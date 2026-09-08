import Foundation

// The watch's daily aggregates. Absolute and idempotent: every push carries the
// whole day, so a dropped one costs freshness and nothing else.
struct DayRecord: Codable, Equatable {
    var date: String
    var steps: Int
    var rhr: Int
    var distanceM: Int
    var floors: Int
    var battery: Int
    // Changes when the watch's bridge restarts, which is the only signal that
    // whatever this process holds in memory about the link is stale.
    var incarnation: String?
    // What the delta lane has already delivered, per kind. Absent from a
    // pre-coverage watch, which is the one case the mirror reads Health back.
    var covered: [String: Coverage]?
}

// Optional throughout: the watch's ledger, not ours, and an older watch has
// none. Both fields are per kind and reset at the watch's own rollover.
struct Coverage: Codable, Equatable {
    var units: Int
    var through: Date?
}

// A night, not a day: sleep crosses midnight, so the watch reports it as an
// absolute interval. Health Services gives asleep/awake transitions and not
// Fitbit's four stages, so this lands in HealthKit as unspecified sleep rather
// than pretending to a staging we cannot read.
struct SleepRecord: Codable, Equatable {
    var start: Date
    var end: Date
}

struct Beat: Codable, Equatable {
    var bpm: Int
    var at: Date
}

// One movement delta with the span it happened in. The watch's daily totals
// stay the authority for the count; this exists so the sample lands on the
// minutes it belongs to, which is what lets Apple Health merge it against the
// iPhone's own reading instead of adding a second climb.
struct Delta: Equatable {
    enum Kind: String { case steps, dist, floors }
    var kind: Kind
    var value: Double
    var start: Date
    var end: Date
}

enum WatchEvent: Equatable {
    // A beat with no time is the live tile and nothing more. One with a time is
    // a measurement, and that is the only kind Apple Health ever sees.
    case hr(Int, Date?)
    case delta(Delta)
    case day(DayRecord)
    case sleep(SleepRecord)

    // One JSON object per line on the TX characteristic. An unknown type is
    // skipped rather than rejected: the watch may be newer than the phone.
    static func decode(_ line: String) -> WatchEvent? {
        guard let data = line.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        switch o["t"] as? String {
        case "hr":
            guard let bpm = o["bpm"] as? Int else { return nil }
            let at = (o["at"] as? Double).map { Date(timeIntervalSince1970: $0 / 1000) }
            return .hr(bpm, at)
        case "day":
            guard let date = o["d"] as? String, let steps = o["steps"] as? Int else { return nil }
            let covered = (o["cov"] as? [String: [Double]])?.compactMapValues { pair -> Coverage? in
                guard let units = pair.first else { return nil }
                let through = pair.count > 1 ? pair[1] : 0
                return Coverage(units: Int(units),
                                through: through > 0 ? Date(timeIntervalSince1970: through / 1000) : nil)
            }
            return .day(DayRecord(date: date, steps: steps,
                                  rhr: o["rhr"] as? Int ?? 0,
                                  distanceM: o["dist"] as? Int ?? 0,
                                  floors: o["floors"] as? Int ?? 0,
                                  battery: o["bat"] as? Int ?? -1,
                                  incarnation: o["inc"] as? String,
                                  covered: covered))
        case "delta":
            guard let k = o["k"] as? String, let kind = Delta.Kind(rawValue: k),
                  let v = o["v"] as? Double, v > 0,
                  let s = o["s"] as? Double, let e = o["e"] as? Double, e >= s else { return nil }
            return .delta(Delta(kind: kind, value: v,
                                start: Date(timeIntervalSince1970: s / 1000),
                                end: Date(timeIntervalSince1970: e / 1000)))
        case "sleep":
            guard let s = o["s"] as? Double, let e = o["e"] as? Double, e > s else { return nil }
            return .sleep(SleepRecord(start: Date(timeIntervalSince1970: s / 1000),
                                      end: Date(timeIntervalSince1970: e / 1000)))
        default:
            return nil
        }
    }
}

// MARK: - Day boundaries
//
// The day comes from the watch's own calendar date, never from the phone's
// clock or from UTC: the watch counts the steps, so it decides when the day
// ended. The phone reads that date in its own timezone, which is right whenever
// the two devices are in the same one — this bridge does not try to reconcile
// a watch and a phone in different zones.

struct DayRange {
    let start: Date
    let end: Date
}

extension DayRecord {
    private static let format: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static var today: String { format.string(from: Date()) }

    var isToday: Bool { date == Self.today }

    var range: DayRange? {
        guard let start = Self.format.date(from: date),
              let end = Calendar.current.date(byAdding: .day, value: 1, to: start) else { return nil }
        return DayRange(start: start, end: end)
    }
}
