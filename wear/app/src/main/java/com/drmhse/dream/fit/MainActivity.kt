package com.drmhse.dream.fit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
    }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { granted -> granted }) startBridge()
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
        if (need.isEmpty()) { startBridge(); return }
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

    private fun startBridge() {
        runCatching { startForegroundService(Intent(this, BridgeService::class.java)) }
    }
}
