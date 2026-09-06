# Dream Fit — use a Pixel Watch with an iPhone

A Wear OS to iOS bridge for the Pixel Watch 4: use your watch from an iPhone. iOS exposes no BT Classic RFCOMM to
non-MFi accessories and no Google Mobile Services, so a true pairing clone is
impossible — this is a practical **side-channel bridge** over Bluetooth LE
instead: the watch advertises a tiny GATT service, the iPhone app acts as
central, and daily health data flows as absolute, idempotent messages.

## How it works

- **Transport:** Nordic-UART-shaped GATT service `6E400001-…` — RX (`…0002`,
  phone → watch) and TX (`…0003`, watch → phone), newline-delimited UTF-8
  JSON, flow-controlled in both directions, and gated on an encrypted,
  MITM-protected link. Full contract: `docs/01-protocol.md`
  (canonical definition: `wear/…/BridgeGatt.kt`).
- **Data model:** the **watch owns every daily aggregate**. `day` messages are
  absolute (`steps`, resting HR, exercise minutes, battery + the watch's own
  local date), sent on subscribe, on change (debounced) and on a 15-minute
  heartbeat. The phone treats HealthKit as a mirror to reconcile against that
  total — never as an accumulator — so repeats, reconnects and out-of-order
  delivery are self-healing. `hr` is live telemetry (5 s throttle), never summed.
- **Phone → watch:** app notifications (`notify`), the user's daily step goal
  (`goal`, which drives the watch bezel as well as the phone ring) and an
  explicit re-sync request (`sync`). An `AncsClient` skeleton is in the Wear app for system-wide
  iPhone notifications over ANCS once the watch is paired as a BT accessory.
- **Power:** `LOW_POWER` advertising (~1 s interval, room-scale TX), 30 s burst /
  5 min rest, advertising off while subscribed, everything event-driven —
  ≈0.06 mA average, ~0.15 % of a charge per day. See `docs/03-power.md`.
- **UI:** SwiftUI on iPhone (settable step-goal ring, live HR chart, permission banners),
  Compose for Wear OS on the watch (step goal, HR, link state).

## Layout

```
ios/     iPhone app (SwiftUI) — BridgeCentral (BLE link state machine), WatchEvent (wire
         shapes + day boundaries), HealthKitSink (async HealthKit), HealthStore
         (model + step mirror), ContentView/Components, Diagnostics,
         xcodegen project.yml
wear/    Wear OS app (Kotlin) — BridgeService (foreground GATT server + sensors,
         link phases),
         DayLog (the day's aggregates and their rules), Tray (notifications),
         Advertiser (radio duty cycle), BridgeGatt (wire contract),
         DreamFitScreen, AncsClient, PassiveDataService
brand/   One heart-with-pulse mark (generate-icons.py) → all platform assets
docs/    00 watch setup · 01 protocol · 02 baseline · 03 power · 04 parity
         status · 05 troubleshooting · 06 lifecycles
capture/ Redacted reference dumps from the original bring-up
```

## Getting started

Prereqs: Xcode 16+, `xcodegen`, Android Studio + platform-tools, a Pixel Watch
with developer options (see `docs/00-watch-setup.md`).

**iPhone app:**
```
cd ios
cp Signing.xcconfig.example Signing.xcconfig   # then add your team ID
xcodegen generate                              # the .xcodeproj is not tracked
open DreamFit.xcodeproj
```
Copy `Signing.xcconfig.example` to `Signing.xcconfig` and put your team ID in it
before generating. Build to device — Bluetooth and HealthKit entitlements both
require real hardware.

**nRF Connect** will find `6E400001` in an advertising burst but will be refused
when it tries to read: the characteristics require an encrypted, MITM-protected
link, so only the bonded phone gets in. That refusal is the check passing.

**Wear app:** needs JDK 17+ (Android Studio's bundled JBR works:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`).

Wireless debugging on the watch hands out a one-shot pairing port and a
separate connect port — Settings → Developer options → Wireless debugging:
```
adb pair <watch-ip>:<pairing-port> <code>
adb connect <watch-ip>:<connect-port>
cd wear && ./gradlew :app:installDebug   # or installRelease
```
The foreground service starts advertising in low-power bursts; the iPhone app
pages the known peripheral directly once seen, so later reconnects need no
advertising.

## Status

Working: BLE link (backoff reconnect on iPhone, scheduled re-burst on watch), foreground-persistent watch service,
in-app notify → watch notification, full ANCS client + call answer/decline (activates on BT pairing), HR + absolute daily aggregates, HealthKit
mirror in both directions (queued and retried when the phone is locked, denied
or relaunched in the background), light/dark iPhone UI. Next: ANCS system
notifications / call actions / media control after Bluetooth pairing; Google
sign-in for Calendar/Gmail/Weather. Honest ledger, including what is
impossible (eSIM, Wallet, Play installs, Fitbit-cloud sync): `docs/04-parity.md`.
If a number looks wrong, `docs/05-troubleshooting.md` names the three different
step counts and how to tell which one is lying.

## Privacy

Your health data never leaves your devices: watch → your iPhone over BLE →
Apple Health on that same iPhone. No accounts, no cloud sync, no analytics.
The HealthKit mirror is scoped to Dream Fit's own samples, so it neither
reads your iPhone's pocket pedometer nor touches other apps' data, and the
watch keeps only the current day's aggregates in local storage.

The link is encrypted. Every characteristic requires an encrypted,
MITM-protected connection, so the watch refuses to be read, written or
subscribed to until the link is secured with the keys from the phone's own
pairing — the same bond ANCS uses. Nothing in radio range can connect and read
your heart rate or step count, and nothing crosses the air in the clear. The
trade is that the bridge needs that pairing: unpair the watch and the service
goes dark rather than degrading to plaintext. Details and the verification:
`docs/01-protocol.md`.

This is transport security for a personal bridge, not a claim of
medical-device certification.

Repo hygiene, separately: `capture/` dumps and `docs/` are scrubbed of MACs,
serials, node IDs and LAN addresses (see `capture/README.md`), and no Apple
signing team is checked in — pick your own in Xcode after regen.
