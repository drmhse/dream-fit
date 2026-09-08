package com.drmhse.dream.fit

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

// The watch is the sole authority for daily aggregates, and Health Services is
// the sole authority for the readings behind them. One prefs key, reset on
// rollover: steps only ever rise within a day (Health Services replays stale
// snapshots out of order), RHR is the day's lowest ten-minute minimum.
class DayLog(context: Context) {
    companion object {
        private const val KEY = "day"
        private const val RHR_WINDOW_MS = 600_000L
        private const val RHR_MIN_SAMPLES = 12
        // A projected step is worth a prefs write every so often, not every step.
        private const val WRITE_THROTTLE_MS = 10_000L
        private val KINDS = listOf("steps", "dist", "floors")
    }

    private val prefs = context.getSharedPreferences("dreamfit", Context.MODE_PRIVATE)

    var date = ""
        private set
    var steps = 0
        private set
    var rhr = 0
        private set
    var distM = 0
        private set
    var floors = 0
        private set

    // What the delta lane has already delivered, and how far it reaches. The
    // phone tops up only the span past the watermark, so the same steps cannot
    // arrive twice by two routes.
    private val coveredUnits = mutableMapOf<String, Double>()
    private val coveredThrough = mutableMapOf<String, Long>()

    private var lastWrite = 0L
    private var lastBpmAt = 0L
    private val hrWindow = ArrayDeque<Pair<Long, Int>>()

    init {
        prefs.getString(KEY, "")?.split("|")?.takeIf { it.size >= 5 }?.let { f ->
            date = f[0]
            steps = f[1].toIntOrNull() ?: 0
            rhr = f[2].toIntOrNull() ?: 0
            distM = f[3].toIntOrNull() ?: 0
            floors = f[4].toIntOrNull() ?: 0
            if (f.size >= 5 + KINDS.size * 2) KINDS.forEachIndexed { n, k ->
                coveredUnits[k] = f[5 + n * 2].toDoubleOrNull() ?: 0.0
                coveredThrough[k] = f[6 + n * 2].toLongOrNull() ?: 0L
            }
        }
        roll()
        publish()
    }


    // `inc` is the service's incarnation: a change tells the phone this process
    // restarted and whatever it held in memory about the link is stale.
    fun json(battery: Int, incarnation: String) =
        """{"t":"day","d":"$date","steps":$steps,"rhr":$rhr,""" +
            """"dist":$distM,"floors":$floors,"bat":$battery,"inc":"$incarnation",""" +
            """"cov":{""" + KINDS.joinToString(",") {
                """"$it":[${Math.round(coveredUnits[it] ?: 0.0)},${coveredThrough[it] ?: 0L}]"""
            } + "}}"

    // MARK: - Mutations. Each returns whether the phone should hear about it.

    fun roll(): Boolean {
        val today = LocalDate.now().toString()
        if (date == today) return false
        date = today
        steps = 0
        rhr = 0
        distM = 0
        floors = 0
        hrWindow.clear()
        lastBpmAt = 0
        coveredUnits.clear()
        coveredThrough.clear()
        save(force = true)
        return true
    }

    // Every daily aggregate follows the same rule: absolute, and it may only
    // rise within a day, because Health Services replays stale snapshots.
    //
    // `forDate` is the day the aggregate actually covers, not the day it
    // arrived. A snapshot of yesterday delivered after midnight is a complete
    // day's total, and adopting it as today's would both inflate today and,
    // because the totals may only rise, freeze the real count until tomorrow.
    fun applyDailyTotals(forDate: String, steps: Int, distM: Int, floors: Int): Boolean {
        roll()
        if (forDate.isNotEmpty() && forDate != date) return false
        var changed = false
        if (steps > this.steps) { this.steps = steps; changed = true }
        if (distM > this.distM) { this.distM = distM; changed = true }
        if (floors > this.floors) { this.floors = floors; changed = true }
        if (changed) save(force = true)
        return changed
    }

    // The belt's receipt. Until a delivered delta is written off here, the
    // absolute still claims that load and the phone writes it a second time.
    fun applyCovered(carried: List<DeltaQueue.Carried>): Boolean {
        if (carried.isEmpty()) return false
        roll()
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var changed = false
        for (c in carried) {
            if (c.kind !in KINDS || c.end < dayStart) continue
            coveredUnits[c.kind] = (coveredUnits[c.kind] ?: 0.0) + c.value
            coveredThrough[c.kind] = maxOf(coveredThrough[c.kind] ?: 0L, c.end)
            changed = true
        }
        if (changed) save(force = true)
        return changed
    }

    // Exercise minutes are the platform's own detection, not a heart-rate
    // threshold of ours: USER_ACTIVITY_EXERCISE opens the span, anything else
    // closes it.

    // Batched history and live samples interleave, so the window is anchored to
    // the newest time seen rather than to arrival order. Dropping everything
    // not strictly newest would throw away a whole batch of history the moment
    // one live sample landed.
    fun applyBpm(at: Long, bpm: Int): Boolean {
        val newest = maxOf(lastBpmAt, at)
        if (at <= newest - RHR_WINDOW_MS) return false
        lastBpmAt = newest
        roll()
        var changed = false
        hrWindow.addLast(at to bpm)
        while (hrWindow.isNotEmpty() && newest - hrWindow.first().first > RHR_WINDOW_MS) hrWindow.removeFirst()
        if (hrWindow.size >= RHR_MIN_SAMPLES) {
            val low = hrWindow.minOf { it.second }
            if (rhr == 0 || low < rhr) { rhr = low; changed = true }
        }
        if (changed) save(force = true)
        return changed
    }

    // MARK: - Persistence

    fun flush() = save(force = true)

    private fun save(force: Boolean = false) {
        publish()
        val now = System.currentTimeMillis()
        if (!force && now - lastWrite < WRITE_THROTTLE_MS) return
        lastWrite = now
        val cov = KINDS.joinToString("|") { "${coveredUnits[it] ?: 0.0}|${coveredThrough[it] ?: 0L}" }
        prefs.edit().putString(KEY, "$date|$steps|$rhr|$distM|$floors|$cov").apply()
    }

    private fun publish() = WatchState.update {
        it.copy(steps = steps, distanceM = distM, floors = floors)
    }
}
