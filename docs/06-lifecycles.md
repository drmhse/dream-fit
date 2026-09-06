# 06 — Lifecycles

Four state machines, two per end. They are written as machines because every
link bug this project has had was the same shape: two variables disagreeing
about one fact, with no name for the state they disagreed about.

## iPhone — the link (`BridgeCentral`)

One `phase`, one function that changes it, one timer that decides when a phase
has lasted too long to be believed.

| Phase | Means | On entry | Deadline |
|---|---|---|---|
| `radioDown(s)` | Bluetooth off, unauthorised or resetting | drop handles and buffers | — |
| `searching` | nobody to talk to yet | page the known watch, then scan | — |
| `connecting` | a connect request is outstanding | — | 120s → `searching` |
| `resolving` | connected; asking which characteristics exist | discover services | 10s → retry, 3× → drop link |
| `receiving` | the watch is talking to us and we hold no handle to answer on | — | 60s → rediscover, 3× → drop link |
| `ready` | full duplex | announce, then `sync` | — (freshness rules below) |

`searching` and `ready` are steady states. Everything else is a transition, and
a transition that does not finish **is** the bug — which is why only those
carry deadlines.

`receiving` is the important one. iOS can restore a subscription whose
characteristic discovery never completed, so notifications arrive on a handle
the app never learned about: the watch is heard and cannot be answered, `sync`
and `goal` go nowhere, and nothing renegotiates. It used to take a force-quit.
Naming the state is what let a timer fix it.

### Freshness

Liveness is counted in `day` messages, not in bytes — heart rate arrives every
5 s and would mask a link that can receive but no longer send.

- 20 minutes without a `day` (the heartbeat is 15) → send `sync`
- 25 minutes → drop the connection

Dropping is the point: reconnecting forces a fresh discovery and a fresh CCCD
write, which is the part the watch is waiting for.

## iPhone — the Health mirror (`HealthStore`)

| State | Means |
|---|---|
| idle | nothing outstanding |
| queued | a `day` is waiting for a condition that is not met yet |
| reconciling | a read-diff-write pass is in flight |

A day that cannot be mirrored is queued, never dropped, and retried on the next
push, on device unlock, on an authorisation change, and on foreground. One pass
at a time, because HealthKit is append-only and two interleaved passes would
each diff against the same stale sum.

## Watch — the link (`BridgeService` + `Advertiser`)

| Phase | Means | Leaves on |
|---|---|---|
| `STARTING` | service up, GATT server not yet answering | first burst, or a subscribe |
| `ADVERTISING` | burst window open, nobody subscribed | 30 s burst end → `RESTING`; subscribe → `SUBSCRIBED` |
| `RESTING` | between bursts, nobody subscribed | 5 min → `ADVERTISING`; subscribe → `SUBSCRIBED` |
| `SUBSCRIBED` | the phone is listening; advertising is off | unsubscribe or disconnect → `RESTING` |
| `STOPPED` | service destroyed | — |

`RESTING` earns its name: a watch saving battery between bursts is not a watch
that has lost the phone, and the two used to look identical on screen. The
phase comes from the radio's own callbacks, so it is observed rather than
assumed — a distinction that matters, because the stack reports advertising
asynchronously and a flag set from the request rather than the verdict is
wrong for as long as the stack takes to answer.

## Watch — the day record (`DayLog`)

Not a phase machine but a set of rules with one owner:

- rollover resets the day at the watch's own local midnight
- the Health Services aggregate is the authority and may only raise the total
- the hardware counter projects forward between aggregates, re-anchoring on
  every one, so a projected step is never counted twice
- writes to storage are throttled, and forced before every push and on destroy
