package com.drmhse.dream.fit

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Health Services is the sole authority for both daily aggregates and ambient
// heart rate. It taps the sampling the platform already does for itself, so
// nothing here powers a sensor: the app never holds the PPG open to watch a
// number the system is measuring anyway.
// kind, value, and the span it happened in.
private typealias Delta = Triple<String, Double, Pair<Long, Long>>

class PassiveDataService : PassiveListenerService() {
    private companion object {
        // A reading outside this is a sensor artefact, not a pulse.
        const val BPM_MIN = 20
        const val BPM_MAX = 250
        // Health Services publishes one interval point per step, a few hundred
        // milliseconds apart. Contiguous points are folded up to this span, so a
        // walk lands as a handful of Apple Health rows rather than one per step.
        const val MERGE_MAX_MS = 300_000L
    }

    override fun onNewDataPointsReceived(points: DataPointContainer) {
        // Every reading is timed from boot, so one boot instant dates them all.
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

        // A DAILY point covers "start of day to now" and resets at local
        // midnight, so it is cumulative and may only rise — but a point
        // delivered late still belongs to the day it was measured in. Carrying
        // the day it covers lets DayLog reject yesterday's total instead of
        // adopting it as today's, which is how a stale snapshot arriving at
        // 00:01 would otherwise wipe out a whole day's counting.
        val steps = daily(points, DataType.STEPS_DAILY, boot)
        val dist = daily(points, DataType.DISTANCE_DAILY, boot)
        val floors = daily(points, DataType.FLOORS_DAILY, boot)
        val dayDate = listOfNotNull(steps, dist, floors).map { it.first }.distinct()

        val hr = points.getData(DataType.HEART_RATE_BPM)
            .map { it.getTimeInstant(boot).toEpochMilli() to it.value.toInt() }
            .filter { it.second in BPM_MIN..BPM_MAX }
            .sortedBy { it.first }

        // The interval variants carry the span the movement happened in, which
        // is what Apple Health needs to merge our samples against the phone's
        // rather than add them: a floors delta stamped as an instant three
        // minutes from the phone's own reads as a second climb.
        val deltas = coalesce(listOf(
            DataType.STEPS to "steps", DataType.DISTANCE to "dist", DataType.FLOORS to "floors",
        ).flatMap { (type, label) ->
            points.getData(type)
                .filter { it.value.toDouble() > 0 }
                .map {
                    Triple(
                        label,
                        it.value.toDouble(),
                        it.getStartInstant(boot).toEpochMilli() to it.getEndInstant(boot).toEpochMilli(),
                    )
                }
        })
        if (deltas.isNotEmpty()) {
            Log.d(TAG, "deltas " + deltas.joinToString(" ") {
                "${it.first}=${it.second}@${Instant.ofEpochMilli(it.third.first)}..${Instant.ofEpochMilli(it.third.second)}"
            })
            startForegroundService(
                Intent(this, BridgeService::class.java)
                    .setAction(BridgeService.DELTAS)
                    .putExtra("kinds", deltas.map { it.first }.toTypedArray())
                    .putExtra("values", deltas.map { it.second }.toDoubleArray())
                    .putExtra("starts", deltas.map { it.third.first }.toLongArray())
                    .putExtra("ends", deltas.map { it.third.second }.toLongArray()),
            )
        }

        if (dayDate.size > 1) Log.w(TAG, "aggregates span days $dayDate, applying each on its own")
        // The heart-rate span is logged because it is the one number here whose
        // timestamps we derive ourselves, from a boot instant: a wrong boot
        // instant produces samples in 1970 or in the future, and the resting
        // rate would fail its quorum rather than look wrong.
        val span = if (hr.isEmpty()) "" else
            " span=${Instant.ofEpochMilli(hr.first().first)}..${Instant.ofEpochMilli(hr.last().first)}"
        Log.d(TAG, "passive steps=$steps dist=$dist floors=$floors hr=${hr.size}$span")

        if (dayDate.isEmpty() && hr.isEmpty()) return
        // One day per intent: DayLog owns exactly one day at a time, and a
        // batch that straddles midnight must not have both halves collapsed.
        // Heart rate rides the first intent only, because it is not per-day and
        // DayLog would apply the same samples twice. Earlier this returned
        // instead of clearing, which dropped the second day of a batch that
        // straddled midnight.
        var carryHr = true
        for (date in dayDate.ifEmpty { listOf("") }) {
            startForegroundService(
                Intent(this, BridgeService::class.java)
                    .setAction(BridgeService.PASSIVE)
                    .putExtra("date", date)
                    .putExtra("steps", steps.valueOn(date))
                    .putExtra("distM", dist.valueOn(date))
                    .putExtra("floors", floors.valueOn(date))
                    .putExtra("hrTimes", if (carryHr) hr.map { it.first }.toLongArray() else longArrayOf())
                    .putExtra("hrBpms", if (carryHr) hr.map { it.second }.toIntArray() else intArrayOf()),
            )
            carryHr = false
        }
    }

    // Contiguous points of one kind become one, up to MERGE_MAX_MS. The span is
    // preserved rather than collapsed: it is the whole reason these are sent.
    private fun coalesce(deltas: List<Delta>): List<Delta> =
        deltas.groupBy { it.first }.values.flatMap { ofKind ->
            val out = mutableListOf<Delta>()
            for (d in ofKind.sortedBy { it.third.first }) {
                val last = out.lastOrNull()
                if (last != null && d.third.first <= last.third.second &&
                    d.third.second - last.third.first <= MERGE_MAX_MS
                ) {
                    out[out.lastIndex] = Triple(
                        last.first,
                        last.second + d.second,
                        last.third.first to maxOf(last.third.second, d.third.second),
                    )
                } else {
                    out.add(d)
                }
            }
            out
        }

    // The freshest point wins, dated by the end of the interval it covers.
    // Values are rounded rather than truncated: floors and distance arrive as
    // Doubles, and truncating every delivery loses up to a whole unit a day.
    private fun <T : Number> daily(
        points: DataPointContainer,
        type: DeltaDataType<T, IntervalDataPoint<T>>,
        boot: Instant,
    ): Pair<String, Int>? =
        points.getData(type)
            .maxByOrNull { it.getEndInstant(boot) }
            ?.let { p ->
                val date = LocalDate.ofInstant(p.getEndInstant(boot), ZoneId.systemDefault()).toString()
                date to Math.round(p.value.toDouble()).toInt()
            }

    private fun Pair<String, Int>?.valueOn(date: String) =
        if (this != null && first == date) second else -1

    // Sleep and exercise arrive as state transitions, not readings. The state
    // is what the platform already decided; the timestamp is its own, so a
    // transition delivered late still lands in the right place.
    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val at = info.stateChangeTime.toEpochMilli()
        Log.d(TAG, "activity ${info.userActivityState} at ${info.stateChangeTime}")
        startForegroundService(
            Intent(this, BridgeService::class.java)
                .setAction(BridgeService.ACTIVITY_STATE)
                .putExtra("asleep", info.userActivityState == UserActivityState.USER_ACTIVITY_ASLEEP)
                .putExtra("exercise", info.userActivityState == UserActivityState.USER_ACTIVITY_EXERCISE)
                .putExtra("at", at),
        )
    }

    // Health Services unregisters the request and stops the sensors when this
    // fires, so silence afterwards is total: no steps, no heart rate, no
    // sleep. Reading heart rate from the background needs
    // BODY_SENSORS_BACKGROUND, and without it this is exactly what happens
    // the moment the app stops being visible.
    override fun onPermissionLost() {
        Log.w(TAG, "passive permission lost — registration dropped by Health Services")
        startForegroundService(
            Intent(this, BridgeService::class.java).setAction(BridgeService.PASSIVE_LOST),
        )
    }

    private val TAG get() = BridgeService.TAG
}
