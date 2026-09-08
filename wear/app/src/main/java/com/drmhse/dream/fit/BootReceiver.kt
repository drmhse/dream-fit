package com.drmhse.dream.fit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// START_STICKY restarts a service whose process died; it does nothing across a
// reboot. Without this the bridge stays down until the app is opened by hand,
// and a watch that reboots overnight collects nothing until it is noticed.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(BridgeService.TAG, "boot completed, starting the bridge")
        context.startForegroundService(
            Intent(context, BridgeService::class.java).setAction(BridgeService.BOOT),
        )
    }
}
