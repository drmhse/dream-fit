import Charts
import SwiftUI

// Anything that stops the app doing its job has to say so on screen. The
// previous version banner-ed only two of the five Health states, so a mirror
// sitting idle in `unknown` looked exactly like one that was working.
enum Issue {
    case bluetoothBlocked, bluetoothOff, healthDenied, healthSetup, healthUnavailable

    @MainActor
    init?(ble: BridgeCentral, health: HealthStore) {
        if ble.btBlocked { self = .bluetoothBlocked; return }
        if ble.btOff { self = .bluetoothOff; return }
        switch health.auth {
        case .authorized: return nil
        case .denied: self = .healthDenied
        case .setup, .unknown: self = .healthSetup
        case .unavailable: self = .healthUnavailable
        }
    }

    var title: String {
        switch self {
        case .bluetoothBlocked: "Bluetooth is blocked"
        case .bluetoothOff: "Bluetooth is off"
        case .healthDenied: "Health sync is off"
        case .healthSetup: "Finish setting up Health"
        case .healthUnavailable: "Apple Health is unavailable"
        }
    }

    var detail: String {
        switch self {
        case .bluetoothBlocked: "Dream Fit needs Bluetooth to reach your watch."
        case .bluetoothOff: "Turn Bluetooth on to reconnect your watch."
        case .healthDenied: "Steps and heart rate aren't being saved to Apple Health."
        case .healthSetup: "Allow Dream Fit to write steps, distance, floors, heart rate and sleep."
        case .healthUnavailable: "This device has no Health database, so nothing can be saved."
        }
    }

    var action: String? {
        switch self {
        case .bluetoothBlocked: "Open Settings"
        case .bluetoothOff, .healthUnavailable: nil
        case .healthDenied: "Fix"
        case .healthSetup: "Allow"
        }
    }

    var icon: String {
        switch self {
        case .bluetoothBlocked, .bluetoothOff: "antenna.radiowaves.left.and.right.slash"
        case .healthDenied, .healthSetup, .healthUnavailable: "heart.text.square"
        }
    }
}

struct ActionBanner: View {
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

// The goal is the one number here the watch cannot tell us, so it is the one
// thing the screen lets you change.
struct GoalSheet: View {
    @Binding var goal: Int
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Stepper(value: $goal,
                            in: HealthStore.stepGoalRange,
                            step: HealthStore.stepGoalStep) {
                        HStack {
                            Text("Daily steps")
                            Spacer()
                            Text(goal.formatted(.number.grouping(.automatic)))
                                .font(.body.monospacedDigit().weight(.semibold))
                                .contentTransition(.numericText())
                        }
                    }
                } footer: {
                    Text("Your watch draws its bezel from this too, so it changes in both places.")
                }
            }
            .navigationTitle("Step goal")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.height(220)])
    }
}

struct Card<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground))
            .clipShape(.rect(cornerRadius: 20))
    }
}

struct ProgressRing: View {
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

struct HeartChart: View {
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

struct Tile: View {
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

// Five taps on the status capsule. Both the link and the Health mirror write to
// the same log, so a step that never reached Apple Health says so here.
struct DevSection: View {
    @ObservedObject var ble: BridgeCentral
    @ObservedObject var health: HealthStore
    @ObservedObject private var diag = Diagnostics.shared

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                Text("Diagnostics").font(.headline)
                Button("Send test notification") {
                    ble.sendNotify(title: "Hello Watch", body: "from iPhone")
                }
                .buttonStyle(.bordered).buttonBorderShape(.capsule)
                .disabled(!ble.canSend)

                Text("link \(ble.state) · health \(health.auth.rawValue)")
                    .font(.caption2.monospaced()).foregroundStyle(.secondary)

                Button("Compare our steps with Health") { health.auditSteps() }
                    .buttonStyle(.bordered).buttonBorderShape(.capsule)
                Button("Clear today from Health") { health.rewriteToday() }
                    .buttonStyle(.bordered).buttonBorderShape(.capsule)
                if let audit = health.stepAudit {
                    Text(audit).font(.caption2.monospaced()).foregroundStyle(.secondary)
                }

                VStack(alignment: .leading, spacing: 2) {
                    ForEach(diag.rows.suffix(14)) { row in
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

// Last night, as the watch closed it. Asleep/awake only — Health Services does
// not expose Fitbit's staging — so the card claims a duration and a window and
// nothing about sleep quality.
struct SleepCard: View {
    let night: SleepRecord

    private var minutes: Int { max(0, Int(night.end.timeIntervalSince(night.start) / 60)) }

    private static let clock: DateFormatter = {
        let f = DateFormatter()
        f.timeStyle = .short
        f.dateStyle = .none
        return f
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "bed.double.fill").foregroundStyle(.purple)
                Text("Sleep").font(.headline)
                Spacer()
                Text("\(Self.clock.string(from: night.start)) – \(Self.clock.string(from: night.end))")
                    .font(.caption).foregroundStyle(.secondary)
            }
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text("\(minutes / 60)").font(.system(size: 34, weight: .semibold, design: .rounded))
                Text("h").font(.callout).foregroundStyle(.secondary)
                Text("\(minutes % 60)").font(.system(size: 34, weight: .semibold, design: .rounded))
                Text("min").font(.callout).foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Slept \(minutes / 60) hours \(minutes % 60) minutes")
    }
}

// Named refusals. Each write is gated on its own type, so the app keeps
// mirroring everything else, and the only thing missing is the reader's way of
// knowing why one figure never arrives.
struct UnsharedNote: View {
    let names: [String]
    let act: () -> Void

    var body: some View {
        Button(action: act) {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Not shared with Health").font(.subheadline.weight(.semibold))
                    Text(names.joined(separator: ", ")).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
            }
            .padding(14)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Not shared with Health: \(names.joined(separator: ", "))")
    }
}
