package com.bratan.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log

/** Выполняет только действия из белого списка. Действия, требующие Activity,
 * предлагаются через уведомление: Android 10+ запрещает открывать экран из фона. */
class AndroidActionExecutor(private val context: Context) : ActionExecutor {

    private val torch = TorchController(context)
    private var notificationId = 2000

    override fun execute(result: CommandResult) {
        for (action in result.actions) {
            if (action !in Actions.WHITELIST) {
                Log.w(TAG, "Неизвестное действие: $action")
                continue
            }
            try {
                when (action) {
                    Actions.FLASHLIGHT_ON -> ServiceBus.post(torch.set(true))
                    Actions.FLASHLIGHT_OFF -> ServiceBus.post(torch.set(false))
                    Actions.WIFI_ON, Actions.WIFI_OFF -> offer(
                        "Wi‑Fi",
                        "Нажмите, чтобы открыть панель Wi‑Fi",
                        Intent(Settings.Panel.ACTION_WIFI)
                    )
                    Actions.CALENDAR -> calendar(result.params)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка действия $action", e)
                ServiceBus.post("Ошибка действия: $action")
            }
        }
    }

    private fun calendar(params: Map<String, String>) {
        val title = params["title"]?.takeIf { it.isNotBlank() } ?: "Напоминание"
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)

        params["epoch_ms"]?.toLongOrNull()?.let { epoch ->
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, epoch)
        }

        offer("Календарь", "Нажмите, чтобы создать событие: $title", intent)
    }

    private fun offer(title: String, text: String, target: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            ModularVoiceService.CHANNEL_ACTIONS,
            "Действия",
            NotificationManager.IMPORTANCE_HIGH
        ))
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val id = notificationId++
        val pi = PendingIntent.getActivity(
            context,
            id,
            target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(id, Notification.Builder(context, ModularVoiceService.CHANNEL_ACTIONS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
        ServiceBus.post(text)
    }

    fun release() = torch.release()

    private companion object {
        const val TAG = "BratanActions"
    }
}
