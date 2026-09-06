# 01 — Bridge protocol

We own both ends, so the service is a tiny Nordic-UART-shaped GATT service.
No auth (lab only). Canonical definition lives in `wear/.../BridgeGatt.kt`.

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
```

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

`hr` is live telemetry, not an aggregate — throttled to 5s, saved as samples,
and never summed into anything.
