import Foundation
import HealthKit

// Everything that stands between what the watch said and what Apple Health
// holds.
//
// One writer. The watch's delta lane carries every step, metre and floor with
// the span it happened in, and its heart rate with the minute it was measured
// in; those are written straight through and nothing else writes at all. The
// daily absolute is an audit and a display value, never a source: topping it up
// against what Health already held was how the same steps came to be counted
// twice, once by each lane.
//
// Nothing that cannot be written now is dropped. HealthKit is sealed while the
// phone is locked, which is where a phone spends most of its day, and the watch
// forgets a delivery the moment the radio confirms it — so a write that fails
// is kept and retried on the next push, on unlock, on an authorisation change
// and on foreground.
@MainActor
final class HealthMirror {
    private struct DayValue: Equatable {
        let date: String
        let value: Int
    }

    // One thing the watch handed over. Movement carries the span it happened
    // in; a beat is an instant, so its span is a point. Kind plus that exact
    // span identifies it uniquely by construction, which is what makes a resend
    // safe to ignore: no acknowledgement travels back, and none is needed.
    private struct Delivery: Codable, Equatable {
        enum Kind: String, Codable { case steps, dist, floors, hr }
        var kind: Kind
        var value: Double
        var start: Date
        var end: Date

        var key: String {
            let ms = { (d: Date) in Int(d.timeIntervalSince1970 * 1000) }
            return "\(kind.rawValue)|\(ms(start))|\(ms(end))"
        }

        var type: HKQuantityType {
            switch kind {
            case .steps: HealthKitSink.steps
            case .dist: HealthKitSink.distance
            case .floors: HealthKitSink.floors
            case .hr: HealthKitSink.hr
            }
        }
    }

    private let sink: HealthKitSink
    private let diag: Diagnostics
    private let persist: (any Encodable, String) -> Void

    private var mirroredRHR: DayValue?
    private var mirroredSleep: SleepRecord?
    private var pendingSleep: SleepRecord?
    private var mirroringSleep = false
    private var incarnation: String?
    // Handed over by the watch and not yet accepted by HealthKit. The watch has
    // already forgotten these, so dropping one loses it for good.
    private var unwritten: [Delivery] = []
    private var draining = false

    init(sink: HealthKitSink, diag: Diagnostics, mirroredSleep: SleepRecord?,
         written: [String], unwritten: [Data], persist: @escaping (any Encodable, String) -> Void) {
        self.sink = sink
        self.diag = diag
        self.mirroredSleep = mirroredSleep
        self.writtenOrder = written
        self.written = Set(written)
        self.unwritten = unwritten.compactMap { try? JSONDecoder().decode(Delivery.self, from: $0) }
        self.persist = persist
    }

    static let sleepMirroredKey = "sleepMirrored"
    static let writtenKey = "deltasWritten"
    static let unwrittenKey = "deltasUnwritten"

    // A day of deltas and minute beats, with margin. Past this a resend cannot
    // arrive, so the key is of no further use.
    private static let writtenLimit = 8_000
    // Beyond this the belt has outrun HealthKit for so long that the oldest are
    // a lost cause; losing them loudly beats growing without limit.
    private static let unwrittenLimit = 10_000

    private var written: Set<String> = []
    private var writtenOrder: [String] = []
    private var writeBackScheduled = false

    // MARK: - What the watch told us

    func accept(_ record: DayRecord) {
        // A restart is invisible without being told: the watch is supervised and
        // comes straight back, looking to the phone like an ordinary push.
        if let inc = record.incarnation, inc != incarnation {
            if incarnation != nil { diag.note("watch restarted (\(inc))") }
            incarnation = inc
        }
        // The only thing a day push writes. The heartbeat re-sends the same
        // aggregates every 15 minutes, and resting HR is one figure for the
        // day: write it only when it moves.
        guard record.rhr > 0, mirroredRHR != DayValue(date: record.date, value: record.rhr),
              let range = record.range, sink.canWrite(HealthKitSink.rhr) else { return }
        mirroredRHR = DayValue(date: record.date, value: record.rhr)
        Task { [sink, diag] in
            do { try await sink.saveDailyRHR(record.rhr, in: range, at: min(Date(), range.end)) }
            catch { diag.note("resting heart rate write failed: \(error.localizedDescription)") }
        }
    }

    func accept(_ delta: Delta) {
        let kind: Delivery.Kind = switch delta.kind {
        case .steps: .steps
        case .dist: .dist
        case .floors: .floors
        }
        enqueue(Delivery(kind: kind, value: delta.value, start: delta.start, end: delta.end))
    }

    func accept(_ beat: Beat) {
        enqueue(Delivery(kind: .hr, value: Double(beat.bpm), start: beat.at, end: beat.at))
    }

    func accept(_ record: SleepRecord) {
        pendingSleep = record
        flush()
    }

    // MARK: - Draining

    func flush() {
        flushSleep()
        drain()
    }

    // Removes what this app wrote for the day. A repair, not a rewrite: the
    // deltas that placed those samples are long confirmed and will not come
    // again, so what this clears is gone.
    func clearToday(_ record: DayRecord) {
        guard let range = record.range else { return }
        Task {
            for type in [HealthKitSink.steps, HealthKitSink.distance, HealthKitSink.floors]
            where sink.canWrite(type) {
                do { try await sink.deleteMine(type, in: range) }
                catch { diag.note("clear failed: \(error.localizedDescription)") }
            }
            diag.note("cleared this app's samples for \(record.date)")
        }
    }

    private func enqueue(_ delivery: Delivery) {
        guard !written.contains(delivery.key),
              !unwritten.contains(where: { $0.key == delivery.key }) else { return }
        unwritten.append(delivery)
        if unwritten.count > Self.unwrittenLimit {
            let shed = unwritten.count - Self.unwrittenLimit
            unwritten.removeFirst(shed)
            diag.note("unwritten queue full, \(shed) oldest dropped")
        }
        // Immediately, unlike the dedupe keys: losing one of these loses data,
        // and the queue is only ever long when HealthKit has been unavailable.
        persistUnwritten()
        drain()
    }

    private func drain() {
        guard !draining, !unwritten.isEmpty else { return }
        draining = true
        Task {
            while let next = unwritten.first {
                // A refused type is the banner's problem. Holding its writes
                // forever would stall every other type behind them.
                guard sink.canWrite(next.type) else { unwritten.removeFirst(); continue }
                do {
                    try await write(next)
                } catch {
                    diag.note("\(next.kind.rawValue) write deferred: \(error.localizedDescription)")
                    break
                }
                remember(next.key)
                unwritten.removeFirst()
            }
            persistUnwritten()
            draining = false
        }
    }

    private func write(_ d: Delivery) async throws {
        if d.kind == .hr {
            try await sink.saveBPM(Int(d.value), type: HealthKitSink.hr, at: d.start)
        } else {
            try await sink.saveDelta(d.value, type: d.type, from: d.start, to: d.end)
        }
    }

    private func flushSleep() {
        guard !mirroringSleep, let record = pendingSleep, record != mirroredSleep,
              sink.canWrite(HealthKitSink.sleep) else { return }
        mirroringSleep = true
        Task {
            do {
                try await sink.saveSleep(record)
                mirroredSleep = record
                persist(record, Self.sleepMirroredKey)
            } catch {
                diag.note("sleep mirror deferred: \(error.localizedDescription)")
            }
            mirroringSleep = false
        }
    }

    // MARK: - Storage

    private func remember(_ key: String) {
        guard written.insert(key).inserted else { return }
        writtenOrder.append(key)
        if writtenOrder.count > Self.writtenLimit {
            let shed = writtenOrder.prefix(writtenOrder.count - Self.writtenLimit)
            shed.forEach { written.remove($0) }
            writtenOrder.removeFirst(shed.count)
        }
        scheduleWriteBack()
    }

    // Coalesced: draining a day's backlog remembers a key per message, and
    // rewriting the whole set each time would cost more than the writes do. The
    // worst a lost tail costs is a duplicate on a resend that never came.
    private func scheduleWriteBack() {
        guard !writeBackScheduled else { return }
        writeBackScheduled = true
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard let self else { return }
            self.writeBackScheduled = false
            self.persist(Array(self.writtenOrder.suffix(Self.writtenLimit)), Self.writtenKey)
        }
    }

    private func persistUnwritten() {
        persist(unwritten.compactMap { try? JSONEncoder().encode($0) }, Self.unwrittenKey)
    }
}
