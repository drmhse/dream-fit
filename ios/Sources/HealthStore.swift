import Combine
import Foundation
import HealthKit
import UIKit

// What the watch last told us, plus the mirror of it in Apple Health. The watch
// owns every number here; the phone keeps a durable, timestamped copy so a
// relaunch shows the same facts with the same provenance instead of an empty
// screen.
@MainActor
final class HealthStore: ObservableObject {
    @Published private(set) var day: DayRecord?
    @Published private(set) var confirmedAt: Date?
    @Published private(set) var beats: [Beat] = []
    @Published private(set) var auth: HealthAuth = .unknown
    @Published private(set) var sleep: SleepRecord?

    // The goal is the user's, not ours. The watch draws its bezel from the same
    // number, so every change is pushed across the link.
    @Published var stepGoal: Int = HealthStore.defaultStepGoal {
        didSet {
            guard stepGoal != oldValue else { return }
            defaults.set(stepGoal, forKey: Key.stepGoal)
            onGoalChange?(stepGoal)
        }
    }

    var onGoalChange: ((Int) -> Void)?

    private let sink = HealthKitSink()
    private lazy var mirror = HealthMirror(
        sink: sink, diag: diag,
        mirroredSleep: decode(SleepRecord.self, HealthMirror.sleepMirroredKey),
        // Both through `decode`: `persist` stores JSON, and UserDefaults'
        // typed accessors return nil for it. Read with stringArray, the dedupe
        // set came back empty on every launch and every resend was rewritten.
        written: decode([String].self, HealthMirror.writtenKey) ?? [],
        unwritten: decode([Data].self, HealthMirror.unwrittenKey) ?? [],
        persist: { [weak self] value, key in self?.persist(value, key) },
    )
    private let defaults = UserDefaults.standard
    private let diag = Diagnostics.shared
    private var unlockObserver: NSObjectProtocol?

    private enum Key {
        static let audit = "stepAudit"
        static let sleep = "sleepSnapshot"
        static let day = "daySnapshot"
        static let dayAt = "daySnapshotAt"
        static let beats = "beats"
        static let stepGoal = "stepGoal"
    }

    static let defaultStepGoal = 10_000
    static let stepGoalRange = 1_000 ... 50_000
    static let stepGoalStep = 500

    // A beat older than this is history, not a current reading.
    private static let beatFreshness: TimeInterval = 300
    // The chart is a live trace, not a diary: yesterday's beats must not come
    // back as today's after a relaunch.
    private static let beatWindow: TimeInterval = 3600
    private static let beatLimit = 60

    init() {
        day = decode(DayRecord.self, Key.day)
        sleep = decode(SleepRecord.self, Key.sleep)
        confirmedAt = defaults.object(forKey: Key.dayAt) as? Date
        beats = decode([Beat].self, Key.beats) ?? []
        trimBeats()
        let saved = defaults.integer(forKey: Key.stepGoal)
        stepGoal = Self.stepGoalRange.contains(saved) ? saved : Self.defaultStepGoal

        // Reading the grant is free and shows no UI. Without it a process that
        // Core Bluetooth relaunched into the background never learns it may
        // write, and queues every push the watch sends all day.
        evaluateAuth()

        // HealthKit is sealed while the phone is locked — where a phone spends
        // most of its day — so unlocking is the moment to retry.
        unlockObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification,
            object: nil, queue: .main,
        ) { [weak self] _ in
            Task { @MainActor in self?.flush() }
        }
    }

    deinit {
        if let unlockObserver { NotificationCenter.default.removeObserver(unlockObserver) }
    }

    // MARK: - Derived facts
    //
    // Every one of these is nil-able on purpose: "we don't know" and "zero" are
    // different answers, and the UI has to be able to tell them apart.

    private var todayRecord: DayRecord? { day?.isToday == true ? day : nil }

    var todaySteps: Int? { todayRecord?.steps }
    var restingHR: Int? { todayRecord.map(\.rhr).flatMap { $0 > 0 ? $0 : nil } }
    var watchBattery: Int? { day.map(\.battery).flatMap { $0 >= 0 ? $0 : nil } }
    var bpmHistory: [Int] { beats.map(\.bpm) }
    var todayDistanceM: Int? { todayRecord.map(\.distanceM).flatMap { $0 > 0 ? $0 : nil } }
    var todayFloors: Int? { todayRecord.map(\.floors).flatMap { $0 > 0 ? $0 : nil } }

    // The night the watch last closed, shown only while it is recent enough to
    // be last night rather than a fragment of history the phone still holds.
    var lastNight: SleepRecord? {
        guard let sleep, Date().timeIntervalSince(sleep.end) < 36 * 3600 else { return nil }
        return sleep
    }

    var currentBPM: Int? {
        guard let last = beats.last, Date().timeIntervalSince(last.at) < Self.beatFreshness else { return nil }
        return last.bpm
    }

    // MARK: - Watch events

    func apply(_ event: WatchEvent) {
        switch event {
        case let .hr(bpm, at): applyBeat(bpm, at)
        case let .day(record): applyDay(record)
        case let .sleep(record): applySleep(record)
        case let .delta(delta): mirror.accept(delta)
        }
    }

    // A beat with no time is the watch's live tile: shown, never filed. One
    // with a time is a measurement, and the mirror is the only thing that files
    // it — arriving hours late after a day apart is normal and must still land
    // on the minute it was taken.
    private func applyBeat(_ bpm: Int, _ at: Date?) {
        let beat = Beat(bpm: bpm, at: at ?? Date())
        if Date().timeIntervalSince(beat.at) < Self.beatWindow {
            beats.append(beat)
            beats.sort { $0.at < $1.at }
            trimBeats()
            persist(beats, Key.beats)
        }
        if at != nil { mirror.accept(beat) }
    }

    // Only a day message confirms the aggregates. An HR beat says nothing about
    // the step count and must never freshen it.
    private func applyDay(_ record: DayRecord) {
        let now = Date()
        day = record
        confirmedAt = now
        persist(record, Key.day)
        defaults.set(now, forKey: Key.dayAt)
        // The heartbeat re-sends the same aggregates every 15 minutes, and
        // resting HR is one figure for the day: write it only when it moves.
        mirror.accept(record)
        audit(record)
    }

    private func applySleep(_ record: SleepRecord) {
        if record != sleep {
            sleep = record
            persist(record, Key.sleep)
        }
        mirror.accept(record)
    }

    private func trimBeats() {
        let cutoff = Date().addingTimeInterval(-Self.beatWindow)
        beats.removeAll { $0.at < cutoff }
        if beats.count > Self.beatLimit { beats.removeFirst(beats.count - Self.beatLimit) }
    }

    // A denied type is the banner's problem, not the log's; anything else is a
    // failure the user cannot see and therefore has to be recorded.
    private func write(_ type: HKQuantityType, _ label: String,
                       _ body: @escaping (HealthKitSink) async throws -> Void) {
        guard sink.canWrite(type) else { return }
        Task { [sink, diag] in
            do { try await body(sink) } catch { diag.note("\(label) write failed: \(error.localizedDescription)") }
        }
    }

    // Retried on the next push, on unlock, on an authorisation change and on
    // foreground — every event that can change the answer. See `HealthMirror`.
    // Unlock and foreground are the two moments a locked-out audit can finally
    // read: HealthKit is sealed while the phone is locked, so a walk with the
    // phone in a pocket produces failures rather than figures, and the useful
    // sample is the first one after it comes back.
    func flush() {
        mirror.flush()
        day.map(audit)
    }

    // What this app wrote for today, what Health shows for it, and what every
    // other source contributed. Sampled on each `day` push rather than only on
    // demand, so a walk carrying both devices leaves a history to read back
    // instead of a single figure taken after the fact. Every line also reaches
    // the unified log through Diagnostics, which is what survives the app being
    // killed.
    @Published private(set) var stepAudit: String?

    private static let auditLimit = 240

    func auditSteps() { day.map(audit) ?? { stepAudit = "no day record yet" }() }

    // Removes what this app wrote for today. A repair for a day already spoiled
    // by an earlier build; what it clears does not come back.
    func rewriteToday() {
        guard let record = day else { stepAudit = "no day record yet"; return }
        mirror.clearToday(record)
        Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            audit(record)
        }
    }

    private func audit(_ record: DayRecord) {
        guard let range = record.range, sink.available else { return }
        Task { [sink, diag] in
            do {
                // Every mirrored type, because the merge does not treat them
                // alike: a dense type absorbs our samples and a sparse one adds
                // them, which is only visible when they are read side by side.
                var parts: [String] = []
                for (type, name) in [(HealthKitSink.steps, "steps"),
                                     (HealthKitSink.distance, "dist"),
                                     (HealthKitSink.floors, "floors")] {
                    let mine = try await sink.mySum(type, in: range)
                    let split = try await sink.sourceBreakdown(type, in: range)
                    let sources = split.bySource.map { "\($0.0)=\($0.1)" }.joined(separator: " ")
                    parts.append("\(name) ours=\(mine) health=\(split.merged) [\(sources)]")
                }
                // The watch's own ledger of what it delivered, which is what
                // separates a delivery that never arrived from a write Health
                // refused: ours < delivered is the phone's fault, delivered <
                // watch is the belt's.
                let ledger = ["steps", "dist", "floors"]
                    .compactMap { k in record.covered?[k].map { "\(k)=\($0.units)" } }
                    .joined(separator: " ")
                let line = "audit d=\(record.date) watch=\(record.steps)/\(record.distanceM)m/"
                    + "\(record.floors)fl · delivered [\(ledger)] · " + parts.joined(separator: " · ")
                stepAudit = line
                diag.note(line)
                var kept = defaults.stringArray(forKey: Key.audit) ?? []
                kept.append("\(ISO8601DateFormatter().string(from: Date())) \(line)")
                if kept.count > Self.auditLimit { kept.removeFirst(kept.count - Self.auditLimit) }
                defaults.set(kept, forKey: Key.audit)
            } catch {
                diag.note("audit failed: \(error.localizedDescription)")
            }
        }
    }


    // MARK: - Authorisation
    //
    // requestAuthorization's result says nothing about what was granted
    // (deliberately, for privacy). The per-type verdict is the only truth, and
    // it is one-shot: after a denial only Settings can restore it.

    // Shows UI, so this belongs to a foreground moment, never to init.
    func requestAuth() {
        guard sink.available else { auth = .unavailable; return }
        Task {
            await sink.requestAuth()
            evaluateAuth()
        }
    }

    // Safe from anywhere, including a background launch. `auth` drives the
    // banner only: each write is gated on its own type, so a heart-rate denial
    // can never stop steps.
    func evaluateAuth() {
        guard sink.available else { auth = .unavailable; return }
        let core = [HealthKitSink.hr, HealthKitSink.steps].map(sink.status)
        if core.allSatisfy({ $0 == .sharingAuthorized }) {
            auth = .authorized
        } else {
            auth = core.allSatisfy { $0 == .notDetermined } ? .setup : .denied
        }
        flush()
    }

    var healthDenied: Bool { auth == .denied }

    // Types Health has been asked about and refused. The banner covers the core
    // pair, because a heart-rate refusal must not read as steps being broken,
    // but a refused type still has to be named: a tile showing "—" means the
    // watch has not said yet, and that is a different answer from never.
    var unsharedMirrors: [String] {
        guard sink.available else { return [] }
        return HealthKitSink.mirrored
            .filter { sink.status($0.0) == .sharingDenied }
            .map(\.1)
    }

    func resolveHealthPermission() {
        if !healthDenied { requestAuth() }
    }

    // MARK: - Storage

    private func persist(_ value: some Encodable, _ key: String) {
        if let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: key) }
    }

    private func decode<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }
}
