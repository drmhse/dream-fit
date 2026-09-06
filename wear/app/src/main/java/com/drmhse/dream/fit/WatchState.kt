package com.drmhse.dream.fit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Where the watch is in the link's lifecycle. Two booleans could not tell
// RESTING from ADVERTISING, so a watch quietly saving battery between bursts
// looked identical to one that had lost the phone.
enum class LinkPhase {
    STARTING,     // service up, GATT server not yet answering
    ADVERTISING,  // burst window open, nobody subscribed
    RESTING,      // between bursts, nobody subscribed — by design, not a fault
    SUBSCRIBED,   // the phone is listening; advertising is off
    STOPPED,
}

// What the watch face shows. The service owns it and the UI observes it, so
// there is no prefs round-trip or broadcast hop between a reading and the
// screen — and no second copy of the numbers to drift.
object WatchState {
    data class Snapshot(
        val bpm: Int = 0,
        val steps: Int = 0,
        val exMin: Int = 0,
        val goal: Int = Settings.DEFAULT_GOAL,
        val phase: LinkPhase = LinkPhase.STOPPED,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    fun update(block: (Snapshot) -> Snapshot) { _state.value = block(_state.value) }
}
