package com.drmhse.dream.fit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// What the watch face shows. The service owns it and the UI observes it, so
// there is no prefs round-trip or broadcast hop between a reading and the
// screen — and no second copy of the numbers to drift.
object WatchState {
    data class Snapshot(
        val bpm: Int = 0,
        val steps: Int = 0,
        val exMin: Int = 0,
        val linked: Boolean = false,
        val running: Boolean = false,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    fun update(block: (Snapshot) -> Snapshot) { _state.value = block(_state.value) }
}
