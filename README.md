# Dream Fit — Pixel Watch ↔ iPhone bridge

Use a Pixel Watch 4 from an iPhone. iOS exposes no BT Classic RFCOMM to
non-MFi accessories and no Google Mobile Services, so a true pairing clone is
impossible — this is a practical **side-channel bridge** over Bluetooth LE
instead: the watch advertises a tiny GATT service, the iPhone app acts as
central, and daily health data flows as absolute, idempotent messages.

## How it works

- **Transport:** Nordic-UART-shaped GATT service `6E400001-…` — RX (`…0002`,
  phone → watch) and TX (`…0003`, watch → phone), newline-delimited UTF-8
  JSON, flow-controlled in both directions. Full contract: `docs/01-protocol.md`
  (canonical definition: `wear/…/BridgeGatt.kt`).
- **Data model:** the **watch owns every daily aggregate**. `day` messages are
  absolute (`steps`, resting HR, exercise minutes, battery + the watch's own
  local date), sent on subscribe, on change (debounced) and on a 15-minute
  heartbeat. The phone treats HealthKit as a mirror to reconcile against that
  total — never as an accumulator — so repeats, reconnects and out-of-order
  delivery are self-healing. `hr` is live telemetry (5 s throttle), never summed.
- **Phone → watch:** app notifications (`notify`) and an explicit re-sync
  request (`sync`). An `AncsClient` skeleton is in the Wear app for system-wide
  iPhone notifications over ANCS once the watch is paired as a BT accessory.
- **Power:** `LOW_POWER` advertising (~1 s interval, room-scale TX), 30 s burst /
  5 min rest, advertising off while subscribed, everything event-driven —
  ≈0.06 mA average, ~0.15 % of a charge per day. See `docs/03-power.md`.
- **UI:** SwiftUI on iPhone (step-goal ring, live HR chart, permission banners),
  Compose for Wear OS on the watch (step goal, HR, link state).

## Layout

```
ios/     iPhone app (SwiftUI) — BridgeCentral (BLE), HealthStore (HealthKit sink),
         ContentView, xcodegen project.yml
wear/    Wear OS app (Kotlin) — BridgeService (foreground GATT server + sensors),
         BridgeGatt (wire contract), DreamFitScreen, AncsClient, PassiveDataService
brand/   One heart-with-pulse mark (generate-icons.py) → all platform assets
docs/    00 watch setup · 01 protocol · 02 baseline · 03 power · 04 parity status
capture/ Redacted reference dumps from the original bring-up
```

## Getting started

Prereqs: Xcode 16+, `xcodegen`, Android Studio + platform-tools, a Pixel Watch
with developer options (see `docs/00-watch-setup.md`).

**iPhone app:**
```
cd ios
xcodegen generate
open DreamFit.xcodeproj   # Signing & Capabilities → pick your team (mints the HealthKit profile)
```
Build to device (Bluetooth + HealthKit entitlements require a real device).
Verify the link first with **nRF Connect**: look for the `6E400001` service
during an advertising burst.

**Wear app:**
```
adb connect <watch-ip>:5555
cd wear && ./gradlew :app:installRelease   # or installDebug
```
The foreground service starts advertising in low-power bursts; the iPhone app
pages the known peripheral directly once seen, so later reconnects need no
advertising.

## Status

Working: BLE link (backoff reconnect on iPhone, scheduled re-burst on watch), foreground-persistent watch service,
in-app notify → watch notification, HR + absolute daily aggregates, HealthKit
mirror in both directions, light/dark iPhone UI. Next: ANCS system
notifications / call actions / media control after Bluetooth pairing; Google
sign-in for Calendar/Gmail/Weather. Honest ledger, including what is
impossible (eSIM, Wallet, Play installs, Fitbit-cloud sync): `docs/04-parity.md`.

## Privacy

No personal data lives in this repo: `capture/` dumps and `docs/` are scrubbed
of MACs, serials, node IDs and LAN addresses (see `capture/README.md`), and no
Apple signing team is checked in — pick your own in Xcode after regen.
