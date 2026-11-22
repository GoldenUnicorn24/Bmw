package de.nightdesk.trade

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal class TradeNotices(private val context: Context) {
    private val settings = context.getSharedPreferences("nightdesk-notices", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val channel = "paper-execution"

    var enabled: Boolean
        get() = settings.getBoolean("fills", false)
        set(value) { settings.edit().putBoolean("fills", value).apply() }

    fun fill(title: String, symbol: String) {
        if (!enabled || !manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(channel, "Papierausführungen", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(20, Notification.Builder(context, channel).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title).setContentText(symbol).setAutoCancel(true).build())
    }
}
