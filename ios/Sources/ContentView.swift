import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject var ble: BridgeCentral
    @EnvironmentObject var health: HealthStore
    @Environment(\.openURL) private var openURL
    @AppStorage("showDev") private var showDev = false
    @State private var taps = 0
    @State private var showHealthHelp = false
    @State private var showGoal = false

    private var stepGoal: Int { health.stepGoal }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 16) {
                    statusRow
                    if let issue { ActionBanner(issue: issue, act: resolve) }
                    todayCard
                    heartCard
                    HStack(spacing: 16) {
                        Tile("Exercise", health.exerciseMin.map(String.init), unit: "min",
                             icon: "flame.fill", tint: .orange)
                        Tile("Watch", health.watchBattery.map(String.init), unit: "%",
                             icon: "watch.analog", tint: .indigo)
                    }
                    if showDev { DevSection(ble: ble, health: health) }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 28)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Dream Fit")
            .toolbar {
                Button {
                    showGoal = true
                } label: {
                    Label("Step goal", systemImage: "target")
                }
            }
            .sheet(isPresented: $showGoal) {
                GoalSheet(goal: $health.stepGoal)
            }
            .alert("Turn Health sync back on", isPresented: $showHealthHelp) {
                Button("Open Health") { openHealth() }
                Button("Not now", role: .cancel) {}
            } message: {
                Text("Settings → Apps → Health → Data Access & Devices → Dream Fit, then turn every toggle on.")
            }
        }
    }

    // MARK: - Link state

    private var statusRow: some View {
        HStack(spacing: 9) {
            Circle()
                .fill(ble.isLinked ? Color.green : Color.orange)
                .frame(width: 8, height: 8)
            Text(ble.isLinked ? "Pixel Watch connected" : "Looking for Pixel Watch")
                .font(.subheadline.weight(.medium))
            Spacer(minLength: 8)
            if let at = health.confirmedAt {
                Text(at, format: .relative(presentation: .named))
                    .font(.caption).foregroundStyle(.secondary)
                    .monospacedDigit()
            } else {
                Text("no data yet").font(.caption).foregroundStyle(.tertiary)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 11)
        .background(Color(.secondarySystemGroupedBackground), in: .capsule)
        .contentShape(.capsule)
        .onTapGesture { taps += 1; if taps >= 5 { showDev.toggle(); taps = 0 } }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(ble.isLinked ? "Pixel Watch connected" : "Looking for Pixel Watch")
    }

    // MARK: - Today

    private var todayCard: some View {
        Card {
            HStack(spacing: 22) {
                ProgressRing(
                    progress: Double(health.todaySteps ?? 0) / Double(stepGoal),
                    tint: health.todaySteps == nil ? .secondary : .green,
                )
                .frame(width: 116, height: 116)
                .overlay {
                    VStack(spacing: 1) {
                        // "—" is unknown, not zero: the watch may not have
                        // pushed a record for today yet.
                        Text(health.todaySteps.map(formatted) ?? "—")
                            .font(.system(.title2, design: .rounded, weight: .bold))
                            .contentTransition(.numericText())
                            .minimumScaleFactor(0.6).lineLimit(1)
                        Text("steps").font(.caption2).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 18)
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Today").font(.headline)
                    Button {
                        showGoal = true
                    } label: {
                        Text("\(formatted(stepGoal)) goal")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    if let steps = health.todaySteps {
                        let left = max(0, stepGoal - steps)
                        Text(left == 0 ? "Goal reached" : "\(formatted(left)) to go")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(left == 0 ? .green : .secondary)
                    } else {
                        Text("Waiting for the watch")
                            .font(.subheadline).foregroundStyle(.tertiary)
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(stepsAccessibility)
    }

    private var stepsAccessibility: String {
        guard let steps = health.todaySteps else { return "Steps, loading" }
        return "\(steps) steps of \(stepGoal) goal"
    }

    // MARK: - Heart

    // The chart is a full-bleed footer rather than a padded child: an area
    // fill inset on three sides and clipped on the fourth just looks broken.
    private var heartCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Label("Heart rate", systemImage: "heart.fill")
                        .font(.headline).foregroundStyle(.red)
                    Spacer()
                    if let rhr = health.restingHR {
                        Text("Resting \(rhr)")
                            .font(.caption.weight(.medium))
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(.quaternary, in: .capsule)
                            .foregroundStyle(.secondary)
                    }
                }
                HStack(alignment: .firstTextBaseline, spacing: 5) {
                    Text(health.currentBPM.map(String.init) ?? "—")
                        .font(.system(size: 46, weight: .bold, design: .rounded))
                        .contentTransition(.numericText())
                        .foregroundStyle(health.currentBPM == nil ? .secondary : .primary)
                    Text("bpm").font(.callout).foregroundStyle(.secondary)
                    Spacer()
                }
            }
            .padding(18)

            if health.bpmHistory.count > 1 {
                HeartChart(values: health.bpmHistory).frame(height: 66)
            } else {
                Text(health.beats.isEmpty ? "Waiting for a reading from the watch"
                                          : "Only one reading so far")
                    .font(.caption).foregroundStyle(.tertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(height: 66, alignment: .top)
                    .padding(.horizontal, 18)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(.rect(cornerRadius: 20))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(health.currentBPM.map { "Heart rate \($0) bpm" } ?? "No current heart rate")
    }

    // MARK: - Problems that need a tap

    private var issue: Issue? { Issue(ble: ble, health: health) }

    private func resolve(_ issue: Issue) {
        switch issue {
        case .bluetoothBlocked:
            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
        case .bluetoothOff:
            break
        case .healthDenied:
            showHealthHelp = true
        case .healthSetup:
            health.resolveHealthPermission()
        case .healthUnavailable:
            break
        }
    }

    // Apple documents no route to another app's Settings page (App-prefs died
    // in iOS 18) and Health has no sub-paths, so this is directions plus
    // transport, not a deep link.
    private func openHealth() {
        guard let home = URL(string: "x-apple-health://") else { return }
        UIApplication.shared.open(home) { opened in
            guard !opened, let settings = URL(string: UIApplication.openSettingsURLString) else { return }
            UIApplication.shared.open(settings)
        }
    }

    private func formatted(_ n: Int) -> String {
        n.formatted(.number.grouping(.automatic))
    }
}
