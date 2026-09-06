import SwiftUI

@main
@MainActor
struct DreamFitApp: App {
    @StateObject private var ble: BridgeCentral
    @StateObject private var health: HealthStore
    @Environment(\.scenePhase) private var phase

    // The link is wired to the model here, not in a view's .task. Core Bluetooth
    // state restoration can relaunch this process straight into the background,
    // where no view lifecycle runs — and every event the watch sent would land
    // on an unwired closure and be lost.
    init() {
        let health = HealthStore()
        let ble = BridgeCentral()
        ble.onEvent = { event in Task { @MainActor in health.apply(event) } }
        // The watch's bezel follows the phone's goal: pushed when it changes,
        // and again whenever the link comes back.
        health.onGoalChange = { [weak ble] goal in ble?.sendGoal(goal) }
        ble.onReady = { [weak ble] in Task { @MainActor in ble?.sendGoal(health.stepGoal) } }
        _health = StateObject(wrappedValue: health)
        _ble = StateObject(wrappedValue: ble)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(ble)
                .environmentObject(health)
                // Asking for authorisation shows UI, so it waits for a screen.
                // Reading the existing grant does not, and already happened in
                // HealthStore's init.
                .task { health.requestAuth() }
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
