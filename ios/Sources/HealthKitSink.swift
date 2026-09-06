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

    private static let bpmUnit = HKUnit.count().unitDivided(by: .minute())

    private let store = HKHealthStore()

    var available: Bool { HKHealthStore.isHealthDataAvailable() }

    func status(_ type: HKQuantityType) -> HKAuthorizationStatus {
        store.authorizationStatus(for: type)
    }

    func canWrite(_ type: HKQuantityType) -> Bool { status(type) == .sharingAuthorized }

    // appleExerciseTime is deliberately absent: it is Apple-reserved and merely
    // requesting it terminates the process (bisected).
    func requestAuth() async {
        let types: Set<HKSampleType> = [Self.hr, Self.steps, Self.rhr]
        _ = try? await store.requestAuthorization(toShare: types, read: types)
    }

    // MARK: - Writes
    //
    // One entry point per unit: a single save(value:as:) invites the next caller
    // to write a step count in beats per minute.

    func saveBPM(_ bpm: Int, type: HKQuantityType, at date: Date) async throws {
        try await save(HKQuantity(unit: Self.bpmUnit, doubleValue: Double(bpm)), type: type, at: date)
    }

    func saveSteps(_ steps: Int, at date: Date) async throws {
        try await save(HKQuantity(unit: .count(), doubleValue: Double(steps)), type: Self.steps, at: date)
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
                    k.resume(returning: Int(result?.sumQuantity()?.doubleValue(for: .count()) ?? 0))
                }
            }
            store.execute(query)
        }
    }

    func deleteMine(_ type: HKQuantityType, in range: DayRange) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            store.deleteObjects(of: type, predicate: Self.mine(in: range)) { _, _, error in
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
        NSCompoundPredicate(andPredicateWithSubpredicates: [
            HKQuery.predicateForSamples(withStart: range.start, end: range.end),
            HKQuery.predicateForObjects(from: [.default()]),
        ])
    }
}
