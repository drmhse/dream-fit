package com.drmhse.dream.fit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

// The watch's notification tray. The header always shows the posting app (only
// the system bridge can stamp another package's identity, and that needs GMS),
// so the source app goes in the title line itself.
class Tray(private val ctx: Context) {
    companion object {
        const val FOREGROUND_ID = 1
        const val CALL_ID = 200
        private const val BRIDGE = "bridge"
        private const val PHONE = "phone"
        private const val CALLS = "calls"
    }

    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private var nextId = 100
    private val idsByUid = mutableMapOf<String, Int>()
    private val groupCount = mutableMapOf<String, Int>()

    fun foregroundNotification(): Notification {
        channel(BRIDGE, "Dream Fit bridge", NotificationManager.IMPORTANCE_MIN)
        return Notification.Builder(ctx, BRIDGE)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText("iPhone link active")
            .setSmallIcon(R.drawable.ic_stat_dreamfit)
            .build()
    }

    fun showNotify(
        title: String,
        body: String,
        app: String = "iPhone",
        icon: Int = R.drawable.ic_stat_dreamfit,
        uidHex: String? = null,
    ) {
        channel(PHONE, "iPhone alerts", NotificationManager.IMPORTANCE_HIGH)
        val color = when (app) {
            "WhatsApp", "Messages", "Phone" -> 0xFF34C759.toInt()
            "Gmail", "Mail" -> 0xFFEA4335.toInt()
            else -> 0xFF0A84FF.toInt()
        }
        val group = "app:$app"
        val id = nextId++
        uidHex?.let { idsByUid[it] = id }
        nm.notify(id, alert(PHONE, "$app • $title", body, icon, color, group).build())
        val count = groupCount.merge(group, 1) { a, b -> a + b } ?: 1
        nm.notify(
            group.hashCode(),
            alert(PHONE, app, "$count new", icon, color, group).setGroupSummary(true).build(),
        )
    }

    fun showCall(caller: String, uidHex: String) {
        channel(CALLS, "Phone calls", NotificationManager.IMPORTANCE_HIGH)
        nm.notify(
            CALL_ID,
            Notification.Builder(ctx, CALLS)
                .setContentTitle("Incoming call").setContentText(caller)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setCategory(Notification.CATEGORY_CALL)
                .addAction(action("Answer", BridgeService.ANSWER, uidHex, 0))
                .addAction(action("Decline", BridgeService.DECLINE, uidHex, 1))
                .setOngoing(true).build(),
        )
    }

    fun cancelCall() = nm.cancel(CALL_ID)

    fun cancelFor(uidHex: String) {
        idsByUid.remove(uidHex)?.let { nm.cancel(it) }
    }

    private fun alert(ch: String, title: String, body: String, icon: Int, color: Int, group: String) =
        Notification.Builder(ctx, ch)
            .setContentTitle(title).setContentText(body)
            .setSmallIcon(icon).setColor(color).setColorized(true)
            .setGroup(group)

    private fun action(label: String, intentAction: String, uidHex: String, offset: Int) =
        Notification.Action.Builder(
            null, label,
            PendingIntent.getService(
                ctx, uidHex.hashCode() + offset,
                Intent(ctx, BridgeService::class.java).setAction(intentAction).putExtra("uid", uidHex),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    private fun channel(id: String, name: String, importance: Int) {
        nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }
}
