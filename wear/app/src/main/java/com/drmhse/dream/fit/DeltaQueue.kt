package com.drmhse.dream.fit

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

// The belt keeps its load. A movement delta is the one message on this link
// that cannot be reconstructed later: Health Services publishes it once, keeps
// no history, and the phone needs the span it happened in. So a delta produced
// while nobody is subscribed is written to storage and delivered when the link
// comes back, rather than dropped into a send that has no targets.
//
// Delivery is confirmed by the link, not by the phone: a window is forgotten
// only once the radio reports its queue emptied without a discard, and returned
// to the front of the queue otherwise. Confirmation can still be lost after the
// data arrived, so a delta is identified by its kind and its exact span, which
// is unique by construction, and the phone ignores one it has already written.
class DeltaQueue(context: Context) {
    // What the radio confirmed, in the terms DayLog needs to write it off.
    data class Carried(val kind: String, val value: Double, val end: Long)

    private companion object {
        const val LEGACY_KEY = "deltas"
        // A day of movement at one coalesced entry per passive batch, with room
        // to spare. Past this the oldest go, because a queue that grows without
        // limit is a slower failure than a dropped delta.
        const val MAX = 5_000
        // Messages handed to the radio before waiting for it to confirm them.
        // The link chunks each one to the MTU and discards its whole backlog
        // past 512 chunks, which is what made a day's queue undeliverable: the
        // drain overflowed, the batch returned, and the next drain sent the
        // identical burst.
        const val WINDOW = 64
        // Health Services batches its deliveries, and the first point of a
        // batch continues the last point of the one before. Merging across that
        // boundary, up to this span, keeps a walk to a few Apple Health rows.
        const val MERGE_MAX_MS = 300_000L
    }

    private data class Entry(val kind: String, val value: Double, val start: Long, val end: Long) {
        fun line() = """{"t":"delta","k":"$kind","v":$value,"s":$start,"e":$end}"""
    }

    // Append-only, rewritten only when a window is confirmed. The previous
    // form re-serialised the entire queue on every single delta, which is
    // unnoticeable at four entries and unusable at a day's worth.
    private val log = File(context.filesDir, "deltas.log")
    // The radio confirms and discards on a binder thread while the service
    // queues on the main one, and an unsynchronised ArrayDeque corrupts its own
    // head under that. This crashed the process mid-drain, which took the whole
    // delta lane down and left the daily absolute mirroring on its own.
    private val lock = Any()
    private val pending = ArrayDeque<String>()
    // Handed to the radio and not yet reported as sent. Kept separate because
    // handing a message over is not delivering it: the link discards its
    // backlog on a disconnect, on a failed notify and on overflow, and a delta
    // dropped there is the one message on this link that no later absolute can
    // reconstruct the span of.
    private val inFlight = ArrayDeque<String>()

    init {
        // Anything in flight when the process died was never confirmed, so it
        // is simply pending again.
        if (log.exists()) log.forEachLine { if (it.isNotBlank()) pending.addLast(it) }
        val prefs = context.getSharedPreferences("dreamfit", Context.MODE_PRIVATE)
        if (prefs.contains(LEGACY_KEY)) {
            prefs.getString(LEGACY_KEY, "").orEmpty()
                .split('\n').filter { it.isNotBlank() }
                .forEach { pending.addLast(it) }
            prefs.edit().remove(LEGACY_KEY).apply()
            compact()
        }
        if (trim()) compact()
        if (pending.isNotEmpty()) Log.i(BridgeService.TAG, "${pending.size} deltas held from last run")
    }

    fun add(kind: String, value: Double, start: Long, end: Long): Unit = synchronized(lock) {
        // Only ever against pending: what is in flight has already gone out.
        val tail = pending.lastOrNull()?.let(::parse)
        if (tail != null && tail.kind == kind && start <= tail.end &&
            end - tail.start <= MERGE_MAX_MS
        ) {
            pending.removeLast()
            pending.addLast(Entry(kind, tail.value + value, tail.start, maxOf(tail.end, end)).line())
            // Rewrites the log, but once per batch rather than once per step.
            compact()
            return
        }
        val line = Entry(kind, value, start, end).line()
        pending.addLast(line)
        runCatching { log.appendText(line + "\n") }
            .onFailure { Log.w(BridgeService.TAG, "delta append failed", it) }
        if (trim()) compact()
    }

    // Anything that is not a movement delta: it takes the same belt, the same
    // confirmation and the same durability, but never merges with a neighbour.
    fun addMessage(line: String): Unit = synchronized(lock) {
        pending.addLast(line)
        runCatching { log.appendText(line + "\n") }
            .onFailure { Log.w(BridgeService.TAG, "message append failed", it) }
        if (trim()) compact()
    }

    private fun parse(line: String): Entry? = runCatching {
        JSONObject(line).let { Entry(it.getString("k"), it.getDouble("v"), it.getLong("s"), it.getLong("e")) }
    }.getOrNull()

    // One window at a time. The radio confirms a batch rather than a message,
    // so a second window sent before the first is answered has nothing to
    // return to when the answer is a discard.
    fun drain(send: (String) -> Unit) {
        val batch = synchronized(lock) {
            if (inFlight.isNotEmpty() || pending.isEmpty()) return
            repeat(minOf(WINDOW, pending.size)) { inFlight.addLast(pending.removeFirst()) }
            Log.d(BridgeService.TAG, "draining ${inFlight.size}, ${pending.size} behind")
            inFlight.toList()
        }
        // Sent outside the lock and off the deque: the radio reports an overflow
        // synchronously from inside send, and `returned` would otherwise mutate
        // the queue this loop is walking.
        batch.forEach(send)
    }

    // Returns what was confirmed so the day's ledger can write it off. Until
    // that happens the absolute has no way to know the load already travelled.
    fun delivered(): List<Carried> = synchronized(lock) {
        if (inFlight.isEmpty()) return emptyList()
        val carried = inFlight.mapNotNull(::parse).map { Carried(it.kind, it.value, it.end) }
        inFlight.clear()
        compact()
        carried
    }

    // Back to the front of the queue, in order, for the next window. The log
    // holds inFlight and pending as one sequence, so nothing on disk moves.
    fun returned(): Unit = synchronized(lock) {
        if (inFlight.isEmpty()) return
        Log.w(BridgeService.TAG, "${inFlight.size} deltas returned to the queue")
        while (inFlight.isNotEmpty()) pending.addFirst(inFlight.removeLast())
    }

    val backlog: Int get() = synchronized(lock) { inFlight.size + pending.size }

    private fun trim(): Boolean {
        var dropped = 0
        while (inFlight.size + pending.size > MAX) {
            if (inFlight.isNotEmpty()) inFlight.removeFirst() else pending.removeFirst()
            dropped++
        }
        if (dropped > 0) Log.w(BridgeService.TAG, "delta queue full, $dropped oldest dropped")
        return dropped > 0
    }

    private fun compact() {
        runCatching { log.writeText((inFlight + pending).joinToString("") { it + "\n" }) }
            .onFailure { Log.w(BridgeService.TAG, "delta compaction failed", it) }
    }
}
