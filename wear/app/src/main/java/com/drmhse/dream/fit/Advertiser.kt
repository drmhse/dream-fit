package com.drmhse.dream.fit

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

// Advertising in 30s bursts every 5.5 minutes: a watch that shouts constantly is
// a watch that is flat by lunchtime.
//
// State is the live callback, never a "running" flag. The stack's verdict lands
// asynchronously, so a flag set in onStartSuccess is still false when the next
// burst asks — which starts a second advertisement whose callback nothing holds.
// stopAdvertising can only cancel the newest, and the leaked ones accumulate
// until the stack refuses to advertise at all.
class Advertiser(
    ctx: Context,
    private val handler: Handler,
    private val onAdvertising: (Boolean) -> Unit,
) {
    companion object {
        const val BURST_MS = 30_000L
        const val REST_MS = 300_000L
    }

    private val le = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
        .adapter?.bluetoothLeAdvertiser
    private var live: AdvertiseCallback? = null
    private var wanted = false
    private val stopSoon = Runnable { stop() }

    fun burst() = handler.post {
        start()
        handler.removeCallbacks(stopSoon)
        handler.postDelayed(stopSoon, BURST_MS)
    }

    fun off() = handler.post {
        handler.removeCallbacks(stopSoon)
        stop()
    }

    fun stop() {
        wanted = false
        live?.let { runCatching { le?.stopAdvertising(it) } }
        live = null
        onAdvertising(false)
    }

    private fun start() {
        wanted = true
        if (live != null) return
        val adv = le ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(BridgeGatt.SERVICE)))
            .setIncludeDeviceName(false).build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings?) {
                Log.d(BridgeService.TAG, "adv on")
                // A stop that arrived while the stack was still deciding.
                if (!wanted) stop() else onAdvertising(true)
            }

            override fun onStartFailure(e: Int) {
                Log.w(BridgeService.TAG, "adv fail $e")
                if (live === this) live = null
                onAdvertising(false)
            }
        }
        live = cb
        runCatching { adv.startAdvertising(settings, data, scanResponse, cb) }
            .onFailure { Log.w(BridgeService.TAG, "adv start", it); live = null }
    }
}
