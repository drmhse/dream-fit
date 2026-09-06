package com.drmhse.dream.fit

import android.app.Service
import android.bluetooth.*
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
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

// The link itself: sensors in, GATT out, and the phone's requests back the
// other way. The day's numbers belong to DayLog, the tray to Tray and the
// radio's duty cycle to Advertiser.
class BridgeService : Service(), SensorEventListener {
    companion object {
        const val TAG = "DreamFit"
        const val ANSWER = "dreamfit.ANSWER"
        const val DECLINE = "dreamfit.DECLINE"
        const val DAILY = "dreamfit.DAILY"
        private const val DAY_HEARTBEAT_MS = 900_000L
        private const val DAY_DEBOUNCE_MS = 1_000L
        private const val HR_THROTTLE_MS = 5_000L
        private const val STEP_PUSH_MS = 60_000L
        private const val RX_MAX = 4_096
        private const val TX_QUEUE_MAX = 512
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var day: DayLog
    private lateinit var settings: Settings
    private lateinit var tray: Tray
    private lateinit var advertiser: Advertiser

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        day = DayLog(this)
        settings = Settings(this)
        tray = Tray(this)
        // The radio reports its own duty cycle, so ADVERTISING and RESTING are
        // observed rather than assumed. A subscriber outranks both.
        advertiser = Advertiser(this, handler) { on ->
            if (notifyOn.isEmpty()) phase(if (on) LinkPhase.ADVERTISING else LinkPhase.RESTING)
        }
        startForeground(Tray.FOREGROUND_ID, tray.foregroundNotification())
        phase(LinkPhase.STARTING)
        startGatt(getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
        handler.post(burstLoop)
        handler.postDelayed(heartbeat, DAY_HEARTBEAT_MS)
        startSensors()
        registerPassive()
        startAncs()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        when (i?.action) {
            ANSWER -> { ancs?.performAction(i.getStringExtra("uid").orEmpty(), true); tray.cancelCall() }
            DECLINE -> { ancs?.performAction(i.getStringExtra("uid").orEmpty(), false); tray.cancelCall() }
            DAILY -> if (day.applyDailyTotal(i.getIntExtra("total", -1))) scheduleDayPush()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        phase(LinkPhase.STOPPED)
        day.flush()
        runCatching { ancs?.stop() }
        handler.removeCallbacksAndMessages(null)
        runCatching { sensorMgr?.unregisterListener(this) }
        advertiser.stop()
        runCatching { gattServer?.close() }
        super.onDestroy()
    }

    // ---- day pushes ----

    private val dayPush = Runnable {
        day.flush()
        send(day.json(batteryPct()))
    }

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

    // ---- sensors ----

    private var sensorMgr: SensorManager? = null
    private var lastHr = 0
    private var lastHrPush = 0L
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

    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit

    private fun onStepCounter(e: SensorEvent) {
        val counter = e.values.getOrNull(0) ?: return
        if (!day.applyCounter(counter)) return
        // The watch face updates instantly (in-process); the radio does not.
        val now = System.currentTimeMillis()
        if (now - lastStepPush < STEP_PUSH_MS) return
        lastStepPush = now
        scheduleDayPush()
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
        if (day.applyBpm(now, bpm)) scheduleDayPush()
    }

    private fun registerPassive() {
        runCatching {
            val config = PassiveListenerConfig.builder()
                .setDataTypes(setOf(DataType.STEPS_DAILY)).build()
            HealthServices.getClient(this).passiveMonitoringClient
                .setPassiveListenerServiceAsync(PassiveDataService::class.java, config)
        }.onFailure { Log.w(TAG, "passive", it) }
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

    // ---- advertising duty cycle ----

    // Gate on OUR subscribers, never on raw connections: after iPhone pairing
    // the system holds an ANCS/HFP link permanently, which would otherwise
    // suppress discovery of our service forever.
    private val burstLoop = object : Runnable {
        override fun run() {
            if (notifyOn.isEmpty()) advertiser.burst()
            handler.postDelayed(this, Advertiser.BURST_MS + Advertiser.REST_MS)
        }
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
        // Encrypted and MITM-protected, not plain. The stack refuses every read,
        // write and subscribe until the link is encrypted with the bond keys, so
        // heart rate and step counts never cross the air in the clear and nobody
        // in radio range can connect and read them.
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                UUID.fromString(BridgeGatt.RX),
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM,
            ),
        )
        txChar = BluetoothGattCharacteristic(
            UUID.fromString(BridgeGatt.TX), BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0,
        ).apply {
            // Subscribing is the gate for the whole notify stream: an encrypted
            // CCCD means the link is encrypted before a single beat is sent.
            addDescriptor(
                BluetoothGattDescriptor(
                    UUID.fromString(BridgeGatt.CCCD),
                    BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED_MITM
                        or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED_MITM,
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
            if (notifyOn.isEmpty()) phase(LinkPhase.RESTING)
            synchronized(txLock) { txQueue.removeAll { it.first == dev } }
            mtus.remove(dev.address)
            rxBuf.remove(dev.address)
            if (notifyOn.isEmpty()) advertiser.burst()
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
                phase(LinkPhase.SUBSCRIBED)
                advertiser.off()
                Log.d(TAG, "subscribed, adv off")
                if (lastHr > 0) send("""{"t":"hr","bpm":$lastHr}""")
                scheduleDayPush()
            } else {
                notifyOn.remove(dev)
                if (notifyOn.isEmpty()) phase(LinkPhase.RESTING)
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
        when (obj.optString("t")) {
            "notify" -> tray.showNotify(obj.optString("title").ifEmpty { "iPhone" }, obj.optString("body"))
            "goal" -> obj.optInt("steps", 0).takeIf { it > 0 }?.let { settings.stepGoal = it }
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

    // Without-response writes are silently discarded once the queue is full;
    // a partial message means the watch never sees a newline.
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
}
