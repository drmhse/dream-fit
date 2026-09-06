import Combine
import Foundation
import os

// One log for both halves of the app. The step mirror used to fail invisibly,
// so anything that can fail without a user-visible symptom writes here.
final class Diagnostics: ObservableObject {
    struct Row: Identifiable {
        let id = UUID()
        let text: String
    }

    static let shared = Diagnostics()
    private static let limit = 200
    // Also to the system log: the in-app panel is behind five taps and is gone
    // the moment the app is killed, which is exactly when you want the history.
    private static let log = Logger(subsystem: "com.drmhse.dream.fit", category: "bridge")

    @Published private(set) var rows: [Row] = []

    func note(_ text: String) {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { self.note(text) }
            return
        }
        Self.log.notice("\(text, privacy: .public)")
        #if DEBUG
        // devicectl's console reads stdout, and watching a bridge run on a real
        // phone is most of debugging one.
        print("[bridge] \(text)")
        #endif
        rows.append(Row(text: text))
        if rows.count > Self.limit { rows.removeFirst(rows.count - Self.limit) }
    }
}
