import CoreBluetooth
import Foundation

// The watch owns every daily aggregate; `day` is absolute and idempotent.
enum WatchEvent {
    case hr(Int)
    case day(WatchDay)
}

struct WatchDay {
    let date: String
    let steps: Int
    let rhr: Int
    let exMin: Int
    let battery: Int
}

struct LogRow: Identifiable {
    let id = UUID()
    let text: String
}

final class BridgeCentral: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private static let serviceUUID = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let rxUUID = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let txUUID = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let maxLogRows = 200
    private static let maxRxBuffer = 4096
    private static let maxWriteQueue = 128

    @Published private(set) var state = "starting"
    @Published private(set) var log: [LogRow] = []
    @Published private(set) var isReady = false

    var onEvent: ((WatchEvent) -> Void)?

    // Implicitly unwrapped but always accessed with `?`: iOS can call
    // willRestoreState from inside CBCentralManager's own initialiser, before
    // this property has been assigned.
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rxCharacteristic: CBCharacteristic?
    private var connectingIDs = Set<UUID>()
    private var discovering = false
    private var rxBuffer = ""
    private var writeQueue: [Data] = []
    private var retryDelay: TimeInterval = 1

    var btBlocked: Bool { central?.state == .unauthorized }
    var btOff: Bool { central?.state == .poweredOff }

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil, options: [
            CBCentralManagerOptionRestoreIdentifierKey: "dreamfit-central",
        ])
    }

    // MARK: - Readiness
    //
    // Derived from the link itself, never latched by whichever callback
    // happened to fire. A restored connection is just as ready as a fresh one.

    private func refreshReady() {
        let ready = peripheral?.state == .connected && rxCharacteristic != nil
        if ready != isReady { isReady = ready }
        if ready {
            state = "connected"
        } else if let c = central, c.state != .poweredOn {
            state = "bt-\(c.state.rawValue)"
        } else {
            state = "searching"
        }
    }

    // Take (or re-take) ownership of a peripheral and make sure we hold live
    // characteristic handles for it.
    private func adopt(_ p: CBPeripheral) {
        if peripheral?.identifier != p.identifier {
            peripheral = p
            rxCharacteristic = nil
            rxBuffer = ""
        }
        p.delegate = self
        if p.state == .connected {
            knownUUID = p.identifier
            central?.stopScan()
            if rxCharacteristic == nil, !discovering {
                discovering = true
                p.discoverServices([Self.serviceUUID])
            }
        }
        refreshReady()
    }

    // MARK: - Discovery

    // The watch advertises 30s per 5.5min to save battery. A known peripheral
    // can be paged with no advertising at all, so remember it and skip the wait.
    private var knownUUID: UUID? {
        get { UserDefaults.standard.string(forKey: "watchUUID").flatMap(UUID.init) }
        set { UserDefaults.standard.set(newValue?.uuidString, forKey: "watchUUID") }
    }

    // iOS hands back a live connection with notifications still enabled, but it
    // does NOT replay didConnect or characteristic discovery. Without adopting
    // it here the link keeps delivering data while the app believes it is down
    // — and every write fails, because there is no RX handle.
    func centralManager(_: CBCentralManager, willRestoreState dict: [String: Any]) {
        for p in dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? [] {
            adopt(p)
        }
    }

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        guard c.state == .poweredOn else { refreshReady(); return }
        if let p = peripheral, p.state == .connected {
            adopt(p)
        } else {
            connectKnown()
            startScan()
        }
        refreshReady()
    }

    private func connectKnown() {
        guard let id = knownUUID,
              let p = central?.retrievePeripherals(withIdentifiers: [id]).first else { return }
        if p.state == .connected { adopt(p); return }
        guard p.state == .disconnected else { return }
        connect(p)
        append("paging known watch")
    }

    private func startScan() {
        guard central?.state == .poweredOn, peripheral?.state != .connected else { return }
        central?.scanForPeripherals(withServices: [Self.serviceUUID], options: nil)
    }

    private func connect(_ p: CBPeripheral) {
        guard !connectingIDs.contains(p.identifier) else { return }
        connectingIDs.insert(p.identifier)
        peripheral = p
        p.delegate = self
        central?.connect(p, options: nil)
    }

    func centralManager(_: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData _: [String: Any], rssi: NSNumber) {
        guard p.state == .disconnected else { return }
        append("found watch \(rssi)")
        connect(p)
    }

    func centralManager(_: CBCentralManager, didConnect p: CBPeripheral) {
        connectingIDs.remove(p.identifier)
        retryDelay = 1
        // Handles from any previous connection are void.
        rxCharacteristic = nil
        discovering = false
        adopt(p)
        append("connected")
    }

    // Only the peripheral we are actually using may tear down our state: a late
    // callback for one we already replaced would otherwise mark a live link dead.
    func centralManager(_: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error _: Error?) {
        connectingIDs.remove(p.identifier)
        guard p.identifier == peripheral?.identifier else { return }
        peripheral = nil
        rxCharacteristic = nil
        discovering = false
        rxBuffer = ""
        writeQueue.removeAll()
        refreshReady()
        append("disconnected, rescanning")
        connectKnown()
        startScan()
    }

    // Exponential backoff: a tight retry loop on a watch that is asleep burns
    // both radios for nothing.
    func centralManager(_: CBCentralManager, didFailToConnect p: CBPeripheral, error _: Error?) {
        connectingIDs.remove(p.identifier)
        append("connect failed, retry in \(Int(retryDelay))s")
        let delay = retryDelay
        retryDelay = min(retryDelay * 2, 60)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, self.peripheral?.state != .connected else { return }
            self.connect(p)
        }
    }

    // MARK: - GATT

    func peripheral(_ p: CBPeripheral, didDiscoverServices _: Error?) {
        for s in p.services ?? [] {
            p.discoverCharacteristics([Self.rxUUID, Self.txUUID], for: s)
        }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error _: Error?) {
        discovering = false
        for ch in s.characteristics ?? [] {
            if ch.uuid == Self.txUUID { p.setNotifyValue(true, for: ch) }
            if ch.uuid == Self.rxUUID { rxCharacteristic = ch }
        }
        refreshReady()
        if isReady {
            append("ready")
            // Re-ask straight away: after a restore the aggregates on screen
            // are as old as the last heartbeat.
            requestSync()
        }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error _: Error?) {
        // Data proves the link is live. If our bookkeeping disagrees, the data
        // wins and we re-acquire handles for whoever is actually talking.
        if p.identifier != peripheral?.identifier || rxCharacteristic == nil { adopt(p) }

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
            if !line.isEmpty { route(line) }
        }
    }

    private func route(_ s: String) {
        append("rx: \(s)")
        guard let data = s.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let t = o["t"] as? String else { return }
        switch t {
        case "hr":
            if let bpm = o["bpm"] as? Int { onEvent?(.hr(bpm)) }
        case "day":
            guard let d = o["d"] as? String, let steps = o["steps"] as? Int else { return }
            onEvent?(.day(WatchDay(date: d, steps: steps,
                                   rhr: o["rhr"] as? Int ?? 0,
                                   exMin: o["exmin"] as? Int ?? 0,
                                   battery: o["bat"] as? Int ?? -1)))
        default:
            break
        }
    }

    // MARK: - Send

    func requestSync() { send(["t": "sync"]) }

    func sendNotify(title: String, body: String) {
        send(["t": "notify", "title": title, "body": body])
    }

    private func send(_ payload: [String: String]) {
        guard let p = peripheral, p.state == .connected, rxCharacteristic != nil else {
            append("not ready")
            startScan()
            return
        }
        guard let json = try? JSONSerialization.data(withJSONObject: payload) else { return }
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
        guard let p = peripheral, let ch = rxCharacteristic, p.state == .connected else { return }
        while !writeQueue.isEmpty, p.canSendWriteWithoutResponse {
            p.writeValue(writeQueue.removeFirst(), for: ch, type: .withoutResponse)
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse _: CBPeripheral) { drainWrites() }

    // MARK: - Log

    private func append(_ text: String) {
        log.append(LogRow(text: text))
        if log.count > Self.maxLogRows { log.removeFirst(log.count - Self.maxLogRows) }
    }
}
