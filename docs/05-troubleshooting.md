# 05 — When a number looks wrong

Everything here is diagnosable from the phone and the watch alone. Start by
knowing which number you are actually looking at.

## The three step counts

They are different numbers and they disagree for legitimate reasons.

| Where | What it is |
|---|---|
| Watch face / Dream Fit on the watch | `DayLog.steps` — Health Services' `STEPS_DAILY` aggregate, projected forward by the hardware counter |
| Dream Fit on the iPhone | the last `day` push, read back from `UserDefaults`. **It is never read from Apple Health** |
| Apple Health → Steps | the HealthKit mirror, plus your iPhone's own pocket pedometer |

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

`day` is `date|steps|rhr|exmin`. If that total matches the watch face, the
bridge is faithfully reporting what Health Services gave it, and any
disagreement with Fitbit is upstream of this project.

`STEPS_DAILY` arrives passively and can trail by minutes; between aggregates
the hardware counter projects forward from the last one. After a reboot, or
after the service is killed and restarted, the projection re-anchors and the
steps taken while it was down are only recovered when the next aggregate lands.

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
