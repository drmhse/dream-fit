import Foundation
import HealthKit

enum HealthAuth: String {
    case unknown, unavailable, setup, denied, authorized
}

// Async facade over HKHealthStore. Every call returns or throws: the callback
// API's habit of handing back an error nobody reads is what let the step mirror
// fail in silence for a whole day.
struct HealthKitSink {
    static let hr = HKQuantityType(.heartRate)
    static let steps = HKQuantityType(.stepCount)
    static let rhr = HKQuantityType(.restingHeartRate)
    static let distance = HKQuantityType(.distanceWalkingRunning)
    static let floors = HKQuantityType(.flightsClimbed)
    static let sleep = HKCategoryType(.sleepAnalysis)

    private static let bpmUnit = HKUnit.count().unitDivided(by: .minute())

    // The unit a type is summed and written in. Getting this wrong writes a
    // distance in calories, which HealthKit will accept without complaint.
    static func unit(for type: HKQuantityType) -> HKUnit {
        switch type {
        case distance: .meter()
        default: .count()
        }
    }

    private let store = HKHealthStore()

    var available: Bool { HKHealthStore.isHealthDataAvailable() }

    func status(_ type: HKSampleType) -> HKAuthorizationStatus {
        store.authorizationStatus(for: type)
    }

    func canWrite(_ type: HKSampleType) -> Bool { status(type) == .sharingAuthorized }

    // Every type the mirror writes, with the name Health shows for it, so a
    // refusal can be named on screen rather than left looking like missing data.
    static let mirrored: [(HKSampleType, String)] = [
        (steps, "Steps"), (hr, "Heart Rate"), (rhr, "Resting Heart Rate"),
        (distance, "Distance"), (floors, "Flights Climbed"), (sleep, "Sleep"),
    ]

    // appleExerciseTime is deliberately absent: it is Apple-reserved and merely
    // requesting it terminates the process (bisected).
    func requestAuth() async {
        // activeEnergyBurned is deliberately absent: Health Services reports
        // total calories including BMR, and there is no honest way to split
        // that into the active figure HealthKit means. The number is shown in
        // the app and never mirrored.
        let types: Set<HKSampleType> = [
            Self.hr, Self.steps, Self.rhr, Self.distance, Self.floors, Self.sleep,
        ]
        _ = try? await store.requestAuthorization(toShare: types, read: types)
    }

    // MARK: - Writes
    //
    // One entry point per unit: a single save(value:as:) invites the next caller
    // to write a step count in beats per minute.

    func saveBPM(_ bpm: Int, type: HKQuantityType, at date: Date) async throws {
        try await save(HKQuantity(unit: Self.bpmUnit, doubleValue: Double(bpm)), type: type, at: date)
    }

    // Exactly one resting heart rate per day. It is a single figure for the
    // day and the day's minimum only ever falls, so appending each new low
    // leaves Apple Health showing three or four resting rates for one day.
    func saveDailyRHR(_ bpm: Int, in range: DayRange, at date: Date) async throws {
        try await deleteMine(Self.rhr, in: range)
        try await save(HKQuantity(unit: Self.bpmUnit, doubleValue: Double(bpm)), type: Self.rhr, at: date)
    }

    // A delta covers the period since the last one, so that is the interval it
    // is written over. Writing it as an instant instead is what let HealthKit's
    // cross-source merge treat the same figure two different ways: an instant
    // landing inside a denser source's interval is absorbed, and one landing in
    // a gap has nothing to merge against and is added on top. Floors are sparse
    // enough to be almost all gap, which is how a two-floor climb counted by
    // both devices came to read as four.
    func saveDelta(_ value: Double, type: HKQuantityType, from start: Date, to end: Date) async throws {
        let quantity = HKQuantity(unit: Self.unit(for: type), doubleValue: value)
        try await store.save(
            HKQuantitySample(type: type, quantity: quantity, start: min(start, end), end: end),
        )
    }

    // Replace rather than append: the watch may re-send the same night after a
    // reconnect, and two overlapping sleep samples read as two nights.
    func saveSleep(_ record: SleepRecord) async throws {
        let range = DayRange(start: record.start, end: record.end)
        try await deleteMine(Self.sleep, in: range)
        try await store.save(
            HKCategorySample(type: Self.sleep,
                             value: HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue,
                             start: record.start, end: record.end),
        )
    }

    private func save(_ quantity: HKQuantity, type: HKQuantityType, at date: Date) async throws {
        try await store.save(HKQuantitySample(type: type, quantity: quantity, start: date, end: date))
    }

    // MARK: - Our own samples only
    //
    // Another source's steps are not ours to sum and certainly not ours to
    // delete. HealthKit also returns an app's own samples even when read access
    // is denied, so this predicate keeps the reconcile honest under a partial grant.

    func mySum(_ type: HKQuantityType, in range: DayRange) async throws -> Int {
        try await withCheckedThrowingContinuation { k in
            let query = HKStatisticsQuery(quantityType: type,
                                          quantitySamplePredicate: Self.mine(in: range),
                                          options: .cumulativeSum) { _, result, error in
                if let error, !Self.isNoData(error) {
                    k.resume(throwing: error)
                } else {
                    let unit = Self.unit(for: type)
                    k.resume(returning: Int(result?.sumQuantity()?.doubleValue(for: unit) ?? 0))
                }
            }
            store.execute(query)
        }
    }


    // Per source sums and the merged total from one query. This is the only
    // reading that separates a merge from an addition: `separateBySource`
    // reports what each contributor wrote, `sumQuantity` reports what Health
    // would show for the same interval, and the two only agree when one source
    // covered everything.
    func sourceBreakdown(_ type: HKQuantityType,
                         in range: DayRange) async throws -> (merged: Int, bySource: [(String, Int)]) {
        try await withCheckedThrowingContinuation { k in
            let query = HKStatisticsQuery(
                quantityType: type,
                quantitySamplePredicate: HKQuery.predicateForSamples(withStart: range.start, end: range.end),
                options: [.cumulativeSum, .separateBySource],
            ) { _, result, error in
                if let error, !Self.isNoData(error) { k.resume(throwing: error); return }
                let unit = Self.unit(for: type)
                let merged = Int(result?.sumQuantity()?.doubleValue(for: unit) ?? 0)
                let per = (result?.sources ?? []).map { source in
                    (source.name, Int(result?.sumQuantity(for: source)?.doubleValue(for: unit) ?? 0))
                }
                k.resume(returning: (merged, per.sorted { $0.1 > $1.1 }))
            }
            store.execute(query)
        }
    }

    func deleteMine(_ type: HKSampleType, in range: DayRange) async throws {
        try await delete(type, Self.mine(from: range.start, to: range.end))
    }

    private func delete(_ type: HKSampleType, _ predicate: NSPredicate) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            store.deleteObjects(of: type, predicate: predicate) { _, _, error in
                if let error, !Self.isNoData(error) { k.resume(throwing: error) } else { k.resume() }
            }
        }
    }

    // An empty range is a sum of zero and a delete of nothing — not a failure.
    // HealthKit reports both as errorNoData, and treating that as an error is a
    // deadlock rather than a retry: the first sample of a new day can never be
    // written, so the range stays empty, so the query keeps failing.
    private static func isNoData(_ error: Error) -> Bool {
        let e = error as NSError
        return e.domain == HKError.errorDomain && e.code == HKError.Code.errorNoData.rawValue
    }

    private static func mine(in range: DayRange) -> NSPredicate {
        mine(from: range.start, to: range.end)
    }

    private static func mine(from start: Date, to end: Date) -> NSPredicate {
        NSCompoundPredicate(andPredicateWithSubpredicates: [
            HKQuery.predicateForSamples(withStart: start, end: end),
            HKQuery.predicateForObjects(from: [.default()]),
        ])
    }
}
