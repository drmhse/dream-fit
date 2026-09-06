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
    private let defaults = UserDefaults.standard
    private let diag = Diagnostics.shared
    private var unlockObserver: NSObjectProtocol?

    private enum Key {
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
    var exerciseMin: Int? { todayRecord?.exMin }
    var restingHR: Int? { todayRecord.map(\.rhr).flatMap { $0 > 0 ? $0 : nil } }
    var watchBattery: Int? { day.map(\.battery).flatMap { $0 >= 0 ? $0 : nil } }
    var bpmHistory: [Int] { beats.map(\.bpm) }

    var currentBPM: Int? {
        guard let last = beats.last, Date().timeIntervalSince(last.at) < Self.beatFreshness else { return nil }
        return last.bpm
    }

    // MARK: - Watch events

    func apply(_ event: WatchEvent) {
        switch event {
        case let .hr(bpm): applyBeat(bpm)
        case let .day(record): applyDay(record)
        }
    }

    private func applyBeat(_ bpm: Int) {
        let beat = Beat(bpm: bpm, at: Date())
        beats.append(beat)
        trimBeats()
        persist(beats, Key.beats)
        write(HealthKitSink.hr, "heart rate") { try await $0.saveBPM(bpm, type: HealthKitSink.hr, at: beat.at) }
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
        if record.rhr > 0, mirroredRHR != DayValue(date: record.date, value: record.rhr) {
            mirroredRHR = DayValue(date: record.date, value: record.rhr)
            write(HealthKitSink.rhr, "resting heart rate") {
                try await $0.saveBPM(record.rhr, type: HealthKitSink.rhr, at: now)
            }
        }
        queued = record
        flush()
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

    // MARK: - Step mirror
    //
    // HealthKit mirrors the watch's absolute; it never accumulates. One
    // reconcile in flight at a time, because HealthKit is append-only and two
    // interleaved passes would each diff against the same stale sum.
    //
    // A day that cannot be mirrored now is queued, never dropped: retried on
    // the next push, on unlock, on an authorisation change and on foreground.

    private struct DayValue: Equatable {
        let date: String
        let value: Int
    }

    private var mirroredRHR: DayValue?
    private var queued: DayRecord?
    private var mirroring = false

    func flush() {
        guard !mirroring, queued != nil else { return }
        mirroring = true
        Task {
            while let record = queued, sink.canWrite(HealthKitSink.steps) {
                do {
                    try await reconcile(record)
                } catch {
                    diag.note("steps mirror deferred: \(error.localizedDescription)")
                    break
                }
                // A push that landed mid-reconcile is newer than what we just
                // wrote and has to go round again.
                if queued == record { queued = nil }
            }
            mirroring = false
        }
    }

    private func reconcile(_ record: DayRecord) async throws {
        guard let range = record.range else { return }
        let have = try await sink.mySum(HealthKitSink.steps, in: range)
        guard record.steps != have else { return }
        let stamp = min(Date(), range.end)
        if record.steps > have {
            try await sink.saveSteps(record.steps - have, at: stamp)
        } else {
            // Self-healing downward: the mirror drifted above the truth. Replace
            // the day wholesale rather than leaving it permanently inflated.
            try await sink.deleteMine(HealthKitSink.steps, in: range)
            if record.steps > 0 { try await sink.saveSteps(record.steps, at: stamp) }
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
