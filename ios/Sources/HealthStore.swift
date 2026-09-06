import Foundation
import HealthKit
import UIKit

// What the watch last told us. The watch owns these numbers; the phone keeps a
// durable, timestamped copy so a relaunch shows the same facts with the same
// provenance instead of an empty screen.
struct DaySnapshot: Codable, Equatable {
    var date: String
    var steps: Int
    var rhr: Int
    var exMin: Int
    var battery: Int
    var at: Date
}

struct Beat: Codable, Equatable {
    var bpm: Int
    var at: Date
}

@MainActor
final class HealthStore: ObservableObject {
    private let store = HKHealthStore()
    private let defaults = UserDefaults.standard

    @Published private(set) var day: DaySnapshot?
    @Published private(set) var beats: [Beat] = []
    @Published var authState = "unknown"

    private static let hrType = HKQuantityType(.heartRate)
    private static let stepType = HKQuantityType(.stepCount)
    private static let rhrType = HKQuantityType(.restingHeartRate)
    private static let bpmUnit = HKUnit.count().unitDivided(by: .minute())
    private static let beatLimit = 60
    // A beat older than this is history, not a current reading.
    private static let beatFreshness: TimeInterval = 300

    init() {
        day = decode(DaySnapshot.self, "daySnapshot")
        beats = decode([Beat].self, "beats") ?? []
    }

    // MARK: - Derived facts
    //
    // Every one of these is nil-able on purpose: "we don't know" and "zero" are
    // different answers, and the UI has to be able to tell them apart.

    private var isToday: Bool { day?.date == Self.today }

    var todaySteps: Int? { isToday ? day?.steps : nil }
    var exerciseMin: Int? { isToday ? day?.exMin : nil }
    var restingHR: Int? { isToday && (day?.rhr ?? 0) > 0 ? day?.rhr : nil }
    var watchBattery: Int? { (day?.battery ?? -1) >= 0 ? day?.battery : nil }
    var confirmedAt: Date? { day?.at }
    var bpmHistory: [Int] { beats.map(\.bpm) }

    var currentBPM: Int? {
        guard let last = beats.last, Date().timeIntervalSince(last.at) < Self.beatFreshness else { return nil }
        return last.bpm
    }

    // MARK: - Watch events

    func apply(_ event: WatchEvent) {
        switch event {
        case let .hr(bpm):
            beats.append(Beat(bpm: bpm, at: Date()))
            if beats.count > Self.beatLimit { beats.removeFirst(beats.count - Self.beatLimit) }
            persist(beats, "beats")
            save(bpm, as: Self.hrType)

        case let .day(d):
            let snapshot = DaySnapshot(date: d.date, steps: d.steps, rhr: d.rhr,
                                       exMin: d.exMin, battery: d.battery, at: Date())
            // Only a day message confirms the aggregates. An HR beat says
            // nothing about the step count and must never freshen it.
            day = snapshot
            persist(snapshot, "daySnapshot")
            if d.rhr > 0 { save(d.rhr, as: Self.rhrType) }
            mirrorSteps(d)
        }
    }

    // MARK: - HealthKit sink

    private func save(_ value: Int, as type: HKQuantityType) {
        guard store.authorizationStatus(for: type) == .sharingAuthorized else { return }
        let q = HKQuantity(unit: Self.bpmUnit, doubleValue: Double(value))
        store.save(HKQuantitySample(type: type, quantity: q, start: Date(), end: Date())) { _, _ in }
    }

    // HealthKit mirrors the watch's absolute; it never accumulates. One
    // reconcile in flight at a time, because HealthKit is append-only and two
    // interleaved passes would each diff against the same stale sum.
    private var syncing = false
    private var pending: WatchDay?

    private func mirrorSteps(_ d: WatchDay) {
        guard authState == "authorized" else { pending = d; return }
        guard !syncing else { pending = d; return }
        guard let range = Self.dayRange(d.date) else { return }
        syncing = true

        let q = HKStatisticsQuery(quantityType: Self.stepType,
                                  quantitySamplePredicate: Self.mineOn(range),
                                  options: .cumulativeSum) { [weak self] _, res, error in
            Task { @MainActor in
                guard let self else { return }
                // A failed read is NOT zero steps. Treating it as zero would
                // rewrite the whole day total on top of what is already there.
                guard error == nil else {
                    self.syncing = false
                    return
                }
                self.settle(d, have: Int(res?.sumQuantity()?.doubleValue(for: .count()) ?? 0), range: range)
            }
        }
        store.execute(q)
    }

    private func settle(_ d: WatchDay, have: Int, range: (start: Date, end: Date)) {
        let stamp = min(Date(), range.end)
        if d.steps > have {
            write(d.steps - have, at: stamp)
        } else if d.steps < have {
            // Self-healing downward: the mirror drifted above the truth. Replace
            // the day wholesale rather than leaving it permanently inflated.
            store.deleteObjects(of: Self.stepType, predicate: Self.mineOn(range)) { [weak self] _, _, _ in
                Task { @MainActor in self?.write(d.steps, at: stamp) }
            }
        } else {
            finish()
        }
    }

    private func write(_ steps: Int, at date: Date) {
        guard steps > 0 else { finish(); return }
        let s = HKQuantitySample(type: Self.stepType,
                                 quantity: HKQuantity(unit: .count(), doubleValue: Double(steps)),
                                 start: date, end: date)
        store.save(s) { [weak self] _, _ in
            Task { @MainActor in self?.finish() }
        }
    }

    private func finish() {
        syncing = false
        if let next = pending { pending = nil; mirrorSteps(next) }
    }

    // MARK: - Authorisation

    // requestAuthorization's completion says nothing about what was granted
    // (deliberately, for privacy). The per-type verdict is the only truth, and
    // it is one-shot: after a denial only Settings can restore it.
    func requestAuth() {
        guard HKHealthStore.isHealthDataAvailable() else { authState = "unavailable"; return }
        // appleExerciseTime is deliberately absent: it is Apple-reserved and
        // merely requesting it terminates the process (bisected).
        let types: Set<HKSampleType> = [Self.hrType, Self.stepType, Self.rhrType]
        store.requestAuthorization(toShare: types, read: types) { [weak self] _, _ in
            Task { @MainActor in self?.evaluateAuth() }
        }
    }

    // rhr is not a veto: a denial there must not block steps and HR.
    func evaluateAuth() {
        let core = [Self.hrType, Self.stepType].map { store.authorizationStatus(for: $0) }
        if core.allSatisfy({ $0 == .sharingAuthorized }) {
            authState = "authorized"
            if let p = pending { pending = nil; mirrorSteps(p) }
        } else {
            authState = core.allSatisfy { $0 == .notDetermined } ? "setup" : "denied"
        }
    }

    var healthDenied: Bool { authState == "denied" }

    func resolveHealthPermission() {
        if !healthDenied { requestAuth() }
    }

    // Apple documents no route to another app's Settings page (App-prefs died
    // in iOS 18) and Health has no sub-paths, so this is directions plus
    // transport, not a deep link.
    func openHealthApp() {
        let app = UIApplication.shared
        guard let home = URL(string: "x-apple-health://") else { return }
        app.open(home) { ok in
            guard !ok, let s = URL(string: UIApplication.openSettingsURLString) else { return }
            app.open(s)
        }
    }

    // MARK: - Storage

    private func persist<T: Encodable>(_ value: T, _ key: String) {
        if let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: key) }
    }

    private func decode<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    // MARK: - Dates
    //
    // Day boundaries come from the watch's own calendar date, never from the
    // phone's clock or from UTC: the watch counts the steps, so it decides
    // when the day ended.

    private static let dayFormat: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static var today: String { dayFormat.string(from: Date()) }

    private static func dayRange(_ date: String) -> (start: Date, end: Date)? {
        guard let start = dayFormat.date(from: date),
              let end = Calendar.current.date(byAdding: .day, value: 1, to: start) else { return nil }
        return (start, end)
    }

    private static func mineOn(_ range: (start: Date, end: Date)) -> NSPredicate {
        NSCompoundPredicate(andPredicateWithSubpredicates: [
            HKQuery.predicateForSamples(withStart: range.start, end: range.end),
            HKQuery.predicateForObjects(from: [.default()]),
        ])
    }
}
