# 03 — Power: why the bridge doesn't move the needle

Measured 2026-09-05 and 2026-09-07 on a Pixel Watch 4 (kenari, model `G8AK3`).

The cell is 455mAh by design and 478mAh as learned, read from the device rather
than from a spec sheet:

```
/sys/class/power_supply/battery/charge_full_design   455000
/sys/class/power_supply/battery/charge_full          478000
dumpsys batterystats: Estimated 479mAh, learned 478mAh
```

Earlier revisions of this document said 325mAh and "41mm class", which was the
41mm figure applied to a 45mm watch. Every percentage below is against 455mAh.

## What we changed (v0 → v1)
- v0 (lab only, never shipped): `LOW_LATENCY` (100ms interval) + `TX MEDIUM` (~0dBm), always-on → ~5–10mA while advertising. Would have been visible on battery.
- v1 (current): `LOW_POWER` (~1000ms interval) + `TX LOW` (~-12dBm, room-scale), 30s burst / 5min rest (~9% duty), **adv off while subscribed**, GATT notify-only (no polling).

## Math
- BLE adv v1 during burst: ~0.3–0.8mA avg (Nordic/Dialog figures for 1s interval, 0dBm→-12dBm saves ~2×).
- Duty-averaged: 0.05–0.08mA → 0.4–0.6mAh/day → **~0.1% of a 455mAh charge**.
- GATT server idle: ~0. Connected link with 1 notify/min: <0.2mA avg.
- Existing load for context: BT Classic Wear Data Layer 10–20mA bursty, HR sensor 5–10mA while sampling, screen 80–150mA, WiFi 30–60mA. Display-on for 5 min costs more than our bridge for a week.

Verified live: `DreamFit: adv on` in logcat, Mac BLE scan sees the watch with
`6e400001` at rssi -65 during a burst, and the service holds no wakelock
(`dumpsys batterystats` shows nothing for the bridge).

## The regression this replaced (2026-09-07)

v1 registered `TYPE_HEART_RATE` at service start and never unregistered it, so
the PPG — green LEDs plus the analog front end — ran for the life of the
service. Measured on kenari over one discharge:

```
Sensor 65567 (HeartRate):     21h 21m 33s realtime  of 21h 23m 55s on battery
HeartRate (handle=0x0001001f, connections=1)   <- us, sole client
Discharge: 417mAh / 21h24m = ~19.5mA avg (~23h on a 455mAh cell)
Deep doze discharge: 359mAh, against 5m 45s of app CPU
```

Idle floor with the service running vs force-stopped, `current_now` sampled
every 5s for 90s each, screen off:

```
RUNNING   floor 10.19mA   never once below 10.2 across 18 samples
STOPPED   floor  2.38mA   seven samples under 4.0mA
```

~7.8mA the app could not let the watch drop below — the signature of a
continuously held sensor rail, which no amount of dozing can dodge. Removing it
takes the average from ~19.5mA to ~11.7mA: **~24h of life to ~41h.**

Two things hid it. `batterystats` blames only 18.5mAh to our uid, because this
device has no per-uid coefficient for the PPG and sweeps it into uid 1000
(339mAh of a 411mAh total) — the *attribution* is useless, though the
`Sensor 65567:` duration line it prints is exactly what proves the case. And
the doc's own verification recipe grepped for a wakelock we never held.

## Guardrails in code

- **Health Services owns every ambient reading.** `PassiveListenerConfig` takes
  `STEPS_DAILY`, `DISTANCE_DAILY`, `CALORIES_DAILY`, `FLOORS_DAILY` and
  `HEART_RATE_BPM`, plus user activity state; all of it rides the sampling the
  platform already does for itself, so the app powers no sensor to watch it,
  and there is no second source for a number Health Services already reports.
- **One raw sensor remains**, and it is the exception that proves the rule:
  `TYPE_LOW_LATENCY_OFFBODY_DETECT`, needed because Health Services does not
  report whether the watch is worn — and it must be known, since a watch on a
  desk reports `USER_ACTIVITY_ASLEEP` (`07-health-services.md`). It is
  on-change rather than continuous, non-optical, and the system already holds
  several clients on it, so ours is shared and costs nothing. That is the
  opposite of the PPG, which we were the sole client of.
- **`MeasureClient` owns live heart rate**, and only while somebody is looking:
  registered when the phone subscribes, when the phone writes, or when the
  watch screen comes up, and released on a 120s deadline. Registered is the
  only state that costs anything, so it is the state we bound.
- Advertising stops when **our app subscribes to TX**, and re-bursts when the
  last subscriber drops. Never gated on raw connection state: after iPhone
  pairing the system holds an ANCS/HFP link permanently, and gating on that
  would suppress discovery of our service forever.
- No `SCAN_MODE_*`, no background jobs, no polling. Aggregates are pushed on
  change plus a 15-minute heartbeat.
- If RSSI proves marginal at -65dBm room-scale, bump to `TX MEDIUM` before
  shortening interval — power cost of +3dB TX is cheaper than 10x interval.

To re-verify any time — the sensor check first, because it is the one that
matters and the one the old recipe missed:

```sh
# Nobody should hold this outside a live window.
adb shell "dumpsys sensorservice | grep -A2 'HeartRate (handle'"
# Sensor duration per uid. Look for the Sensor NNNNN: lines, not the package name.
adb shell "dumpsys batterystats --charged | grep -A12 '^  u0a<uid>:'"
# Idle floor, screen off and off charger. Should settle near 2-3mA.
adb shell 'for i in $(seq 1 18); do cat /sys/class/power_supply/battery/current_now; sleep 5; done'
```

and watch for the `6e400001` service in nRF Connect during a burst.

## Health Connect is not reachable from the watch (checked 2026-09-07)

Worth recording, because the watch looks like it should work and does not.
`dumpsys service list` shows `healthconnect:
[android.health.connect.aidl.IHealthConnectService]`, and
`com.google.android.healthconnect.controller` is installed — but that is the
platform image, not an app-facing API:

```
sdkStatus=1  sdkInt=37  isProfile=false
getSystemService("healthconnect") = null
system features matching /health/ = [com.google.clockwork.hardware.health_services]
```

Wear OS never registers the client-side `HealthConnectManager`, advertises no
`android.software.health_connect` feature, and does not ship
`com.google.android.apps.healthdata`. So sleep, resting heart rate, HRV, SpO2,
skin temperature and respiratory rate — all of which Fitbit computes on this
watch and all of which have a HealthKit type waiting on the iPhone — cannot be
read by any app on the watch, ours included.

Sleep in particular is not something we can recompute: it is a nightly
inference over heart rate, HRV, motion and temperature, and approximating it
would mean holding sensors open all night, which is the exact mistake the rest
of this document exists to prevent. If sleep is wanted, the route is the Fitbit
Web API from the iPhone app, entirely off-watch.

To re-check after a platform update, one line:

```sh
adb shell "pm list features | grep -i health"   # want android.software.health_connect
```
