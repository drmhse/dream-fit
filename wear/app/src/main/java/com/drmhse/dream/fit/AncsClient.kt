package com.drmhse.dream.fit

import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

// After the watch is paired in iPhone Settings > Bluetooth, iOS exposes the
// Apple Notification Center Service to the bonded accessory. Subscribe to
// Notification Source + Data Source, fetch attributes per event, forward to
// the watch tray. No iOS app involvement.
class AncsClient(
    private val ctx: Context,
    private val onNotif: (app: String, title: String, body: String, uidHex: String, cat: Int) -> Unit,
    private val onRemoved: (uidHex: String) -> Unit,
) {
    companion object {
        private const val TAG = "DreamFit-Ancs"
        private const val ATTR_MAX = 4_096
        // AppIdentifier, Title, Subtitle, Message — exactly what fetchAttributes
        // asks for, and the only way to know where one response ends.
        private const val ATTR_COUNT = 4
        private val SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
        private val NOTIF_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        private val CONTROL: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
        private val DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
        private val CCCD: UUID = UUID.fromString(BridgeGatt.CCCD)
    }

    private var gatt: BluetoothGatt? = null
    private val lock = Any()
    private val seenUids = ArrayDeque<String>()
    private val pendingCat = mutableMapOf<String, Int>()
    private val descQueue = ArrayDeque<Pair<BluetoothGatt, BluetoothGattDescriptor>>()
    private val attrBuf = ArrayDeque<Byte>()

    fun start() {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val iphone = runCatching { mgr.adapter?.bondedDevices }
            .onFailure { Log.w(TAG, "bond perm", it) }
            .getOrNull()
            ?.firstOrNull { it.name?.contains("iPhone", true) == true }
            ?: run { Log.d(TAG, "no bonded iPhone"); return }
        runCatching { gatt = iphone.connectGatt(ctx, true, cb, BluetoothDevice.TRANSPORT_LE) }
            .onFailure { Log.w(TAG, "connect perm", it) }
    }

    fun stop() {
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
    }

    // CategoryID 1 is an incoming call; PerformNotificationAction answers or declines it.
    fun performAction(uidHex: String, positive: Boolean) {
        val g = gatt ?: return
        val ctrl = g.getService(SERVICE)?.getCharacteristic(CONTROL) ?: return
        val uid = uidHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        write(g, ctrl, byteArrayOf(2) + uid + byteArrayOf(if (positive) 0 else 1))
    }

    private fun write(g: BluetoothGatt, ch: BluetoothGattCharacteristic, v: ByteArray) {
        runCatching { g.writeCharacteristic(ch, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
            .onFailure { Log.w(TAG, "write", it) }
    }

    // iOS replays the whole tray on subscribe: fetch each UID once.
    private fun firstSighting(uid: String): Boolean = synchronized(lock) {
        if (seenUids.contains(uid)) return false
        seenUids.addLast(uid)
        while (seenUids.size > 64) seenUids.removeFirst()
        true
    }

    // CCCD writes must be serialised: one in flight at a time.
    private fun subscribe(g: BluetoothGatt, uuid: UUID) {
        val ch = g.getService(SERVICE)?.getCharacteristic(uuid) ?: run { Log.w(TAG, "$uuid missing"); return }
        runCatching {
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(CCCD) ?: return
            val first = synchronized(lock) { descQueue.addLast(g to desc); descQueue.size == 1 }
            if (first) flushDescQueue()
        }.onFailure { Log.w(TAG, "subscribe $uuid", it) }
    }

    private fun flushDescQueue() {
        val (g, d) = synchronized(lock) { descQueue.firstOrNull() } ?: return
        runCatching { g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
            .onFailure {
                Log.w(TAG, "cccd", it)
                synchronized(lock) { descQueue.removeFirstOrNull() }
            }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                runCatching { g?.discoverServices() }.onFailure { Log.w(TAG, "discover", it) }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(lock) { descQueue.clear(); attrBuf.clear() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            if (g == null || status != BluetoothGatt.GATT_SUCCESS) return
            if (g.getService(SERVICE) == null) { Log.d(TAG, "no ANCS"); g.disconnect(); return }
            subscribe(g, NOTIF_SOURCE)
            subscribe(g, DATA_SOURCE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt?, d: BluetoothGattDescriptor?, status: Int) {
            synchronized(lock) { descQueue.removeFirstOrNull() }
            flushDescQueue()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
            when (c.uuid) {
                NOTIF_SOURCE -> onSourceEvent(g, v)
                DATA_SOURCE -> onAttributeData(v)
            }
        }
    }

    private fun onSourceEvent(g: BluetoothGatt, v: ByteArray) {
        if (v.size < 8) return
        val uid = v.sliceArray(4..7).toHex()
        when (v[0].toInt()) {
            0 -> { // added
                if (!firstSighting(uid)) return
                synchronized(lock) { pendingCat[uid] = v[2].toInt() and 0xFF }
                fetchAttributes(g, v.sliceArray(4..7))
            }
            1 -> onRemoved(uid) // removed — this is what ends a call notification
            // 2 = modified: iOS re-delivers it as an add; nothing to do
        }
    }

    // AppIdentifier(0, no length) + Title(1, 64) + Subtitle(2, 128) + Message(3, 512).
    private fun fetchAttributes(g: BluetoothGatt, uid: ByteArray) {
        val ctrl = g.getService(SERVICE)?.getCharacteristic(CONTROL) ?: return
        write(g, ctrl, byteArrayOf(0) + uid + byteArrayOf(0, 1, 64, 0, 2, 128.toByte(), 0, 3, 0, 2))
    }

    // A response runs ~600B across ~30 notifies on an MTU-23 link. Buffer until
    // one parses whole; a dropped fragment resets rather than wedging forever.
    private fun onAttributeData(v: ByteArray) {
        val done = synchronized(lock) {
            v.forEach { attrBuf.addLast(it) }
            if (attrBuf.size > ATTR_MAX) { Log.w(TAG, "attr overflow, reset"); attrBuf.clear(); return }
            val parsed = parse(attrBuf.toByteArray()) ?: return
            repeat(parsed.consumed) { attrBuf.removeFirst() }
            parsed
        }
        emit(done)
    }

    private class Attrs(
        val consumed: Int,
        val uid: String = "",
        val app: String = "",
        val title: String = "",
        val sub: String = "",
        val msg: String = "",
    )

    // Null while the response is still incomplete.
    // CommandID(1)=0, UID(4), then repeating AttrID(1) Len(2 LE) Value.
    //
    // Stops after the four attributes that were requested rather than when the
    // buffer runs out. iOS replays its whole tray on subscribe, so responses
    // arrive back to back: reading to the end of the buffer consumed the next
    // response's header as an attribute of this one, desynchronised the stream
    // and left every following notification unparseable — the `bad cmd 3` /
    // `bad cmd 102` warnings in logcat after a reconnect. Trailing bytes now
    // stay in the buffer for the response they belong to.
    private fun parse(v: ByteArray): Attrs? {
        if (v.size < 5) return null
        if (v[0] != 0.toByte()) { Log.w(TAG, "bad cmd ${v[0]}, resyncing"); return Attrs(v.size) }
        var i = 5
        var app = ""; var title = ""; var sub = ""; var msg = ""
        repeat(ATTR_COUNT) {
            if (i + 3 > v.size) return null
            val id = v[i].toInt() and 0xFF
            val len = ByteBuffer.wrap(v, i + 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            if (i + 3 + len > v.size) return null
            val str = String(v, i + 3, len, Charsets.UTF_8)
            when (id) { 0 -> app = str; 1 -> title = str; 2 -> sub = str; 3 -> msg = str }
            i += 3 + len
        }
        return Attrs(i, v.sliceArray(1..4).toHex(), app, title, sub, msg)
    }

    private fun emit(a: Attrs) {
        if (a.title.isEmpty() && a.msg.isEmpty() && a.sub.isEmpty()) return
        val cat = synchronized(lock) { pendingCat.remove(a.uid) } ?: -1
        Log.d(TAG, "ancs [${a.app}] ${a.title} cat=$cat")
        onNotif(a.app, a.title.ifEmpty { a.sub.ifEmpty { "iPhone" } }, a.msg.ifEmpty { a.sub }, a.uid, cat)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
