import Combine
import CoreBluetooth
import Foundation

// Where the phone is in the link's lifecycle.
//
// Every bug this file has had was two variables disagreeing about one fact:
// connected-but-no-handle, discovering-that-never-finished, ready-that-was-only
// -half-true. `receiving` is that half-truth given a name — the watch is
// talking to us and we hold nothing to answer on.
enum LinkPhase: Equatable {
    case radioDown(CBManagerState)
    case searching
    case connecting
    case resolving
    case receiving
    case ready

    var label: String {
        switch self {
        case let .radioDown(s): "bt-\(s.rawValue)"
        case .searching: "searching"
        case .connecting: "connecting"
        case .resolving: "resolving"
        case .receiving: "receiving"
        case .ready: "connected"
        }
    }

    // How long this phase may last before it is stuck rather than slow.
    // Searching and ready are steady states; the rest are transitions, and a
    // transition that does not finish is the bug.
    var deadline: TimeInterval? {
        switch self {
        case .radioDown, .searching, .ready: nil
        case .connecting: 120
        case .resolving: 10
        case .receiving: 60
        }
    }
}

// Transport only: frames lines off the TX characteristic and hands them to
// WatchEvent to decode. What the numbers mean is not this class's business.
final class BridgeCentral: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private static let serviceUUID = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let rxUUID = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let txUUID = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let maxRxBuffer = 4096
    private static let maxWriteQueue = 128
    private static let tick: TimeInterval = 15
    // Attempts within a phase before we stop retrying it and drop the link.
    private static let maxAttempts = 3
    // The watch pushes a `day` every 15 minutes come what may, so silence past
    // this is not a quiet watch — it is a link that has stopped working.
    private static let silenceProbe: TimeInterval = 1200
    private static let silenceReset: TimeInterval = 1500

    // One published fact. Everything the UI asks is a question about it, so
    // there is nothing left to keep in step.
    @Published private(set) var phase: LinkPhase = .radioDown(.unknown)

    var isLinked: Bool { phase == .receiving || phase == .ready }
    var canSend: Bool { phase == .ready }
    var state: String { phase.label }
    var btBlocked: Bool { phase == .radioDown(.unauthorized) }
    var btOff: Bool { phase == .radioDown(.poweredOff) }

    var onEvent: ((WatchEvent) -> Void)?
    var onReady: (() -> Void)?

    private let diag = Diagnostics.shared

    // Implicitly unwrapped but always accessed with `?`: iOS can call
    // willRestoreState from inside CBCentralManager's own initialiser, before
    // this property has been assigned.
    private var central: CBCentralManager!
    private var subject: CBPeripheral?
    private var rxCharacteristic: CBCharacteristic?
    private var rxBuffer = ""
    private var writeQueue: [Data] = []

    private var enteredAt = Date()
    private var attempts = 0
    private var lastDay = Date()
    private var probed = false
    private var heartbeat: Timer?

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil, options: [
            CBCentralManagerOptionRestoreIdentifierKey: "dreamfit-central",
        ])
        heartbeat = Timer.scheduledTimer(withTimeInterval: Self.tick, repeats: true) { [weak self] _ in
            self?.age()
        }
    }

    deinit { heartbeat?.invalidate() }

    // MARK: - The machine
    //
    // One entry point for every change, so a phase transition cannot happen
    // without its side effect, and a side effect cannot happen without the
    // phase to justify it.

    private func enter(_ next: LinkPhase) {
        guard next != phase else { return }
        append("\(phase.label) → \(next.label)")
        if phase == .searching { central?.stopScan() }
        phase = next
        enteredAt = Date()
        attempts = 0

        switch next {
        case .radioDown:
            forgetConnection()
        case .searching:
            pageKnown()
            scan()
        case .connecting:
            break // the connect request is what got us here
        case .resolving:
            discover()
        case .receiving:
            break // the tick will keep trying to resolve a write handle
        case .ready:
            probed = false
            lastDay = Date()
            onReady?()
            // Re-ask straight away: after a restore the aggregates on screen
            // are as old as the last heartbeat.
            requestSync()
        }
    }

    // A phase that outlives its deadline is retried, and retried a bounded
    // number of times before the link itself is the thing we stop believing.
    private func age() {
        if let deadline = phase.deadline, Date().timeIntervalSince(enteredAt) > deadline {
            attempts += 1
            enteredAt = Date()
            switch phase {
            case .connecting:
                append("connect pending too long, re-paging")
                enter(.searching)
            case .resolving, .receiving:
                if attempts >= Self.maxAttempts {
                    append("no write handle after \(attempts) tries, dropping link")
                    dropConnection()
                } else {
                    append("rediscovering (\(attempts))")
                    discover()
                }
            default:
                break
            }
        }
        checkFreshness()
    }

    // Liveness is measured in `day` messages, not in bytes: heart rate arrives
    // every 5s and would mask a link that can receive but no longer send.
    private func checkFreshness() {
        guard isLinked else { return }
        let silent = Date().timeIntervalSince(lastDay)
        if silent > Self.silenceReset {
            append("no day in \(Int(silent))s, dropping link")
            lastDay = Date()
            dropConnection()
        } else if silent > Self.silenceProbe, canSend, !probed {
            probed = true
            append("no day in \(Int(silent))s, probing")
            requestSync()
        }
    }

    private func dropConnection() {
        guard let p = subject else { enter(.searching); return }
        // Dropping is what forces a fresh discovery and a fresh CCCD write on
        // reconnect, which is the part the watch is waiting for.
        central?.cancelPeripheralConnection(p)
        forgetConnection()
        enter(.searching)
    }

    private func forgetConnection() {
        rxCharacteristic = nil
        rxBuffer = ""
        writeQueue.removeAll()
    }

    // MARK: - Actions

    private func discover() {
        guard let p = subject, p.state == .connected else { return }
        p.discoverServices([Self.serviceUUID])
    }

    private func scan() {
        guard central?.state == .poweredOn, subject?.state != .connected else { return }
        central?.scanForPeripherals(withServices: [Self.serviceUUID], options: nil)
    }

    // The watch advertises 30s per 5.5min to save battery. A known peripheral
    // can be paged with no advertising at all, so remember it and skip the wait.
    private var knownUUID: UUID? {
        get { UserDefaults.standard.string(forKey: "watchUUID").flatMap(UUID.init) }
        set { UserDefaults.standard.set(newValue?.uuidString, forKey: "watchUUID") }
    }

    private func pageKnown() {
        guard let id = knownUUID,
              let p = central?.retrievePeripherals(withIdentifiers: [id]).first else { return }
        if p.state == .connected { adopt(p); return }
        guard p.state == .disconnected else { return }
        append("paging known watch")
        connect(p)
    }

    private func connect(_ p: CBPeripheral) {
        subject = p
        p.delegate = self
        central?.connect(p, options: nil)
        enter(.connecting)
    }

    // Take (or re-take) ownership of a peripheral iOS says is already live.
    private func adopt(_ p: CBPeripheral) {
        if subject?.identifier != p.identifier {
            subject = p
            forgetConnection()
        }
        p.delegate = self
        guard p.state == .connected else { return }
        knownUUID = p.identifier
        enter(rxCharacteristic == nil ? .resolving : .ready)
    }

    // MARK: - Central delegate

    // iOS hands back a live connection with notifications still enabled, but it
    // does NOT replay didConnect or characteristic discovery. Without adopting
    // it here the link keeps delivering data while the app believes it is down.
    func centralManager(_: CBCentralManager, willRestoreState dict: [String: Any]) {
        for p in dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? [] {
            adopt(p)
        }
    }

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        guard c.state == .poweredOn else { enter(.radioDown(c.state)); return }
        if let p = subject, p.state == .connected { adopt(p) } else { enter(.searching) }
    }

    func centralManager(_: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData _: [String: Any], rssi: NSNumber) {
        guard phase == .searching, p.state == .disconnected else { return }
        append("found watch \(rssi)")
        connect(p)
    }

    func centralManager(_: CBCentralManager, didConnect p: CBPeripheral) {
        guard p.identifier == subject?.identifier else { return }
        // Handles from any previous connection are void.
        forgetConnection()
        adopt(p)
    }

    // Only the peripheral we are actually using may tear down our state: a late
    // callback for one we already replaced would otherwise mark a live link dead.
    func centralManager(_: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error _: Error?) {
        guard p.identifier == subject?.identifier else { return }
        forgetConnection()
        enter(.searching)
    }

    func centralManager(_: CBCentralManager, didFailToConnect p: CBPeripheral, error _: Error?) {
        guard p.identifier == subject?.identifier else { return }
        // No backoff of our own: `searching` pages the known watch and scans,
        // and the tick re-enters it. A tight retry loop was the old behaviour.
        enter(.searching)
    }

    // MARK: - Peripheral delegate

    func peripheral(_ p: CBPeripheral, didDiscoverServices _: Error?) {
        // iOS caches the attribute database. After the watch app is reinstalled
        // discovery can come back empty while notifications still arrive on the
        // old handle — the tick retries, and gives up into a reconnect.
        guard let services = p.services, !services.isEmpty else {
            append("no services found")
            return
        }
        for s in services {
            p.discoverCharacteristics([Self.rxUUID, Self.txUUID], for: s)
        }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error _: Error?) {
        for ch in s.characteristics ?? [] {
            if ch.uuid == Self.txUUID { p.setNotifyValue(true, for: ch) }
            if ch.uuid == Self.rxUUID { rxCharacteristic = ch }
        }
        if rxCharacteristic != nil { enter(.ready) }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error _: Error?) {
        // Data proves the link is live. If our bookkeeping disagrees, the data
        // wins: hearing the watch without a handle to answer on is `receiving`,
        // a state the tick works to get out of.
        if p.identifier != subject?.identifier { adopt(p) }
        if rxCharacteristic == nil, phase != .receiving { enter(.receiving) }

        guard let d = ch.value, let s = String(data: d, encoding: .utf8) else { return }
        rxBuffer += s
        // A dropped fragment must not wedge the channel for the whole session.
        if rxBuffer.utf8.count > Self.maxRxBuffer {
            append("rx overflow, reset")
            rxBuffer = ""
            return
        }
        while let nl = rxBuffer.firstIndex(of: "\n") {
            let line = String(rxBuffer[rxBuffer.startIndex ..< nl])
            rxBuffer = String(rxBuffer[rxBuffer.index(after: nl)...])
            if line.isEmpty { continue }
            append("rx: \(line)")
            guard let event = WatchEvent.decode(line) else { continue }
            if case .day = event { lastDay = Date(); probed = false }
            onEvent?(event)
        }
    }

    // MARK: - Send

    func requestSync() { send(["t": "sync"]) }

    // The goal lives on the phone and the watch draws its bezel from it, so it
    // is pushed on every change and again whenever the link comes back.
    func sendGoal(_ steps: Int) { send(["t": "goal", "steps": steps]) }

    func sendNotify(title: String, body: String) {
        send(["t": "notify", "title": title, "body": body])
    }

    private func send(_ payload: [String: Any]) {
        guard canSend, let p = subject, let json = try? JSONSerialization.data(withJSONObject: payload) else {
            append("cannot send in \(phase.label)")
            return
        }
        let bytes = json + Data([0x0A])
        let cap = max(1, p.maximumWriteValueLength(for: .withoutResponse))
        guard writeQueue.count + bytes.count / cap < Self.maxWriteQueue else {
            append("tx queue full, dropped")
            return
        }
        for i in stride(from: 0, to: bytes.count, by: cap) {
            writeQueue.append(bytes[i ..< min(i + cap, bytes.count)])
        }
        drainWrites()
    }

    // Without-response writes are silently discarded once the queue is full;
    // a partial message means the watch never sees a newline.
    private func drainWrites() {
        guard let p = subject, let ch = rxCharacteristic, p.state == .connected else { return }
        while !writeQueue.isEmpty, p.canSendWriteWithoutResponse {
            p.writeValue(writeQueue.removeFirst(), for: ch, type: .withoutResponse)
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse _: CBPeripheral) { drainWrites() }

    private func append(_ text: String) { diag.note(text) }
}
