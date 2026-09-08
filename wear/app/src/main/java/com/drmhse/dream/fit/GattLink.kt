package com.drmhse.dream.fit

import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.Collections
import java.util.UUID

// Bytes across the link, and nothing else: the GATT server, who is subscribed,
// reassembly in, flow-controlled notification out. It knows nothing about days,
// heart rates or advertising — it reports that somebody subscribed, that the
// last one left, and that a line arrived.
class GattLink(
    private val ctx: Context,
    private val onSubscribed: () -> Unit,
    private val onIdle: () -> Unit,
    private val onMessage: (String) -> Unit,
    // Everything queued has gone out, and everything queued was discarded.
    // Anything that cannot be reconstructed later has to know which happened,
    // because handing a message to this class is not the same as delivering it.
    private val onFlushed: () -> Unit = {},
    private val onDropped: () -> Unit = {},
) {
    private companion object {
        const val RX_MAX = 4_096
        const val TX_QUEUE_MAX = 512
    }

    private var gattServer: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private val notifyOn: MutableSet<BluetoothDevice> = Collections.synchronizedSet(mutableSetOf())
    private val mtus = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val rxBuf = java.util.concurrent.ConcurrentHashMap<String, StringBuilder>()

    val hasSubscribers get() = notifyOn.isNotEmpty()

    fun start(mgr: BluetoothManager) {
        gattServer = mgr.openGattServer(ctx, serverCallback)
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

    fun close() = runCatching { gattServer?.close() }.let { }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(dev: BluetoothDevice?, status: Int, newState: Int) {
            if (newState != BluetoothProfile.STATE_DISCONNECTED || dev == null) return
            notifyOn.remove(dev)
            val had = synchronized(txLock) {
                val n = txQueue.count { it.first == dev }
                txQueue.removeAll { it.first == dev }
                n
            }
            if (had > 0) onDropped()
            mtus.remove(dev.address)
            rxBuf.remove(dev.address)
            if (notifyOn.isEmpty()) onIdle()
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
                onSubscribed()
            } else {
                notifyOn.remove(dev)
                if (notifyOn.isEmpty()) onIdle()
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
            if (buf.length > RX_MAX) { Log.w(BridgeService.TAG, "rx overflow, reset"); buf.setLength(0); return }
            while (true) {
                val nl = buf.indexOf("\n")
                if (nl < 0) break
                val line = buf.substring(0, nl).trim()
                buf.delete(0, nl + 1)
                if (line.isNotEmpty()) onMessage(line)
            }
        }

        override fun onNotificationSent(dev: BluetoothDevice?, status: Int) {
            val drained = synchronized(txLock) { txBusy = false; txQueue.isEmpty() }
            if (drained && status == BluetoothGatt.GATT_SUCCESS) onFlushed()
            pump()
        }
    }

    // ---- TX: one chunk in flight, next only after onNotificationSent ----

    private val txLock = Any()
    private val txQueue = ArrayDeque<Pair<BluetoothDevice, ByteArray>>()
    private var txBusy = false

    fun send(msg: String) {
        val bytes = (msg + "\n").toByteArray(Charsets.UTF_8)
        val targets = synchronized(notifyOn) { notifyOn.toList() }
        if (targets.isEmpty()) return
        // On overflow the whole backlog goes and this message goes with it, so
        // that `onDropped` means exactly what the queue needs it to mean:
        // nothing outstanding was delivered. Queueing this message after
        // clearing would have delivered it and reported it discarded at once,
        // leaving the sender to send it again.
        val overflowed = synchronized(txLock) {
            if (txQueue.size > TX_QUEUE_MAX) {
                Log.w(BridgeService.TAG, "tx overflow, backlog and this message dropped")
                txQueue.clear()
                true
            } else {
                for (d in targets) {
                    val cap = (mtus[d.address] ?: 23) - 3
                    var i = 0
                    while (i < bytes.size) {
                        val end = minOf(i + cap, bytes.size)
                        txQueue.addLast(d to bytes.copyOfRange(i, end))
                        i = end
                    }
                }
                false
            }
        }
        if (overflowed) { onDropped(); return }
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
            Log.w(BridgeService.TAG, "notify failed, backlog dropped")
            onDropped()
        }
    }
}
