package com.drmhse.dream.fit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {
    private companion object {
        val PERMS = arrayOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )

        // Asked strictly after BODY_SENSORS is held, and never in the same
        // request: the system ignores a batch containing both and grants
        // neither. It is also the one permission the bridge runs without —
        // degraded to steps only, rather than not at all.
        const val BACKGROUND_SENSORS = "android.permission.BODY_SENSORS_BACKGROUND"
    }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { granted -> granted }) ensureBackgroundSensors()
    }

    // Asked once because API 33+ / Wear OS 4 documents it as the gate for
    // reading sensors once the app is hidden. On this watch (API 37) the dialog
    // cannot grant it — the answer comes back as a plain denial, and the
    // permission is not offered in app settings either — yet passive delivery
    // continued regardless. So the request is kept for the platforms where it
    // is grantable, and nothing is concluded from the answer: only Health
    // Services saying it dropped us counts as evidence.
    private val askBackground =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Log.i(BridgeService.TAG, "no background body sensors; watching for a dropped registration")
            startBridge()
        }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent { DreamFitScreen() }
        ensurePerms()
    }

    override fun onResume() {
        super.onResume()
        ensurePerms()
    }

    // Every runtime permission the bridge needs, or it fails silently. Tracked
    // per permission, not as one flag: a permission added in a later version
    // has never been asked, and a blanket flag would read that as a permanent
    // denial and send the user to Settings instead of showing the sheet.
    private fun ensurePerms() {
        val need = PERMS.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) { ensureBackgroundSensors(); return }
        val prefs = getSharedPreferences("dreamfit", MODE_PRIVATE)
        val asked = prefs.getStringSet("permAsked", emptySet()).orEmpty()
        val permanent = need.all {
            it in asked && !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        prefs.edit().putStringSet("permAsked", asked + need).apply()
        if (permanent) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        } else {
            askPerms.launch(need.toTypedArray())
        }
    }

    // The screen being up is the one moment a stale bpm is visible, so it also
    // buys a live heart-rate window.
    // Health Services keeps the passive registration only while the app may
    // read body sensors in the background; on Android 13+ that is a separate,
    // second grant the user makes once.
    private fun ensureBackgroundSensors() {
        if (ActivityCompat.checkSelfPermission(this, BACKGROUND_SENSORS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startBridge()
            return
        }
        val prefs = getSharedPreferences("dreamfit", MODE_PRIVATE)
        if (!prefs.getBoolean("bgSensorsAsked", false)) {
            prefs.edit().putBoolean("bgSensorsAsked", true).apply()
            runCatching { askBackground.launch(BACKGROUND_SENSORS) }.onFailure { startBridge() }
            return
        }
        startBridge()
    }


    private fun startBridge() {
        runCatching {
            startForegroundService(
                Intent(this, BridgeService::class.java).setAction(BridgeService.WATCHING),
            )
        }
    }
}
