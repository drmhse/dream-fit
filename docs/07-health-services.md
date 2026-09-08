# 07 — What Health Services actually delivers

Every bug in this area came from assuming a semantic instead of reading one.
The contract below is quoted from the AndroidX source and then checked against
this watch (Pixel Watch 4, kenari, Wear OS 6 / SDK 37), because the API being
capable of something and this device supporting it are different questions.

## Capabilities, as reported by the device

`PassiveMonitoringClient.getCapabilitiesAsync()`, logged at every service start
because the passive set differs by model and a silently absent state reads
exactly like a feature that never fires:

```
states = [USER_ACTIVITY_PASSIVE, USER_ACTIVITY_EXERCISE, USER_ACTIVITY_ASLEEP]
types  = [HeartRate, Daily Steps, Steps, Daily Distance, Distance,
          Daily Calories, Calories, Daily Floors, Floors,
          Daily Elevation Gain, Elevation Gain]
```

No HRV, no SpO2, no skin temperature, no respiratory rate — those live in
Health Connect, which Wear OS does not expose to apps (`03-power.md`).

## The types we consume

| Type | Declared as | Value | Meaning (quoted) |
|---|---|---|---|
| `STEPS_DAILY` | `DeltaDataType<Long, IntervalDataPoint<Long>>` | `Long` | "The total step count over a day, where the previous day ends and a new day begins at 12:00 AM local time" |
| `DISTANCE_DAILY` | `…<Double, IntervalDataPoint<Double>>` | `Double` metres | same daily rule |
| `CALORIES_DAILY` | `…<Double, …>` | `Double` kcal | "The total number of calories over a day (**including both BMR and active calories**)" — supported, deliberately not collected |
| `FLOORS_DAILY` | `…<Double, …>` | `Double` | "The total number floors climbed over a day" |
| `HEART_RATE_BPM` | `DeltaDataType<Double, SampleDataPoint<Double>>` | `Double` bpm | "Current heart rate, in beats per minute" |

Every `*_DAILY` point "will cover the interval from the start of day to now" —
**cumulative, not a delta**, which is why `DayLog` treats them as absolutes
that may only rise, and why summing them multiplies the truth by the point
count.

Three consequences that are not obvious:

- **Doubles are rounded, not truncated.** Floors and distance arrive as
  `Double`; truncating every delivery loses up to a whole unit per day.
- **A daily point is dated by the interval it covers, not by its arrival.** A
  snapshot delivered at 00:01 carries *yesterday's* completed total. Adopting
  it as today's would both inflate today and, because totals may only rise,
  freeze the real count until tomorrow. Each aggregate is stamped with
  `getEndInstant(bootInstant)` and rejected unless it matches the day `DayLog`
  currently holds.
- **Calories are not collected at all.** They include BMR — 698 kcal by nine in
  the morning is a body at rest — and HealthKit's `activeEnergyBurned` means the
  active figure alone. With no honest way to split one into the other there was
  nothing to do with the number, so the type was dropped from the passive
  config rather than carried across the link to be ignored.

## Heart rate arrives in batches, with its own timestamps

Observed delivery on this watch: **65 and 112 samples in one callback**,
spanning three to four minutes and ending a second before delivery, roughly
every 45-60s while the app was foreground and the background permission was
held. Deliveries are also **partial**: one carried
`steps=null dist=null floors=null kcal=747 hr=29`, so each type arrives on its
own schedule and a missing type must mean "no news", never zero. That is what
the `-1` sentinel through the intent is for. This matters twice
over:

- The resting-rate quorum (12 samples inside a ten-minute window) is met
  comfortably. It was an open risk when ambient heart rate replaced the
  always-on PPG, and the risk is closed: `rhr` reads 67.
- Samples carry `timeDurationFromBoot`, so wall time is derived from a boot
  instant (`currentTimeMillis - elapsedRealtime`). A batch of history and a
  live `MeasureClient` reading therefore interleave, which is why the window is
  anchored to the newest time *seen* rather than to arrival order, and why the
  live bpm is published only from a sample under five minutes old. The span of
  each batch is logged: a wrong boot instant puts samples in 1970 or the
  future, and the resting rate would quietly fail its quorum rather than look
  wrong.

## User activity state is not a reading

`setShouldUserActivityInfoBeRequested(true)` yields
`onUserActivityInfoReceived(UserActivityInfo)` with a `UserActivityState` and a
`stateChangeTime` — "the time at which the current state took effect", so it is
routinely in the past, and the current state is delivered on registration.
Requires `ACTIVITY_RECOGNITION`.

**`USER_ACTIVITY_ASLEEP` does not mean the wearer is asleep. It means the watch
is still.** Measured here: off-wrist at 07:34:08 per the off-body sensor, and
Health Services declared `ASLEEP` at 09:12:30 — a watch on a desk. Writing that
to HealthKit would invent a night of sleep every time the watch charged, and
once written it is indistinguishable from real data.

So every sleep session is gated on the watch being worn
(`TYPE_LOW_LATENCY_OFFBODY_DETECT`), sessions under 15 minutes are dropped as
stillness, sessions over 16 hours are dropped as a wake transition we never saw,
and taking the watch off closes an open session at the moment of removal.

Residual risk, stated rather than hidden: the platform's asleep detection lags,
and it can still report `ASLEEP` on a worn watch shortly after waking. The
15-minute minimum absorbs a short lag; a lag longer than that on a worn watch
would let a short spurious session through.

## Permissions — and the failure that is silent by default

| Need | Permission |
|---|---|
| passive `HEART_RATE_BPM` | `BODY_SENSORS` (→ `health.READ_HEART_RATE` when targeting 36+) |
| `*_DAILY` aggregates, user activity state | `ACTIVITY_RECOGNITION` |
| **any of it, once the app is not visible** | `BODY_SENSORS_BACKGROUND` (→ `health.READ_HEALTH_DATA_IN_BACKGROUND` when targeting 36+) |

This watch is Wear OS 7 (`ro.cw_build.wear_sdk.version=7`), Android 17, API 37 —
Wear OS is Android, so these are ordinary Android runtime permissions and the
API levels are the platform's own. The app targets 34, which is why the legacy
`BODY_SENSORS_BACKGROUND` is the one requested at runtime. **When `targetSdk`
moves to 36 or higher the runtime request must switch** to
`health.READ_HEALTH_DATA_IN_BACKGROUND`; the manifest already declares both, but
`MainActivity` asks only for the legacy one.

Also: the runtime dialog cannot grant all-the-time access on API 33+. It offers
the equivalent of "while using the app", and the answer comes back as a plain
denial — observed here, with both background permissions ending up `USER_SET`
and denied after the wearer answered. Only Settings → Apps → Dream Fit →
Permissions → Body sensors → "Allow all the time" grants it, which is why the
watch face carries a tappable warning line rather than a log entry.

Without background access, per the guide: "the app loses the `BODY_SENSORS`
permission, and the `onPermissionLost()` callback is called". Health Services
then "will automatically unregister the client request and stop the relevant
sensors" — so the loss would be **total**: no heart rate, no steps, no sleep.

What was actually observed on this watch, stated separately from the guide
because the two do not fully agree:

- One `onPermissionLost` fired, immediately after a reinstall revoked
  `BODY_SENSORS_BACKGROUND` (an `adb install -r` drops a `pm grant`). That
  reads as a reaction to the revocation, not to a background transition.
- A subsequent 14-minute soak with the permission still absent, screen off,
  produced **no** loss at all — but delivery thinned markedly, from roughly one
  callback every 45s to one in 5.5 minutes.

So the honest position is that the permission is the documented requirement and
worth holding, but on Wear OS 7 / API 37 the observed penalty looks like
throttling rather than an outright unregister. A plausible reason: at API 37 the
platform may enforce the `health.*` permissions, and `health.READ_HEART_RATE` is
granted while `health.READ_HEALTH_DATA_IN_BACKGROUND` is not. Untested.

The test that would settle it: grant "allow all the time" in Settings and
compare callback cadence over an identical window. Until then, treat delivery
cadence as the thing to watch, not just the callback.

Two traps:

- **The two permissions must be requested in separate operations.** "If your app
  requests both body sensor permissions at the same time, the system ignores the
  request and doesn't grant your app either permission."
- **Do not copy the documented `maxSdkVersion="35"` cap onto the legacy
  permissions.** That migration assumes `targetSdk` 36+. This app targets 34, so
  on an SDK-37 device the legacy permission is still the one enforced, and a cap
  by *device* SDK would silently remove it.

`onPermissionLost` is implemented: it logs, and the next time the app becomes
visible the passive registration is rebuilt. Silence is the one failure mode
this API makes easy, so it is the one worth wiring loudly.

## Re-checking any of this

```sh
adb logcat | grep -E "DreamFit: (caps|passive|activity|on body)|permission lost"
adb shell "dumpsys package com.drmhse.dream.fit | grep BODY_SENSORS"
adb shell "dumpsys sensorservice | grep -A4 'off body detect (wake-up): last'"
```
