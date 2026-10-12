package com.bratan.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object RecoveryNotifier {
    const val CHANNEL = "bratan_recovery"
    const val NOTIF_ID = 2
    const val EXTRA_AUTOSTART = "autostart"

    fun show(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL, "Восстановление", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(
            ctx, NOTIF_ID,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_AUTOSTART, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(pi).setAutoCancel(true)
            .build())
    }

    fun cancel(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
}
