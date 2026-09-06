package com.drmhse.dream.fit

import android.content.Context

// The step goal belongs to the phone's owner, not to the watch. The watch only
// remembers what it was last told, so the bezel is right after a reboot and
// before the phone reconnects.
class Settings(context: Context) {
    companion object {
        const val DEFAULT_GOAL = 10_000
        private val RANGE = 1_000..50_000
        private const val KEY = "goal"
    }

    private val prefs = context.getSharedPreferences("dreamfit", Context.MODE_PRIVATE)

    var stepGoal: Int = prefs.getInt(KEY, DEFAULT_GOAL).coerceIn(RANGE)
        set(value) {
            val next = value.coerceIn(RANGE)
            if (next == field) return
            field = next
            prefs.edit().putInt(KEY, next).apply()
            publish()
        }

    init { publish() }

    private fun publish() = WatchState.update { it.copy(goal = stepGoal) }
}
