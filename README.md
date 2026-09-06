# Dream Fit — Pixel Watch 4 ↔ iPhone bridge
Practical side-channel bridge (BT Classic clone is impossible from iOS).
Watch advertises `6E400001-...`, iPhone scans + writes notify JSON, watch pushes HR and
an absolute daily-aggregate message. The watch owns all daily totals; HealthKit
is reconciled to them. Wire format and sync rules: `docs/01-protocol.md`.
Power: LOW_POWER adv, 30s/5min bursts, adv-off-when-subscribed (~0.06mA avg).
UI: SwiftUI on iPhone, Compose for Wear OS on the watch. Mark and icons: `brand/`.
