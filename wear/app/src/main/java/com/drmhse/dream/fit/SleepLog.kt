package com.drmhse.dream.fit

import android.content.Context

// Sleep is a session, not a daily bucket: it crosses midnight, so it is kept
// and reported as an absolute interval rather than assigned to a day. Health
// Services reports asleep/awake transitions, not Fitbit's four stages — the
// staging is a proprietary inference we cannot read and should not fake.
//
// Every session is gated on the watch being worn. A watch on a desk reports
// USER_ACTIVITY_ASLEEP — observed on this device: off-wrist at 07:34, declared
// asleep at 09:12 — so without the gate a charging watch would write a night
// of sleep into Apple Health.
class SleepLog(context: Context) {
    companion object {
        private const val KEY = "sleep"
        // Anything shorter is a still moment, not a night.
        private const val MIN_SESSION_MS = 900_000L
        // Anything longer is a wake transition we never saw — the service was
        // killed, or the listener was unregistered mid-night. Better to lose
        // the night than to report a 40-hour one.
        private const val MAX_SESSION_MS = 57_600_000L
        private const val RECENT_MS = 129_600_000L
    }

    private val prefs = context.getSharedPreferences("dreamfit", Context.MODE_PRIVATE)

    var start = 0L
        private set
    var end = 0L
        private set
    private var openedAt = 0L

    init {
        prefs.getString(KEY, "")?.split("|")?.takeIf { it.size == 3 }?.let {
            start = it[0].toLongOrNull() ?: 0
            end = it[1].toLongOrNull() ?: 0
            openedAt = it[2].toLongOrNull() ?: 0
        }
        publish()
    }

    val hasSession get() = start > 0 && end > start

    // What the watch face shows: only a night recent enough to still be last
    // night, so a stale record does not sit on the screen for days.
    fun publish() = WatchState.update {
        val recent = hasSession && System.currentTimeMillis() - end < RECENT_MS
        it.copy(sleepMin = if (recent) ((end - start) / 60_000L).toInt() else 0)
    }

    fun json() = """{"t":"sleep","s":$start,"e":$end}"""

    // Returns whether the phone should hear about it: only a closed session is
    // worth sending, and only once.
    fun apply(asleep: Boolean, at: Long, onBody: Boolean, wornSince: Long): Boolean {
        // A session may only open on a worn watch, and taking the watch off
        // ends it: removal is the last moment we have any evidence for, and
        // guessing a wake time from a watch on a bedside table is worse than
        // reporting a slightly short night.
        //
        // `at` is when the state took effect, which for a state delivered on
        // registration is routinely well in the past — and possibly while the
        // watch was on a desk. Observed here: asleep declared 09:12:30, wrist
        // at 09:19:18, state corrected 09:25:30. A session may not begin before
        // the wrist did, so the start is clamped to it. `wornSince` is 0 when
        // no off-to-on transition was actually seen, and then there is nothing
        // to clamp to.
        if (asleep && onBody) {
            if (openedAt == 0L) { openedAt = maxOf(at, wornSince); save() }
            return false
        }
        return close(at)
    }

    fun leftWrist(at: Long): Boolean = close(at)

    private fun close(at: Long): Boolean {
        val from = openedAt
        openedAt = 0
        val span = at - from
        if (from == 0L || span < MIN_SESSION_MS || span > MAX_SESSION_MS) { save(); return false }
        start = from
        end = at
        save()
        return true
    }

    private fun save() {
        publish()
        prefs.edit().putString(KEY, "$start|$end|$openedAt").apply()
    }
}
