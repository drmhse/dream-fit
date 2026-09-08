# 05 — When a number looks wrong

Everything here is diagnosable from the phone and the watch alone. Start by
knowing which number you are actually looking at.

## The three step counts

They are different numbers and they disagree for legitimate reasons.

| Where | What it is |
|---|---|
| Watch face / Dream Fit on the watch | `DayLog.steps` — Health Services' `STEPS_DAILY` aggregate, and nothing else |
| Dream Fit on the iPhone | the last `day` push, read back from `UserDefaults`. **It is never read from Apple Health** |
| Apple Health → Steps | HealthKit's own merge across every source, which is not the sum of them |

The middle one is the trap: the ring on the phone shows what the watch said,
not what reached Apple Health. The app looking right proves the *link* works
and says nothing about the mirror.

## Steps show in Dream Fit but not in Apple Health

In order:

1. **Five taps on the status capsule** opens diagnostics. `health authorized`
   means the mirror is allowed to run; `denied` or `setup` means it is not, and
   the banner above says so too. Any HealthKit failure is logged there by name.
2. **Health → Browse → Steps → Data Sources & Access.** Permission puts Dream
   Fit in Health's *Apps* list; an actual written sample puts it in *Sources*
   for that data type. Absent from Sources means nothing has ever been written.
3. **Unlock the phone and open the app.** HealthKit is sealed while the device
   is locked, so a phone that sat on a desk all day rejected every write
   attempt. The queued day is retried on unlock, on foreground, on the next
   push and on any authorisation change — but if you want it *now*, unlocking
   is the trigger.

The third one is not addition, which an earlier revision of this document got
wrong. A statistics query with no source predicate, which is what the Health app
uses, applies HealthKit's cross-source merge: for an interval covered by two
sources it takes one of them by source priority rather than adding both, and it
adds only the intervals a single source covered. Apple's guidance is that
`HKStatisticsQuery` and `HKStatisticsCollectionQuery` are the queries that
reproduce what Health displays, and that summing samples from a plain
`HKSampleQuery` is what produces a total "significantly larger than expected".

The consequence is the opposite of double counting, and it was measured on a
30-minute walk carrying the watch and the phone together (2026-09-07, per-source
sums from one `separateBySource` query, sampled on each `day` push):

```
13:07  watch=213   ours=213   health=188    [Dream Fit=213  iPhone=28]
13:28  watch=2073  ours=213   health=1537   [iPhone=1479    Dream Fit=213]
13:29  watch=2073  ours=2073  health=1537   [Dream Fit=2073 iPhone=1479]
13:46  watch=2773  ours=2073  health=2915   [iPhone=2857    Dream Fit=2073]
```

Three things follow, none of which a reading of the totals alone would show.

**It is not addition.** The last row sums to 4930 across sources and Health
shows 2915.

**It is not a maximum either.** The first row has Health at 188 while this app's
own samples total 213, so the merge selects per interval rather than picking a
winning source for the day.

**The iPhone outranks Dream Fit here.** In the 13:29 row this app's total rose
from 213 to 2073 and the figure Health displays did not move from 1537. By the
end, 2073 written steps had contributed 58 to what Health shows: the merge took
the phone's coverage and added only the intervals the phone had not covered.

So on any day the phone travels with you, the watch's step count is largely
passed over. Health → Browse → Steps → Data Sources & Access orders that
priority, and Dream Fit has to sit above iPhone there for the watch's count to
win. Diagnostics carries a **Compare our steps with Health** button that prints
the same three figures for the current day on demand.

The 13:28 and 13:29 rows are also the queue working: `ours` sat at 213 while the
watch had counted 2073, because the phone was locked and HealthKit's store is
sealed then, and it caught up within a second of the phone being unlocked.

The mirror's own reconcile is deliberately not merged: `mySum` scopes to
`HKSource.default()`, because the diff has to be computed against what this app
wrote rather than against a figure another source contributed to.

Do not compare against the iPhone's own step count to decide whether the
mirror ran: a phone left on a desk correctly reports zero steps of its own.

## Nothing connects at all, on either side

The service requires an encrypted, MITM-protected link, so it is unreachable
unless the watch is paired to the phone as a Bluetooth accessory. That is not a
degraded mode — there is no plaintext fallback by design. Check Settings →
Bluetooth on the iPhone: if the watch is not in **My Devices**, pair it, and the
bridge comes back with it.

`adb shell dumpsys bluetooth_manager | grep le_authenticated` on the watch is
the direct answer: `le_authenticated:T` means the bond satisfies the MITM
requirement. `F` means the pairing was made without numeric comparison and the
watch will refuse the subscribe — re-pair to fix it.

## The watch's number looks too low

Check what the watch itself thinks before suspecting the bridge:

```
adb shell run-as com.drmhse.dream.fit \
  cat /data/data/com.drmhse.dream.fit/shared_prefs/dreamfit.xml
```

`day` is `date|steps|rhr|distanceM|floors`, and `sleep` is
`start|end|openedAt` in epoch milliseconds — `openedAt` non-zero means a sleep
session is open right now. A record with a different field count is from an
older version and is ignored rather than migrated; the next aggregate refills
it, since every daily total is absolute.

If the step total matches the watch face, the bridge is faithfully reporting
what Health Services gave it, and any disagreement with Fitbit is upstream of
this project.

`STEPS_DAILY` arrives passively and can trail by minutes, so the watch face can
sit behind your actual step count until the next aggregate lands. That lag is
deliberate: the hardware step counter used to paper over it, and reading a
sensor to second-guess a number the platform already reports is what this app
no longer does. Steps taken while the service was down are recovered by the
next aggregate, since the aggregate is absolute rather than incremental.

## The watch says `searching` and the phone shows stale data

`searching` means nobody is subscribed to TX. If the phone *also* looks
connected, this is the half-open link described in `01-protocol.md`: the watch
app restarted, its subscriber list is empty, and the LE connection underneath
survived — so iOS still believes everything is fine. Both sides are wrong in
opposite directions and neither renegotiates.

Force-quitting and relaunching the iPhone app clears it immediately, and that
is worth knowing because it is the fastest manual fix. The phone's watchdog now
does the same thing on its own, but deliberately slowly: it waits out 20 minutes
of silence before probing, because the `day` heartbeat is only every 15 and a
trigger-happy reconnect would cost more battery than it saves.

After **reinstalling the watch app** the same thing happens by construction, so
expect either the relaunch or the watchdog to be what restores it — not the
advertising burst.
