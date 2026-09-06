# 04 — Parity with Pixel+Watch (where we stand)

Goal: Pixel Watch 4 on iPhone feeling like Pixel 9 + Watch. Honest ledger.

## Done
- BLE link (custom GATT 6E400001, LOW_POWER, adv-off-while-subscribed, backoff reconnect on iPhone + scheduled re-burst on watch)
- Foreground-persistent watch service (immune to app-idle kill)
- In-app notify → visible watch notification; full ANCS client beyond that (see below)
- HR (5s throttle) event-driven; daily aggregates pushed as absolutes
- Watch is sole authority for daily totals; HealthKit reconciles to it in both
  directions (see `01-protocol.md`)
- Flow-controlled notify/write queues and bounded reassembly buffers on both ends
- iPhone UI: step-goal ring, live HR chart, actionable permission banners,
  light/dark verified in the simulator
- Watch UI: Compose for Wear OS — step goal on the bezel, HR readout, link
  state; service publishes `WatchState` in-process, so no prefs or broadcast
  hop between a reading and the screen (prefs hold the durable day record)
- Derived metrics on-watch: resting HR = the day's lowest 10-minute-window
  minimum; exercise minutes = one per minute above 110 bpm; battery % rides
  every `day` push
- System notifications, implemented, awaiting pairing: `AncsClient.kt` is a
  complete client, not a stub — bonded-iPhone lookup, serialised subscribe to
  Notification + Data Source, per-event attribute fetch (app/title/subtitle/
  message), first-sighting dedup (iOS replays the tray on subscribe),
  removal handling that ends call notifications, and answer/decline via
  `PerformNotificationAction`. Tray shows per-app identity for Messages,
  Phone, Mail, WhatsApp, Messenger, Telegram and Gmail, grouped with
  summaries and per-app colours
- Runtime permission flow (`MainActivity.kt`): body sensors, activity
  recognition, notifications, BT connect + advertise — tracked per
  permission, with a Settings deep link on permanent denial

## Ready, one Xcode step away
- iOS HealthKit sink (`HealthStore.swift`): HR, resting HR + steps to Apple Health, auth + live ❤/steps in UI. The code is written and committed — no signing team lives in the repo, so open `ios/DreamFit.xcodeproj` → DreamFit target → Signing & Capabilities → pick your Apple Developer team, then build to device.

## Next (watch code ships, needs iPhone Settings > Bluetooth pair)
- Flip the switch: pair the watch as a BT accessory and the ANCS client above
  comes alive — all iPhone notifications on wrist, incoming calls with
  answer/decline. No iOS app change needed.
- Known ANCS limits (from the code comments, not guesses): answer/decline
  covers calls only, there is no message reply; ANCS carries no icons or
  image attachments, so the tray shows the source app's name with a matching
  glyph; photo bodies ("My O2 / Photo") are MMS references iOS won't hand out.
- Calls: HFP audio routing to non-MFi is blocked by Apple; best achievable =
  caller ID + answer/decline via ANCS actions (wired end to end already).
- Media: AMS client, same pairing story (play/pause/track).

## Impossible (secure silos, not effort)
- eSIM provisioning, Google Wallet payments, Play Store installs from phone, Fitbit-cloud sync, Assistant phone handoff, Camera/Recorder sync. These need GMS + carrier/secure-element access Apple and Google both gate.

## TODO after the tap
Google sign-in in Dream Fit → Calendar/Gmail/Weather → watch; sleep/workout sync; DND/alarm mirror.
