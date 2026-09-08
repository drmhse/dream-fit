package com.drmhse.dream.fit

import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.PassiveListenerConfig
import java.util.UUID
import org.json.JSONObject

// The link itself: readings in, GATT out, and the phone's requests back the
// other way. Health Services owns every reading — passively for ambient, via
// MeasureClient for live — and the day's numbers belong to DayLog, the tray to
// Tray and the radio's duty cycle to Advertiser.
class BridgeService : Service() {
    companion object {
        const val TAG = "DreamFit"
        const val ANSWER = "dreamfit.ANSWER"
        const val DECLINE = "dreamfit.DECLINE"
        const val PASSIVE = "dreamfit.PASSIVE"
        const val ACTIVITY_STATE = "dreamfit.ACTIVITY_STATE"
        const val PASSIVE_LOST = "dreamfit.PASSIVE_LOST"
        const val DELTAS = "dreamfit.DELTAS"
        const val WATCHING = "dreamfit.WATCHING"
        const val BOOT = "dreamfit.BOOT"
        private const val DAY_HEARTBEAT_MS = 900_000L
        private const val DAY_DEBOUNCE_MS = 1_000L
        // A discarded window is a busy radio, not a lost one. Retried rather
        // than spun on.
        private const val DRAIN_RETRY_MS = 2_000L
        private const val HR_THROTTLE_MS = 5_000L
        private const val LIVE_HR_MS = 120_000L
        private const val HR_FRESH_MS = 300_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    // Minted per service lifetime. A crash and a START_STICKY restart are
    // invisible to the phone otherwise, and it goes on trusting state this
    // process no longer holds.
    private val incarnation = UUID.randomUUID().toString().take(8)
    private lateinit var day: DayLog
    private lateinit var sleep: SleepLog
    private lateinit var settings: Settings
    private lateinit var tray: Tray
    private lateinit var advertiser: Advertiser
    private lateinit var link: GattLink
    private lateinit var wrist: WristWatcher
    private lateinit var deltas: DeltaQueue

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        day = DayLog(this)
        sleep = SleepLog(this)
        settings = Settings(this)
        tray = Tray(this)
        // The radio reports its own duty cycle, so ADVERTISING and RESTING are
        // observed rather than assumed. A subscriber outranks both.
        advertiser = Advertiser(this, handler) { on ->
            if (!link.hasSubscribers) phase(if (on) LinkPhase.ADVERTISING else LinkPhase.RESTING)
        }
        // Every one of these arrives on a binder thread, and two of them mutate
        // the delta queue while the service is draining it on this one. Posting
        // puts the whole queue on a single thread and stops `onDropped` from
        // re-entering the drain it was raised inside.
        link = GattLink(
            this,
            { handler.post { onSubscribed() } },
            { handler.post { onIdle() } },
            ::handlePhoneMsg,
            onFlushed = { handler.post { onDeltasDelivered() } },
            onDropped = {
                handler.post {
                    deltas.returned()
                    handler.removeCallbacks(drainRetry)
                    handler.postDelayed(drainRetry, DRAIN_RETRY_MS)
                }
            },
        )
        wrist = WristWatcher(this, ::onWristRemoved, ::onWristWorn)
        deltas = DeltaQueue(this)
        startForeground(Tray.FOREGROUND_ID, tray.foregroundNotification())
        phase(LinkPhase.STARTING)
        link.start(getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
        handler.post(burstLoop)
        handler.postDelayed(heartbeat, DAY_HEARTBEAT_MS)
        registerPassive()
        wrist.start()
        startAncs()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        when (i?.action) {
            ANSWER -> { ancs?.performAction(i.getStringExtra("uid").orEmpty(), true); tray.cancelCall() }
            DECLINE -> { ancs?.performAction(i.getStringExtra("uid").orEmpty(), false); tray.cancelCall() }
            PASSIVE -> onPassive(i)
            ACTIVITY_STATE -> onActivityState(i)
            DELTAS -> onDeltas(i)
            PASSIVE_LOST -> WatchState.update { it.copy(ambientBlocked = true) }
            // onCreate has already done the work; the action exists so a boot
            // start is visible in the log as a boot rather than a mystery.
            BOOT -> Unit
            WATCHING -> {
                // The screen coming up is the app being visible again, which is
                // when a permission-driven unregister can be undone.
                if (WatchState.state.value.ambientBlocked) registerPassive()
                // Passive monitoring batches for power and keeps no history, so
                // the only way to see current data is to ask for what is
                // buffered rather than wait out the batching interval.
                flushPassive()
                liveHr(LIVE_HR_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        phase(LinkPhase.STOPPED)
        day.flush()
        runCatching { ancs?.stop() }
        handler.removeCallbacksAndMessages(null)
        wrist.stop()
        setLiveHr(false)
        advertiser.stop()
        link.close()
        super.onDestroy()
    }

    // ---- day pushes ----

    private val dayPush = Runnable {
        day.flush()
        send(day.json(batteryPct(), incarnation))
    }

    // The radio's receipt, written off against the day: what the phone already
    // holds is no longer the absolute's to claim. Confirming one window is also
    // what releases the next, so a day's backlog walks out a window at a time.
    private fun onDeltasDelivered() {
        if (day.applyCovered(deltas.delivered())) scheduleDayPush()
        drainDeltas()
    }

    private val drainRetry = Runnable { drainDeltas() }

    private fun scheduleDayPush() {
        handler.removeCallbacks(dayPush)
        handler.postDelayed(dayPush, DAY_DEBOUNCE_MS)
    }

    // The absolute is idempotent, so a heartbeat costs one small notification
    // and buys the phone a ceiling on how stale it can ever be.
    private val heartbeat = object : Runnable {
        override fun run() {
            day.roll()
            scheduleDayPush()
            handler.postDelayed(this, DAY_HEARTBEAT_MS)
        }
    }

    private fun phase(p: LinkPhase) = WatchState.update { it.copy(phase = p) }

    private fun batteryPct() =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    // ---- heart rate and daily aggregates ----

    private var lastHr = 0
    private var lastHrAt = 0L
    private var lastHrPush = 0L

    // Ambient readings and the day's totals both arrive here, from the
    // platform's own sampling. Nothing in this path powers a sensor.
    private fun onPassive(i: Intent) {
        // Delivery is the only proof the registration is alive, so it is also
        // what clears the warning. There is no second flag for the same fact:
        // the screen-on path reads this state to decide whether to re-register.
        if (WatchState.state.value.ambientBlocked) {
            WatchState.update { it.copy(ambientBlocked = false) }
        }
        var push = day.applyDailyTotals(
            i.getStringExtra("date").orEmpty(),
            i.getIntExtra("steps", -1),
            i.getIntExtra("distM", -1),
            i.getIntExtra("floors", -1),
        )
        val times = i.getLongArrayExtra("hrTimes")
        val bpms = i.getIntArrayExtra("hrBpms")
        if (times != null && bpms != null && times.size == bpms.size) {
            for (n in times.indices) if (day.applyBpm(times[n], bpms[n])) push = true
            queueBeats(times, bpms)
            // A batch can carry hours of history. Only the newest sample is a
            // current reading, and only if it is actually recent — the rest
            // belong to the day's aggregates, not to the bpm on the screen.
            val newest = times.indices.maxByOrNull { times[it] }
            if (newest != null && System.currentTimeMillis() - times[newest] < HR_FRESH_MS) {
                publishHr(bpms[newest])
            }
        }
        if (push) scheduleDayPush()
    }

    // Queued rather than sent, then drained whenever the link has a listener.
    // Health Services publishes a delta once and keeps no history, so this is
    // the only copy: dropping it into a send with no targets would lose the
    // span for good.
    private fun onDeltas(i: Intent) {
        val kinds = i.getStringArrayExtra("kinds") ?: return
        val values = i.getDoubleArrayExtra("values") ?: return
        val starts = i.getLongArrayExtra("starts") ?: return
        val ends = i.getLongArrayExtra("ends") ?: return
        if (kinds.size != values.size || kinds.size != starts.size || kinds.size != ends.size) return
        for (n in kinds.indices) deltas.add(kinds[n], values[n], starts[n], ends[n])
        drainDeltas()
    }

    private fun drainDeltas() {
        handler.removeCallbacks(drainRetry)
        if (link.hasSubscribers) deltas.drain(::send)
    }

    // Ambient heart rate arrives around thirty readings a minute. Every one of
    // them is neither useful in Apple Health nor deliverable across a day's
    // backlog, so a minute becomes one reading: the median, which no single PPG
    // artefact can drag, carried at the time it was actually measured. Full
    // resolution still reaches DayLog, which needs it for the resting quorum.
    private fun queueBeats(times: LongArray, bpms: IntArray) {
        if (times.isEmpty()) return
        times.indices.groupBy { times[it] / 60_000L }.forEach { (_, idx) ->
            val mid = idx.sortedBy { bpms[it] }[idx.size / 2]
            if (bpms[mid] > 0) deltas.addMessage("""{"t":"hr","bpm":${bpms[mid]},"at":${times[mid]}}""")
        }
        drainDeltas()
    }

    // The live tile only: no timestamp, so the phone shows it and does not file
    // it. What Apple Health gets is the belt's minute readings, which survive a
    // day with nobody listening.
    private fun publishHr(bpm: Int) {
        if (bpm <= 0) return
        lastHr = bpm
        lastHrAt = System.currentTimeMillis()
        WatchState.update { it.copy(bpm = bpm) }
        val now = System.currentTimeMillis()
        if (now - lastHrPush < HR_THROTTLE_MS) return
        lastHrPush = now
        send("""{"t":"hr","bpm":$bpm}""")
    }

    private fun flushPassive() {
        runCatching {
            HealthServices.getClient(this).passiveMonitoringClient.flushAsync()
        }.onFailure { Log.w(TAG, "flush", it) }
    }

    private fun registerPassive() {
        runCatching {
            val config = PassiveListenerConfig.builder()
                .setDataTypes(
                    setOf(
                        DataType.STEPS_DAILY,
                        DataType.DISTANCE_DAILY,
                        DataType.FLOORS_DAILY,
                        // The interval variants alongside the daily absolutes:
                        // each point carries the span the movement happened in,
                        // which is the shape HealthKit needs to merge our
                        // samples against the phone's instead of adding them.
                        DataType.STEPS,
                        DataType.DISTANCE,
                        DataType.FLOORS,
                        DataType.HEART_RATE_BPM,
                    ),
                )
                .setShouldUserActivityInfoBeRequested(true)
                .build()
            val client = HealthServices.getClient(this).passiveMonitoringClient
            client.setPassiveListenerServiceAsync(PassiveDataService::class.java, config)
            // What this watch will actually give us, recorded once at startup:
            // the passive set differs by model, and a silently absent state
            // reads exactly like a feature that never fires.
            val caps = client.getCapabilitiesAsync()
            caps.addListener({
                runCatching {
                    val c = caps.get()
                    Log.i(TAG, "caps states=${c.supportedUserActivityStates} " +
                        "types=${c.supportedDataTypesPassiveMonitoring.map { it.name }}")
                }.onFailure { Log.w(TAG, "caps", it) }
            }, handler::post)
        }.onFailure { Log.w(TAG, "passive", it) }
    }

    // ---- worn or not ----

    private var pendingAsleep: Long? = null

    private fun onWristRemoved() {
        pendingAsleep = null
        if (sleep.leftWrist(System.currentTimeMillis())) send(sleep.json())
    }

    private fun onWristWorn() {
        pendingAsleep?.let { pendingAsleep = null; applySleep(true, it) }
    }

    private fun onActivityState(i: Intent) {
        val at = i.getLongExtra("at", System.currentTimeMillis())
        val asleep = i.getBooleanExtra("asleep", false)
        // The state can land before the first off-body reading does — observed
        // half a second apart — and "worn?" is unanswerable until it has. Hold
        // the transition rather than judging it against a default.
        if (asleep && wrist.worn == null) { pendingAsleep = at; return }
        applySleep(asleep, at)
    }

    private fun applySleep(asleep: Boolean, at: Long) {
        if (sleep.apply(asleep, at, wrist.worn == true, wrist.wornSince)) send(sleep.json())
    }

    // ---- live heart rate ----

    // The PPG is the one thing this app can hold open that costs real battery:
    // green LEDs and the analog front end, several mA for as long as it runs.
    // MeasureClient powers it only while registered, so it is registered only
    // while somebody is actually looking — the phone subscribed, the phone
    // asked, or the watch screen is up — and released on a deadline.
    private var liveOn = false
    private var liveUntil = 0L

    private val liveOff = Runnable { setLiveHr(false) }

    private fun liveHr(ms: Long) {
        val until = maxOf(liveUntil, System.currentTimeMillis() + ms)
        liveUntil = until
        setLiveHr(true)
        handler.removeCallbacks(liveOff)
        handler.postDelayed(liveOff, until - System.currentTimeMillis())
    }

    private val measureCb = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit

        override fun onDataReceived(data: DataPointContainer) {
            data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let { publishHr(it.value.toInt()) }
        }
    }

    private fun setLiveHr(on: Boolean) {
        if (on == liveOn) return
        liveOn = on
        val client = HealthServices.getClient(this).measureClient
        runCatching {
            if (on) client.registerMeasureCallback(DataType.HEART_RATE_BPM, measureCb)
            else client.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCb)
        }.onFailure { Log.w(TAG, "live hr $on", it); liveOn = false }
    }

    // ---- tray ----

    private var ancs: AncsClient? = null
    private var activeCallUid: String? = null

    private fun startAncs() {
        ancs = AncsClient(
            this,
            onNotif = { app, title, body, uidHex, cat ->
                if (cat == 1) {
                    activeCallUid = uidHex
                    tray.showCall(title, uidHex)
                } else {
                    AppNames.resolve(app).let { tray.showNotify(title, body, it.name, it.icon, uidHex) }
                }
            },
            onRemoved = { uidHex ->
                if (uidHex == activeCallUid) { activeCallUid = null; tray.cancelCall() }
                tray.cancelFor(uidHex)
            },
        ).also { runCatching { it.start() } }
    }

    // ---- what the link reports ----

    private fun onSubscribed() {
        phase(LinkPhase.SUBSCRIBED)
        advertiser.off()
        Log.d(TAG, "subscribed, adv off")
        // Only a reading that is still current. Replaying a stale one on every
        // reconnect would have the phone stamp an old beat with the present
        // time and file it in Apple Health as a reading that never happened.
        if (lastHr > 0 && System.currentTimeMillis() - lastHrAt < HR_FRESH_MS) {
            send("""{"t":"hr","bpm":$lastHr}""")
        }
        if (sleep.hasSession) send(sleep.json())
        drainDeltas()
        liveHr(LIVE_HR_MS)
        scheduleDayPush()
    }

    private fun onIdle() {
        phase(LinkPhase.RESTING)
        // Nobody is listening, so anything still in flight was never delivered.
        // The link only reports a discard when it actually held chunks, and a
        // disconnect between the subscriber check and the queueing leaves none.
        deltas.returned()
        advertiser.burst()
    }

    private fun handlePhoneMsg(msg: String) {
        val obj = runCatching { JSONObject(msg) }.getOrNull() ?: return
        when (obj.optString("t")) {
            "notify" -> tray.showNotify(obj.optString("title").ifEmpty { "iPhone" }, obj.optString("body"))
            "goal" -> obj.optInt("steps", 0).takeIf { it > 0 }?.let { settings.stepGoal = it }
        }
        // Any inbound message doubles as a sync request: the phone asks by
        // writing, and gets the current day snapshot back — and somebody
        // writing is somebody looking, which is worth a live heart rate.
        if (sleep.hasSession) send(sleep.json())
        liveHr(LIVE_HR_MS)
        scheduleDayPush()
    }

    private fun send(msg: String) = link.send(msg)

    // ---- advertising duty cycle ----

    // Gate on OUR subscribers, never on raw connections: after iPhone pairing
    // the system holds an ANCS/HFP link permanently, which would otherwise
    // suppress discovery of our service forever.
    private val burstLoop = object : Runnable {
        override fun run() {
            if (!link.hasSubscribers) advertiser.burst()
            handler.postDelayed(this, Advertiser.BURST_MS + Advertiser.REST_MS)
        }
    }

}
