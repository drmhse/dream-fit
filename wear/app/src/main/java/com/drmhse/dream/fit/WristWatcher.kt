package com.drmhse.dream.fit

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

// Is the watch being worn, and since when.
//
// The one raw sensor in the app, and the exception earns itself: Health
// Services does not report off-body, so this is no second source for a number
// we already have — and it has to be known, because a watch on a desk reports
// USER_ACTIVITY_ASLEEP. It is on-change rather than continuous, non-optical,
// and the system already holds several clients on it, so ours is shared and
// costs nothing. That is the opposite of the PPG, which we were the sole
// client of.
class WristWatcher(
    private val ctx: Context,
    private val onRemoved: () -> Unit,
    private val onWorn: () -> Unit,
) : SensorEventListener {
    private var sensorMgr: SensorManager? = null

    // Null until the sensor answers. "Not worn" and "not yet known" must not
    // read the same, or an unknown wrist silently discards a real night.
    var worn: Boolean? = null
        private set

    // Only a transition actually watched happen dates the wrist. The value an
    // on-change sensor hands over at registration says worn, not since when,
    // and dating a night from service start would truncate a real one.
    var wornSince = 0L
        private set

    fun start() {
        val mgr = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorMgr = mgr
        val sensor = mgr.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
            ?: run { Log.w(BridgeService.TAG, "no off-body sensor: sleep will not be recorded"); return }
        runCatching { mgr.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
            .onFailure { Log.w(BridgeService.TAG, "off-body register", it) }
    }

    fun stop() = runCatching { sensorMgr?.unregisterListener(this) }.let { }

    override fun onSensorChanged(e: SensorEvent?) {
        if (e?.sensor?.type != Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) return
        val now = (e.values.getOrNull(0) ?: 0f) > 0.5f
        if (now == worn) return
        val knew = worn
        worn = now
        Log.d(BridgeService.TAG, "on body=$now")
        if (!now) {
            wornSince = 0
            onRemoved()
        } else {
            wornSince = if (knew == false) System.currentTimeMillis() else 0
            onWorn()
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
}
