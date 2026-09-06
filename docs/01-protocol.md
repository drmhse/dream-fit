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
{"t":"day","d":"2026-09-05","steps":8412,"rhr":54,"exmin":22,"bat":73}
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
aggregate is the authority for the step total, but it is batched for power and
can trail the watch's own summary by minutes. Between aggregates the hardware
step counter projects forward: every aggregate re-anchors the counter, so a
projected step is never counted twice, and a stale aggregate replay can only
raise the total, never lower it. The projection updates the watch face
instantly and the radio at most once a minute.

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
and never summed into anything. `rhr` is the day's lowest 10-minute HR
minimum; `exmin` accrues one per minute above 110 bpm.
