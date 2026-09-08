# 04 — Parity with Pixel+Watch (where we stand)

Goal: Pixel Watch 4 on iPhone feeling like Pixel 9 + Watch. Honest ledger.

## Done
- BLE link (custom GATT 6E400001, encrypted + MITM-gated, LOW_POWER, adv-off-while-subscribed, backoff reconnect on iPhone + scheduled re-burst on watch)
- Foreground-persistent watch service (immune to app-idle kill)
- In-app notify → visible watch notification; full ANCS client beyond that (see below)
- Live HR event-driven (5s throttle) for the tile; ambient HR filed as one
  median reading per minute, carried at the time it was measured
- Watch is sole authority for daily totals, and the only lane that writes to
  HealthKit is the delta belt: movement and beats, each over its own span. A
  write that cannot run right now is kept and retried rather than dropped, and
  the belt holds a day's backlog across a disconnection (see `01-protocol.md`)
- Flow-controlled notify/write queues and bounded reassembly buffers on both ends
- iPhone UI: step-goal ring, live HR chart, actionable permission banners,
  light/dark verified in the simulator
- Watch UI: Compose for Wear OS — step goal on the bezel, HR readout, link
  state; service publishes `WatchState` in-process, so no prefs or broadcast
  hop between a reading and the screen (prefs hold the durable day record)
- Derived metrics on-watch: resting HR = the day's lowest 10-minute-window
  minimum, over twelve samples or not at all; battery % rides every `day` push.
  Exercise minutes are not reported: `USER_ACTIVITY_EXERCISE` never fired for a
  measured 30-minute walk, so the figure was zero for a real walk
- Sleep sessions from `USER_ACTIVITY_ASLEEP`, and daily distance and floors
  from Health Services passive monitoring — shown on both screens and mirrored
  to Apple Health. Calories are not collected at all (they include BMR and
  cannot be honestly mirrored; see `01-protocol.md`). No sensor is powered for
  any of it
- The PPG is never held open: doing so cost ~7.8mA and roughly halved battery
  life (`03-power.md`). The only raw sensor left is off-body detection, which
  Health Services does not report and which sleep depends on
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
- iOS HealthKit sink (`HealthKitSink.swift` + `HealthStore.swift`): HR, resting
  HR + steps to Apple Health, auth + live ❤/steps in UI. The code is written and
  committed — no signing team lives in the repo, so open `ios/DreamFit.xcodeproj`
  → DreamFit target → Signing & Capabilities → pick your Apple Developer team,
  then build to device.

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
