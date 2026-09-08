# 01 — Bridge protocol

We own both ends, so the service is a tiny Nordic-UART-shaped GATT service.
Canonical definition lives in `wear/.../BridgeGatt.kt`.

Every characteristic requires an **encrypted, MITM-protected link**
(`PERMISSION_*_ENCRYPTED_MITM` on the RX characteristic and on the CCCD). The
watch stack refuses the write, the read and the subscribe until the link is
encrypted with the bond keys, so no heart rate or step count crosses the air in
the clear and nothing in radio range can connect and read them.

This costs nothing at runtime because the bond already exists — it is the same
pairing ANCS needs to put iPhone notifications on the wrist. On the watch:

```
le_authenticated:T  le_encrypted:T  le_enc_key_size:16
ble_pairing_algorithm: PairingAlgorithm::SC   (LE Secure Connections)
```

`le_authenticated:T` is the part that matters: the bond was made with numeric
comparison, so it satisfies the MITM requirement and not merely the encryption
one. iOS needs no code for this — CoreBluetooth answers an insufficient-
authentication error by elevating with the existing keys and retrying.

The consequence is deliberate: **the bridge requires the pairing.** Unpair the
watch and the service becomes unreachable rather than falling back to
plaintext. Advertising stays unencrypted, because discovery cannot work
otherwise, but an advertisement carries only the service UUID and the device
name — never health data.

Service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`

| Char | UUID suffix | Props | Direction |
|---|---|---|---|
| RX | `6E400002-…` | Write, WriteWithoutResponse | phone → watch |
| TX | `6E400003-…` | Notify | watch → phone |

Framing: newline-delimited UTF-8 JSON, chunked to `MTU - 3`. Both ends
reassemble on newline and reset the buffer past 4 KB, so one dropped fragment
costs one message instead of wedging the link for the session.

Flow control is mandatory in both directions — this is what the first version
got wrong. The watch sends one notification at a time and waits for
`onNotificationSent`; the phone drains its write queue only while
`canSendWriteWithoutResponse` is true and resumes from
`peripheralIsReady(toSendWriteWithoutResponse:)`.

## Messages

watch → phone
```
{"t":"hr","bpm":72}
{"t":"day","d":"2026-09-05","steps":8412,"rhr":54,"dist":6240,"floors":7,"bat":73}
{"t":"delta","k":"floors","v":2.0,"s":1757246580000,"e":1757246760000}
{"t":"sleep","s":1757210400000,"e":1757237100000}
```

phone → watch
```
{"t":"notify","title":"…","body":"…"}
{"t":"goal","steps":12000}
```

`goal` is the user's daily step target. It lives on the phone, and the watch
keeps the last value it was told so its bezel is right after a reboot and
before the phone reconnects. Pushed on every change and again whenever the
link comes back.

Any inbound write doubles as a sync request: the watch answers every
message with a fresh `day` push, so the phone re-asks with a bare
`{"t":"sync"}` after reconnects and restores.

## Deltas carry the span, absolutes carry the count

`day` says how much the watch has counted; it does not say when any of it
happened, because `STEPS_DAILY` and its siblings cover "start of day to now".
HealthKit stores samples over intervals and merges across sources by interval,
so a day's total written as one instant is not comparable with the iPhone's own
continuous reading of the same walk. Measured on 2026-09-07: two floors counted
by both devices, written by us as an instant three minutes from the phone's own,
came out of Health as four.

So the interval variants ride alongside the absolutes. `STEPS`, `DISTANCE` and
`FLOORS` are each "since the last update", delivered as `IntervalDataPoint`s
carrying the start and end of the movement, and each becomes one `delta`
message and then one HealthKit sample over exactly that span. `k` is `steps`,
`dist` or `floors`, `v` is the value in the same units as the matching `day`
field, and `s` and `e` are epoch milliseconds.

The two are not rivals. The deltas place the samples; the absolute audits the
total, and corrects only once the delta stream for that type has been quiet for
three minutes, because in flight the two views disagree by construction: a
shortfall written while a delta is still arriving is double counted, and a
rewrite discards the spans the deltas placed.

### A delta is the one message that cannot be re-sent from scratch

Every other message is either telemetry or an absolute, so losing one costs
freshness. A delta is neither: Health Services publishes it once and keeps no
history — its whole client is seven methods, none of which reads the past — so
the watch is the only place it exists.

It is therefore written to storage the moment it arrives, and drained only to a
link that has a subscriber. A batch is forgotten when the radio reports its
queue emptied cleanly, and returned to the front of the queue when the radio
reports a discard, when the last subscriber goes, or when the process restarts
while a batch was outstanding. That makes a disconnection cost latency rather
than data, which the absolute-only protocol achieved by making the loss
tolerable instead of impossible.

Confirmation can still be lost after the data landed, so delivery is
at-least-once and the phone makes it idempotent: a delta is identified by its
kind and its exact span, unique by construction, and one already written is
ignored. No acknowledgement travels back, and none is needed.

The queue holds 1,000 deltas, roughly a day of movement, because a queue that
grows without limit is a slower failure than a dropped delta. Overflowing it
costs sample placement rather than the count, since the absolute still notices
the shortfall.

## Link liveness

Restarting the watch app tears the GATT server down and rebuilds it with an
empty subscriber list, but the LE connection underneath can survive. iOS then
reports a healthy link with characteristic handles it is satisfied with, while
the watch no longer believes anyone is listening — and nothing in either stack
renegotiates. Data simply stops, with both sides convinced they are fine.

Force-quitting the phone app fixes it, because a fresh `CBCentralManager`
rediscovers services and rewrites the CCCD. The phone now does that without the
relaunch: the state it lands in has a name (`receiving`) and a deadline. See
`docs/06-lifecycles.md` for both ends' state machines and every deadline in
them.

## Source of truth

**The watch owns every daily aggregate.** Health Services' `STEPS_DAILY`
aggregate is the sole authority for the step total. It is batched for power and
can trail by minutes, and nothing second-guesses it in the meantime.

Two rules keep a replayed snapshot harmless. Within a day a total may only
rise. Across days it is rejected outright: every aggregate is stamped with the
day its interval *ends* in, so a snapshot of yesterday delivered at 00:01 —
which is a complete day's total — cannot be adopted as today's. Without that
second rule the may-only-rise rule would work against us, freezing the real
count until tomorrow.

Heart rate has two sources for one number, split by cost rather than by
authority: `HEART_RATE_BPM` passive monitoring for ambient readings, which ride
the platform's own sampling, and `MeasureClient` for live readings, which
powers the PPG and so runs only while the phone is subscribed, the phone has
just written, or the watch screen is up.

`day` is absolute, idempotent, and
carries the watch's own local date, so the phone never has to guess a day
boundary from its own clock or from UTC. It is sent on subscribe, on any
change (1s debounce), and on a 15-minute heartbeat.

The phone treats HealthKit as a *mirror* to reconcile against that total, not
as an accumulator:

- reads and writes are scoped to Dream Fit's own samples, so the iPhone's
  pocket pedometer is never read into our sum nor destroyed by our deletes
- mirror below truth → write the difference
- mirror above truth → delete our samples for that day and rewrite the total

That makes repeats, out-of-order delivery, reconnects and external edits all
self-healing, with no deltas, baselines or migrations to maintain.

### When the mirror cannot run

Reconciling needs two things the phone does not always have: permission to
write steps, and an unlocked device — HealthKit's store is sealed while the
phone is locked, which is where a phone spends most of its day. A `day` that
cannot be mirrored right now is therefore **queued, never dropped**, and
retried on all four of the events that can change the answer:

- the next `day` push (at most 15 minutes away, by the heartbeat)
- `protectedDataDidBecomeAvailable` — the phone was unlocked
- a change in Health authorisation
- the app coming to the foreground

Two consequences are worth stating plainly, because getting either wrong makes
the mirror fail in total silence:

- **Authorisation is read, not remembered.** Core Bluetooth state restoration
  can relaunch the iPhone app straight into the background, where no view ever
  appears. `HealthStore` therefore reads the existing grant in its initialiser
  — a synchronous, UI-free call — rather than waiting for a screen. A process
  that never asks never learns it is allowed to write.
- **Each type gates itself.** Steps are written if steps are authorised.
  Denying heart rate must not, and does not, stop them.

Every HealthKit failure is written to the in-app diagnostics log (five taps on
the status capsule), because a mirror that fails invisibly is indistinguishable
from one that was never wired up.

`hr` is live telemetry, not an aggregate — throttled to 5s, saved as samples,
and never summed into anything. `rhr` is the day's lowest 10-minute HR minimum.
`dist` is metres and
`floors` a count; both are Health Services daily aggregates and follow the same
rules as `steps`.

`rhr` needs twelve samples inside its ten-minute window before it will name a
figure at all, and the phone writes exactly one resting rate per day — the
day's minimum only falls, and appending each new low would leave Apple Health
showing three or four resting rates for one day.

Exercise minutes were removed rather than fixed. They came from
`USER_ACTIVITY_EXERCISE`, and a measured 30-minute walk of 2,560 steps never
raised the state at all, so the figure read zero for a real walk. The
heart-rate threshold it replaced would not have caught that walk either, and a
metric that is wrong in the direction of zero is worse than an absent one.

`CALORIES_DAILY` is deliberately not collected. Health Services reports total
calories including BMR, HealthKit's `activeEnergyBurned` means the active
figure alone, and there is no honest way to split one into the other — so the
number had no consumer and carrying it was work for nothing.

`sleep` is a session rather than a daily bucket, because a night crosses
midnight: `s` and `e` are epoch milliseconds for one closed asleep interval,
re-sent on every subscribe and sync so a reconnect recovers the last night. It
comes from `USER_ACTIVITY_ASLEEP` transitions, which are asleep/awake only —
Fitbit's four-stage staging is a proprietary inference no app on the watch can
read, so the phone writes it to HealthKit as `asleepUnspecified` rather than
inventing stages.

Every session is gated on the watch being worn, because a watch on a desk
reports `ASLEEP` (`07-health-services.md`), and its start is clamped to the
moment the wrist began — a state delivered on registration routinely predates
that. Taking the watch off closes the session then and there. Under 15 minutes
is stillness; over 16 hours is a wake transition never seen. The phone replaces
any overlapping sample of its own rather than appending, so a re-sent night
cannot read as two.
