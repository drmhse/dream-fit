import SwiftUI

@main
struct DreamFitApp: App {
    @StateObject private var ble = BridgeCentral()
    @StateObject private var health = HealthStore()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(ble)
                .environmentObject(health)
                .task {
                    ble.onEvent = { event in Task { @MainActor in health.apply(event) } }
                    health.requestAuth()
                }
                // onAppear does not re-fire on foreground return, and Health
                // toggles can be flipped in Settings while we are away.
                .onChange(of: phase) { _, new in
                    guard new == .active else { return }
                    health.evaluateAuth()
                    ble.requestSync()
                }
        }
    }
}
