import Charts
import SwiftUI

struct ContentView: View {
    @EnvironmentObject var ble: BridgeCentral
    @EnvironmentObject var health: HealthStore
    @Environment(\.openURL) private var openURL
    @AppStorage("showDev") private var showDev = false
    @State private var taps = 0
    @State private var showHealthHelp = false

    private let stepGoal = 10_000

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
            .alert("Turn Health sync back on", isPresented: $showHealthHelp) {
                Button("Open Health") { health.openHealthApp() }
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
                .fill(ble.isReady ? Color.green : Color.orange)
                .frame(width: 8, height: 8)
            Text(ble.isReady ? "Pixel Watch connected" : "Looking for Pixel Watch")
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
        .accessibilityLabel(ble.isReady ? "Pixel Watch connected" : "Looking for Pixel Watch")
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
                        // "…" is unknown, not zero: the query may still be in flight.
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
                    Text("\(formatted(stepGoal)) goal")
                        .font(.subheadline).foregroundStyle(.secondary)
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

    private var issue: Issue? {
        if ble.btBlocked { return .bluetoothBlocked }
        if ble.btOff { return .bluetoothOff }
        if health.healthDenied { return .healthDenied }
        if health.authState == "setup" { return .healthSetup }
        return nil
    }

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
        }
    }

    private func formatted(_ n: Int) -> String {
        n.formatted(.number.grouping(.automatic))
    }
}

// MARK: - Pieces

enum Issue {
    case bluetoothBlocked, bluetoothOff, healthDenied, healthSetup

    var title: String {
        switch self {
        case .bluetoothBlocked: "Bluetooth is blocked"
        case .bluetoothOff: "Bluetooth is off"
        case .healthDenied: "Health sync is off"
        case .healthSetup: "Finish setting up Health"
        }
    }

    var detail: String {
        switch self {
        case .bluetoothBlocked: "Dream Fit needs Bluetooth to reach your watch."
        case .bluetoothOff: "Turn Bluetooth on to reconnect your watch."
        case .healthDenied: "Steps and heart rate aren't being saved to Apple Health."
        case .healthSetup: "Allow Dream Fit to write steps and heart rate."
        }
    }

    var action: String? {
        switch self {
        case .bluetoothBlocked: "Open Settings"
        case .bluetoothOff: nil
        case .healthDenied: "Fix"
        case .healthSetup: "Allow"
        }
    }

    var icon: String {
        switch self {
        case .bluetoothBlocked, .bluetoothOff: "antenna.radiowaves.left.and.right.slash"
        case .healthDenied, .healthSetup: "heart.text.square"
        }
    }
}

private struct ActionBanner: View {
    let issue: Issue
    let act: (Issue) -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: issue.icon)
                .font(.title3).foregroundStyle(.orange)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(issue.title).font(.subheadline.weight(.semibold))
                Text(issue.detail).font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 4)
            if let label = issue.action {
                Button(label) { act(issue) }
                    .font(.footnote.weight(.semibold))
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .tint(.orange)
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(.orange.opacity(0.35)))
    }
}

private struct Card<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground))
            .clipShape(.rect(cornerRadius: 20))
    }
}

private struct ProgressRing: View {
    let progress: Double
    let tint: Color

    var body: some View {
        let clamped = min(max(progress, 0), 1)
        ZStack {
            Circle().stroke(tint.opacity(0.16), lineWidth: 13)
            Circle()
                .trim(from: 0, to: clamped)
                .stroke(
                    LinearGradient(colors: [tint, tint.opacity(0.75)],
                                   startPoint: .topTrailing, endPoint: .bottomLeading),
                    style: StrokeStyle(lineWidth: 13, lineCap: .round),
                )
                .rotationEffect(.degrees(-90))
                .animation(.smooth(duration: 0.5), value: clamped)
        }
    }
}

private struct HeartChart: View {
    let values: [Int]

    var body: some View {
        // Pad a flat series so the line sits mid-band instead of on an edge.
        let lo = (values.min() ?? 0) - 4
        let hi = (values.max() ?? 0) + 4
        Chart(Array(values.enumerated()), id: \.offset) { index, bpm in
            AreaMark(x: .value("Sample", index), y: .value("BPM", bpm))
                .interpolationMethod(.catmullRom)
                .foregroundStyle(
                    .linearGradient(
                        colors: [.red.opacity(0.28), .red.opacity(0.02)],
                        startPoint: .top, endPoint: .bottom,
                    ),
                )
            LineMark(x: .value("Sample", index), y: .value("BPM", bpm))
                .interpolationMethod(.catmullRom)
                .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .foregroundStyle(.red)
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartYScale(domain: lo ... hi)
        .accessibilityHidden(true)
    }
}

private struct Tile: View {
    let title: String
    let value: String?
    let unit: String
    let icon: String
    let tint: Color

    init(_ title: String, _ value: String?, unit: String, icon: String, tint: Color) {
        self.title = title
        self.value = value
        self.unit = unit
        self.icon = icon
        self.tint = tint
    }

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 8) {
                Label(title, systemImage: icon)
                    .font(.caption.weight(.medium)).foregroundStyle(tint)
                HStack(alignment: .firstTextBaseline, spacing: 3) {
                    Text(value ?? "—")
                        .font(.system(.title2, design: .rounded, weight: .bold))
                        .contentTransition(.numericText())
                    if value != nil {
                        Text(unit).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(value.map { "\(title) \($0) \(unit)" } ?? "\(title) unavailable")
    }
}

private struct DevSection: View {
    @ObservedObject var ble: BridgeCentral
    @ObservedObject var health: HealthStore

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                Text("Diagnostics").font(.headline)
                Button("Send test notification") {
                    ble.sendNotify(title: "Hello Watch", body: "from iPhone")
                }
                .buttonStyle(.bordered).buttonBorderShape(.capsule)
                .disabled(!ble.isReady)

                Text("link \(ble.state) · health \(health.authState)")
                    .font(.caption2.monospaced()).foregroundStyle(.secondary)

                VStack(alignment: .leading, spacing: 2) {
                    ForEach(ble.log.suffix(14)) { row in
                        Text(row.text)
                            .font(.caption2.monospaced())
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
    }
}
