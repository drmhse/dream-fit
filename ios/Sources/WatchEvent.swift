import Foundation

// The watch's daily aggregates. Absolute and idempotent: every push carries the
// whole day, so a dropped one costs freshness and nothing else.
struct DayRecord: Codable, Equatable {
    var date: String
    var steps: Int
    var rhr: Int
    var exMin: Int
    var battery: Int
}

struct Beat: Codable, Equatable {
    var bpm: Int
    var at: Date
}

enum WatchEvent: Equatable {
    case hr(Int)
    case day(DayRecord)

    // One JSON object per line on the TX characteristic. An unknown type is
    // skipped rather than rejected: the watch may be newer than the phone.
    static func decode(_ line: String) -> WatchEvent? {
        guard let data = line.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        switch o["t"] as? String {
        case "hr":
            guard let bpm = o["bpm"] as? Int else { return nil }
            return .hr(bpm)
        case "day":
            guard let date = o["d"] as? String, let steps = o["steps"] as? Int else { return nil }
            return .day(DayRecord(date: date, steps: steps,
                                  rhr: o["rhr"] as? Int ?? 0,
                                  exMin: o["exmin"] as? Int ?? 0,
                                  battery: o["bat"] as? Int ?? -1))
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
