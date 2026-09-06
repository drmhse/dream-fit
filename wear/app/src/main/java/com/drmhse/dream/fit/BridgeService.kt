package com.drmhse.dream.fit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

class BridgeService : Service(), SensorEventListener {
    companion object {
        const val TAG = "DreamFit"
        const val CALL_ID = 200
        private const val ADV_BURST_MS = 30_000L
        private const val ADV_REST_MS = 300_000L
        private const val DAY_HEARTBEAT_MS = 900_000L
        private const val DAY_DEBOUNCE_MS = 1_000L
        private const val HR_THROTTLE_MS = 5_000L
        private const val STEP_PUSH_MS = 60_000L
        private const val RX_MAX = 4_096
        private const val TX_QUEUE_MAX = 512
        private const val EX_BPM = 110
        private const val RHR_WINDOW_MS = 600_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("dreamfit", MODE_PRIVATE) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        loadDay()
        startForeground(1, fgNotification())
        WatchState.update { it.copy(running = true) }
        val mgr = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        advertiser = mgr.adapter?.bluetoothLeAdvertiser
        startGatt(mgr)
        handler.post(burstLoop)
        handler.postDelayed(heartbeat, DAY_HEARTBEAT_MS)
        startSensors()
        registerPassive()
        ancs = AncsClient(
            this,
            onNotif = { app, title, body, uidHex, cat ->
                if (cat == 1) { activeCallUid = uidHex; showCall(title, uidHex) }
                else AppNames.resolve(app).let { showNotify(title, body, it.name, it.icon, uidHex) }
            },
            onRemoved = { uidHex ->
                if (uidHex == activeCallUid) { activeCallUid = null; cancelCall() }
                notifIds.remove(uidHex)?.let { nm().cancel(it) }
            },
        ).also { runCatching { it.start() } }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        when (i?.action) {
            "dreamfit.ANSWER" -> { ancs?.performAction(i.getStringExtra("uid").orEmpty(), true); cancelCall() }
            "dreamfit.DECLINE" -> { ancs?.performAction(i.getStringExtra("uid").orEmpty(), false); cancelCall() }
            "dreamfit.DAILY" -> onDailySteps(i.getIntExtra("total", -1))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        WatchState.update { it.copy(running = false, linked = false) }
        runCatching { ancs?.stop() }
        handler.removeCallbacksAndMessages(null)
        runCatching { sensorMgr?.unregisterListener(this) }
        stopAdv()
        runCatching { gattServer?.close() }
        super.onDestroy()
    }

    // ---- day record: the watch is the sole authority for daily aggregates ----
    // One prefs key, reset on rollover. Steps are monotonic (Health Services
    // replays stale daily snapshots out of order); RHR is the day's minimum.

    private var dayDate = ""
    private var daySteps = 0
    private var dayRhr = 0
    private var dayExMin = 0

    private fun loadDay() {
        val parts = prefs.getString("day", "")?.split("|").orEmpty()
        if (parts.size == 4) {
            dayDate = parts[0]
            daySteps = parts[1].toIntOrNull() ?: 0
            dayRhr = parts[2].toIntOrNull() ?: 0
            dayExMin = parts[3].toIntOrNull() ?: 0
        }
        rollDay()
        WatchState.update { it.copy(steps = daySteps, exMin = dayExMin) }
    }

    private fun rollDay() {
        val today = LocalDate.now().toString()
        if (dayDate == today) return
        dayDate = today; daySteps = 0; dayRhr = 0; dayExMin = 0
        stepBase = 0; stepAnchor = lastCounter
        saveDay()
    }

    private fun saveDay() {
        prefs.edit().putString("day", "$dayDate|$daySteps|$dayRhr|$dayExMin").apply()
        WatchState.update { it.copy(steps = daySteps, exMin = dayExMin) }
    }

    private fun onDailySteps(total: Int) {
        if (total < 0) return
        rollDay()
        // The aggregate is the authority, but Health Services replays stale
        // daily snapshots out of order, so it may only ever raise the total.
        if (total > daySteps) daySteps = total
        // Re-anchor either way. The projection below always continues from the
        // reported total, so steps the aggregate already counted are never
        // added a second time — which is what broke live deltas before.
        stepBase = daySteps
        stepAnchor = lastCounter
        saveDay()
        scheduleDayPush()
    }

    private val dayPush = Runnable {
        send("""{"t":"day","d":"$dayDate","steps":$daySteps,"rhr":$dayRhr,"exmin":$dayExMin,"bat":${batteryPct()}}""")
    }

    private fun scheduleDayPush() {
        handler.removeCallbacks(dayPush)
        handler.postDelayed(dayPush, DAY_DEBOUNCE_MS)
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            rollDay()
            scheduleDayPush()
            handler.postDelayed(this, DAY_HEARTBEAT_MS)
        }
    }

    private fun batteryPct() =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    // ---- sensors ----

    private var sensorMgr: SensorManager? = null
    private var lastHr = 0
    private var lastHrPush = 0L
    private val hrWindow = ArrayDeque<Pair<Long, Int>>()
    private var lastExMark = 0L

    // Health Services batches STEPS_DAILY to save power, so the aggregate can
    // trail the watch's own summary by minutes. The hardware step counter fills
    // that gap between aggregates; it is a projection, never an authority.
    private var stepAnchor: Float? = null
    private var stepBase = 0
    private var lastCounter: Float? = null
    private var lastStepPush = 0L

    private fun startSensors() {
        sensorMgr = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        register(Sensor.TYPE_HEART_RATE, "heart rate")
        register(Sensor.TYPE_STEP_COUNTER, "step counter")
    }

    private fun register(type: Int, label: String) {
        val sensor = sensorMgr?.getDefaultSensor(type) ?: run { Log.w(TAG, "no $label sensor"); return }
        runCatching { sensorMgr?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
            .onFailure { Log.w(TAG, "register $label", it) }
    }

    override fun onSensorChanged(e: SensorEvent?) {
        when (e?.sensor?.type) {
            Sensor.TYPE_HEART_RATE -> onHeartRate(e)
            Sensor.TYPE_STEP_COUNTER -> onStepCounter(e)
        }
    }

    private fun onStepCounter(e: SensorEvent) {
        val counter = e.values.getOrNull(0) ?: return
        lastCounter = counter
        rollDay()
        val anchor = stepAnchor
        // First reading, or the counter reset on reboot: anchor, never project.
        if (anchor == null || counter < anchor) {
            stepAnchor = counter
            stepBase = daySteps
            return
        }
        val projected = stepBase + (counter - anchor).toInt()
        if (projected <= daySteps) return
        daySteps = projected
        saveDay()
        // The watch face updates instantly (in-process); the radio does not.
        val now = System.currentTimeMillis()
        if (now - lastStepPush >= STEP_PUSH_MS) {
            lastStepPush = now
            scheduleDayPush()
        }
    }

    private fun onHeartRate(e: SensorEvent) {
        val bpm = e.values.getOrNull(0)?.toInt() ?: return
        if (bpm <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastHrPush < HR_THROTTLE_MS) return
        lastHrPush = now
        lastHr = bpm
        send("""{"t":"hr","bpm":$bpm}""")
        WatchState.update { it.copy(bpm = bpm) }
        deriveDaily(now, bpm)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit

    // Resting HR = the day's lowest 10-minute-window minimum. Exercise minutes
    // accrue one per minute spent above EX_BPM (crude zone gate, no weight model).
    private fun deriveDaily(now: Long, bpm: Int) {
        rollDay()
        var changed = false
        hrWindow.addLast(now to bpm)
        while (hrWindow.isNotEmpty() && now - hrWindow.first().first > RHR_WINDOW_MS) hrWindow.removeFirst()
        if (hrWindow.size >= 12) {
            val rhr = hrWindow.minOf { it.second }
            if (dayRhr == 0 || rhr < dayRhr) { dayRhr = rhr; changed = true }
        }
        if (bpm >= EX_BPM && now - lastExMark > 60_000) {
            lastExMark = now; dayExMin++; changed = true
        }
        if (changed) { saveDay(); scheduleDayPush() }
    }

    private fun registerPassive() {
        runCatching {
            val config = PassiveListenerConfig.builder()
                .setDataTypes(setOf(DataType.STEPS_DAILY)).build()
            HealthServices.getClient(this).passiveMonitoringClient
                .setPassiveListenerServiceAsync(PassiveDataService::class.java, config)
        }.onFailure { Log.w(TAG, "passive", it) }
    }

    // ---- advertising ----

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advCallback: AdvertiseCallback? = null
    private var advRunning = false

    private val advStop = Runnable { stopAdv() }

    // Gate on OUR subscribers, never on raw connections: after iPhone pairing
    // the system holds an ANCS/HFP link permanently, which would otherwise
    // suppress discovery of our service forever.
    private val burstLoop = object : Runnable {
        override fun run() {
            if (notifyOn.isEmpty()) burstNow()
            handler.postDelayed(this, ADV_BURST_MS + ADV_REST_MS)
        }
    }

    private fun burstNow() = handler.post {
        startAdv()
        handler.removeCallbacks(advStop)
        handler.postDelayed(advStop, ADV_BURST_MS)
    }

    private fun advOff() = handler.post {
        handler.removeCallbacks(advStop)
        stopAdv()
    }

    private fun startAdv() {
        if (advRunning) return
        val adv = advertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(BridgeGatt.SERVICE)))
            .setIncludeDeviceName(false).build()
        val scanResp = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings?) { advRunning = true; Log.d(TAG, "adv on") }
            override fun onStartFailure(e: Int) { advRunning = false; Log.w(TAG, "adv fail $e") }
        }
        runCatching { adv.startAdvertising(settings, data, scanResp, advCallback) }
            .onFailure { Log.w(TAG, "adv start", it) }
    }

    private fun stopAdv() {
        runCatching { advCallback?.let { advertiser?.stopAdvertising(it) } }
        advRunning = false
    }

    // ---- GATT server ----

    private var gattServer: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private val notifyOn: MutableSet<BluetoothDevice> = Collections.synchronizedSet(mutableSetOf())
    private val mtus = ConcurrentHashMap<String, Int>()
    private val rxBuf = ConcurrentHashMap<String, StringBuilder>()

    private fun startGatt(mgr: BluetoothManager) {
        gattServer = mgr.openGattServer(this, serverCallback)
        val svc = BluetoothGattService(UUID.fromString(BridgeGatt.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                UUID.fromString(BridgeGatt.RX),
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        txChar = BluetoothGattCharacteristic(
            UUID.fromString(BridgeGatt.TX), BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    UUID.fromString(BridgeGatt.CCCD),
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        svc.addCharacteristic(txChar)
        gattServer?.addService(svc)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(dev: BluetoothDevice?, status: Int, newState: Int) {
            if (newState != BluetoothProfile.STATE_DISCONNECTED || dev == null) return
            notifyOn.remove(dev)
            WatchState.update { it.copy(linked = notifyOn.isNotEmpty()) }
            synchronized(txLock) { txQueue.removeAll { it.first == dev } }
            mtus.remove(dev.address)
            rxBuf.remove(dev.address)
            if (notifyOn.isEmpty()) burstNow()
        }

        override fun onMtuChanged(dev: BluetoothDevice?, mtu: Int) {
            dev?.let { mtus[it.address] = mtu }
        }

        override fun onDescriptorWriteRequest(
            dev: BluetoothDevice?, reqId: Int, desc: BluetoothGattDescriptor?,
            prep: Boolean, resp: Boolean, off: Int, value: ByteArray?,
        ) {
            if (resp) gattServer?.sendResponse(dev, reqId, BluetoothGatt.GATT_SUCCESS, off, value)
            if (dev == null) return
            if (value?.any { it != 0.toByte() } == true) {
                notifyOn.add(dev)
                WatchState.update { it.copy(linked = true) }
                advOff()
                Log.d(TAG, "subscribed, adv off")
                if (lastHr > 0) send("""{"t":"hr","bpm":$lastHr}""")
                scheduleDayPush()
            } else {
                notifyOn.remove(dev)
                WatchState.update { it.copy(linked = notifyOn.isNotEmpty()) }
            }
        }

        override fun onCharacteristicWriteRequest(
            dev: BluetoothDevice?, reqId: Int, ch: BluetoothGattCharacteristic?,
            prep: Boolean, resp: Boolean, off: Int, value: ByteArray?,
        ) {
            if (resp) gattServer?.sendResponse(dev, reqId, BluetoothGatt.GATT_SUCCESS, off, value)
            val key = dev?.address ?: return
            val buf = rxBuf.computeIfAbsent(key) { StringBuilder() }
            buf.append(value?.toString(Charsets.UTF_8).orEmpty())
            // A dropped fragment must not wedge the channel forever.
            if (buf.length > RX_MAX) { Log.w(TAG, "rx overflow, reset"); buf.setLength(0); return }
            while (true) {
                val nl = buf.indexOf("\n")
                if (nl < 0) break
                val line = buf.substring(0, nl).trim()
                buf.delete(0, nl + 1)
                if (line.isNotEmpty()) handlePhoneMsg(line)
            }
        }

        override fun onNotificationSent(dev: BluetoothDevice?, status: Int) {
            synchronized(txLock) { txBusy = false }
            pump()
        }
    }

    private fun handlePhoneMsg(msg: String) {
        val obj = runCatching { JSONObject(msg) }.getOrNull() ?: return
        if (obj.optString("t") == "notify") {
            showNotify(obj.optString("title").ifEmpty { "iPhone" }, obj.optString("body"))
        }
        // Any inbound message doubles as a sync request: the phone asks by
        // writing, and gets the current day snapshot back.
        scheduleDayPush()
    }

    // ---- TX: one chunk in flight, next only after onNotificationSent ----

    private val txLock = Any()
    private val txQueue = ArrayDeque<Pair<BluetoothDevice, ByteArray>>()
    private var txBusy = false

    private fun send(msg: String) {
        val bytes = (msg + "\n").toByteArray(Charsets.UTF_8)
        val targets = synchronized(notifyOn) { notifyOn.toList() }
        if (targets.isEmpty()) return
        synchronized(txLock) {
            if (txQueue.size > TX_QUEUE_MAX) { Log.w(TAG, "tx overflow, dropped"); txQueue.clear() }
            for (d in targets) {
                val cap = (mtus[d.address] ?: 23) - 3
                var i = 0
                while (i < bytes.size) {
                    val end = minOf(i + cap, bytes.size)
                    txQueue.addLast(d to bytes.copyOfRange(i, end))
                    i = end
                }
            }
        }
        pump()
    }

    private fun pump() {
        val next = synchronized(txLock) {
            if (txBusy) return
            val n = txQueue.removeFirstOrNull() ?: return
            txBusy = true
            n
        }
        val (dev, chunk) = next
        val tx = txChar
        val ok = tx != null && runCatching {
            gattServer?.notifyCharacteristicChanged(dev, tx, false, chunk) == BluetoothStatusCodes.SUCCESS
        }.getOrDefault(false)
        if (!ok) {
            // No onNotificationSent will arrive: drop this device's backlog
            // rather than stalling every other subscriber behind it.
            synchronized(txLock) { txBusy = false; txQueue.removeAll { it.first == dev } }
            Log.w(TAG, "notify failed, backlog dropped")
        }
    }

    // ---- watch tray ----

    private var ancs: AncsClient? = null
    private var activeCallUid: String? = null
    private var notifId = 100
    private val notifIds = mutableMapOf<String, Int>()
    private val groupCount = mutableMapOf<String, Int>()

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun channel(id: String, name: String, importance: Int) {
        nm().createNotificationChannel(NotificationChannel(id, name, importance))
    }

    private fun fgNotification(): Notification {
        channel("bridge", "Dream Fit bridge", NotificationManager.IMPORTANCE_MIN)
        return Notification.Builder(this, "bridge")
            .setContentTitle(getString(R.string.app_name))
            .setContentText("iPhone link active")
            .setSmallIcon(R.drawable.ic_stat_dreamfit)
            .build()
    }

    // The tray header always shows the posting app (only the system bridge can
    // stamp another package's identity, and that needs GMS), so the source app
    // goes in the title line itself.
    private fun showNotify(
        title: String,
        body: String,
        app: String = "iPhone",
        icon: Int = R.drawable.ic_stat_dreamfit,
        uidHex: String? = null,
    ) {
        channel("phone", "iPhone alerts", NotificationManager.IMPORTANCE_HIGH)
        val color = when (app) {
            "WhatsApp", "Messages", "Phone" -> 0xFF34C759.toInt()
            "Gmail", "Mail" -> 0xFFEA4335.toInt()
            else -> 0xFF0A84FF.toInt()
        }
        val group = "app:$app"
        val id = notifId++
        uidHex?.let { notifIds[it] = id }
        nm().notify(
            id,
            Notification.Builder(this, "phone")
                .setContentTitle("$app • $title").setContentText(body)
                .setSmallIcon(icon).setColor(color).setColorized(true)
                .setGroup(group).build(),
        )
        val count = groupCount.merge(group, 1) { a, b -> a + b } ?: 1
        nm().notify(
            group.hashCode(),
            Notification.Builder(this, "phone")
                .setContentTitle(app).setContentText("$count new")
                .setSmallIcon(icon).setColor(color).setColorized(true)
                .setGroup(group).setGroupSummary(true).build(),
        )
    }

    private fun showCall(caller: String, uidHex: String) {
        channel("calls", "Phone calls", NotificationManager.IMPORTANCE_HIGH)
        fun act(action: String, offset: Int) = PendingIntent.getService(
            this, uidHex.hashCode() + offset,
            Intent(this, BridgeService::class.java).setAction(action).putExtra("uid", uidHex),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm().notify(
            CALL_ID,
            Notification.Builder(this, "calls")
                .setContentTitle("Incoming call").setContentText(caller)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setCategory(Notification.CATEGORY_CALL)
                .addAction(Notification.Action.Builder(null, "Answer", act("dreamfit.ANSWER", 0)).build())
                .addAction(Notification.Action.Builder(null, "Decline", act("dreamfit.DECLINE", 1)).build())
                .setOngoing(true).build(),
        )
    }

    private fun cancelCall() = nm().cancel(CALL_ID)
}
