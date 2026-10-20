package io.github.priceping

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object Notifier {
    private const val CHANNEL = "price_drops"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Price drops", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Alerts when a tracked price drops or an item is back in stock"
            nm.createNotificationChannel(ch)
        }
    }

    fun show(ctx: Context, itemId: String, title: String, text: String) {
        ensureChannel(ctx)
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        val open = Intent(ctx, MainActivity::class.java)
            .putExtra("openItem", itemId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, itemId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF0E7C66.toInt())
            .setContentTitle(title)
            .setContentText(text.substringBefore('\n'))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify((itemId + title).hashCode(), n)
    }
}
