# 04 — Parity with Pixel+Watch (where we stand)

Goal: Pixel Watch 4 on iPhone feeling like Pixel 9 + Watch. Honest ledger.

## Done
- BLE link (custom GATT 6E400001, LOW_POWER, adv-off-when-subscribed, backoff reconnect both ends)
- Foreground-persistent watch service (immune to app-idle kill)
- In-app notify → visible watch notification
- HR (5s throttle) event-driven; daily aggregates pushed as absolutes
- Watch is sole authority for daily totals; HealthKit reconciles to it in both
  directions (see `01-protocol.md`)
- Flow-controlled notify/write queues and bounded reassembly buffers on both ends
- iPhone UI: step-goal ring, live HR chart, actionable permission banners,
  light/dark verified in the simulator
- Watch UI: Compose for Wear OS — step goal on the bezel, HR readout, link
  state; service publishes `WatchState` in-process, so no prefs or broadcast
  hop between a reading and the screen

## Ready, one Xcode tap away (code written, profile wiped by regen)
- iOS HealthKit sink (`HealthStore.swift`): HR, resting HR + steps to Apple Health, auth + live ❤/steps in UI
- Why blocked: xcodegen regen needs Team re-pick to mint the HealthKit profile. Open `dream-fit/ios/DreamFit.xcodeproj` → DreamFit target → Signing & Capabilities → pick your Apple Developer team to mint the HealthKit profile, then build to device.

## Next (watch code ships, needs iPhone Settings > Bluetooth pair)
- System notifications via ANCS (`AncsClient.kt` skeleton in): iPhone exposes Apple Notification Center Service to bonded accessories. Pair the watch as a BT accessory, our app subscribes to Notification Source/Data Source → all iPhone notifications on wrist, with reply/decline actions. No iOS app change needed.
- Calls: HFP audio routing to non-MFi is blocked by Apple; best achievable = caller ID + answer/decline via ANCS actions.
- Media: AMS client, same pairing story (play/pause/track).

## Impossible (secure silos, not effort)
- eSIM provisioning, Google Wallet payments, Play Store installs from phone, Fitbit-cloud sync, Assistant phone handoff, Camera/Recorder sync. These need GMS + carrier/secure-element access Apple and Google both gate.

## TODO after the tap
Google sign-in in Dream Fit → Calendar/Gmail/Weather → watch; sleep/workout sync; DND/alarm mirror.
