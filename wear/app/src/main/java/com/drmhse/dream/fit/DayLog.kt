package com.drmhse.dream.fit

import android.content.Context
import java.time.LocalDate

// The watch is the sole authority for daily aggregates. One prefs key, reset on
// rollover: steps only ever rise within a day (Health Services replays stale
// snapshots out of order), RHR is the day's lowest ten-minute minimum.
class DayLog(context: Context) {
    companion object {
        private const val KEY = "day"
        private const val EX_BPM = 110
        private const val RHR_WINDOW_MS = 600_000L
        private const val RHR_MIN_SAMPLES = 12
        // A projected step is worth a prefs write every so often, not every step.
        private const val WRITE_THROTTLE_MS = 10_000L
        // Keys from versions before the single `day` record. Left behind by an
        // update they would sit in prefs forever, so sweep them once.
        private val LEGACY = listOf("abs_v1", "swept_v1", "last_counter", "ui_hr")
    }

    private val prefs = context.getSharedPreferences("dreamfit", Context.MODE_PRIVATE)

    var date = ""
        private set
    var steps = 0
        private set
    var rhr = 0
        private set
    var exMin = 0
        private set

    // Health Services batches its aggregates to save power, so the hardware
    // counter fills the gap: a projection from the last one, never an authority.
    private var anchor: Float? = null
    private var base = 0
    private var counter: Float? = null

    private var lastWrite = 0L
    private val hrWindow = ArrayDeque<Pair<Long, Int>>()
    private var lastExMark = 0L

    init {
        prefs.getString(KEY, "")?.split("|")?.takeIf { it.size == 4 }?.let {
            date = it[0]
            steps = it[1].toIntOrNull() ?: 0
            rhr = it[2].toIntOrNull() ?: 0
            exMin = it[3].toIntOrNull() ?: 0
        }
        sweepLegacy()
        roll()
        publish()
    }

    private fun sweepLegacy() {
        val stale = prefs.all.keys.filter { it in LEGACY || it.startsWith("passive_") }
        if (stale.isEmpty()) return
        prefs.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    fun json(battery: Int) =
        """{"t":"day","d":"$date","steps":$steps,"rhr":$rhr,"exmin":$exMin,"bat":$battery}"""

    // MARK: - Mutations. Each returns whether the phone should hear about it.

    fun roll(): Boolean {
        val today = LocalDate.now().toString()
        if (date == today) return false
        date = today
        steps = 0
        rhr = 0
        exMin = 0
        base = 0
        anchor = counter
        hrWindow.clear()
        save(force = true)
        return true
    }

    // Re-anchor either way: the projection continues from the reported total, so
    // steps the aggregate already counted are never added a second time.
    fun applyDailyTotal(total: Int): Boolean {
        if (total < 0) return false
        roll()
        if (total > steps) steps = total
        base = steps
        anchor = counter
        save(force = true)
        return true
    }

    fun applyCounter(value: Float): Boolean {
        counter = value
        roll()
        val from = anchor
        // First reading, or the counter reset on reboot: anchor, never project.
        if (from == null || value < from) {
            anchor = value
            base = steps
            return false
        }
        val projected = base + (value - from).toInt()
        if (projected <= steps) return false
        steps = projected
        save()
        return true
    }

    fun applyBpm(now: Long, bpm: Int): Boolean {
        roll()
        var changed = false
        hrWindow.addLast(now to bpm)
        while (hrWindow.isNotEmpty() && now - hrWindow.first().first > RHR_WINDOW_MS) hrWindow.removeFirst()
        if (hrWindow.size >= RHR_MIN_SAMPLES) {
            val low = hrWindow.minOf { it.second }
            if (rhr == 0 || low < rhr) { rhr = low; changed = true }
        }
        if (bpm >= EX_BPM && now - lastExMark > 60_000) {
            lastExMark = now
            exMin++
            changed = true
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
        prefs.edit().putString(KEY, "$date|$steps|$rhr|$exMin").apply()
    }

    private fun publish() = WatchState.update { it.copy(steps = steps, exMin = exMin) }
}
