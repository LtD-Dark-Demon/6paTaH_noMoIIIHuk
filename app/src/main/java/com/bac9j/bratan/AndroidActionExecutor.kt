package com.bac9j.bratan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Исполнитель действий. Только белый список: что бы ни вернула нейросеть, неизвестное действие игнорируется.
 *
 * Android 10+ запрещает сервису в фоне открывать Activity и переключать Wi-Fi,
 * поэтому для таких действий показывается уведомление «нажмите» с нужным экраном.
 */
class AndroidActionExecutor(private val context: Context) : ActionExecutor {

    private var notificationId = 2000

    override fun execute(result: CommandResult) {
        for (action in result.actions) {
            try {
                when (action) {
                    "flashlight_on" -> torch(true)
                    "flashlight_off" -> torch(false)
                    "enable_wifi", "disable_wifi" ->
                        offer("Wi-Fi", "Нажмите, чтобы переключить Wi-Fi", Intent(Settings.Panel.ACTION_WIFI))
                    "create_calendar_event" -> calendar(result.params)
                    else -> Log.w(TAG, "Неизвестное действие: $action")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка действия $action: ${e.message}")
            }
        }
    }

    private fun torch(on: Boolean) {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return
        cm.setTorchMode(id, on)
    }

    private fun calendar(params: Map<String, String>) {
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, params["title"]?.ifBlank { null } ?: "Напоминание")
        params["start"]?.let { s ->
            runCatching { LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
                .getOrNull()?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        }
        offer("Календарь", "Нажмите, чтобы создать событие: ${params["title"] ?: ""}", intent)
    }

    private fun offer(title: String, text: String, target: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Действия ассистента", NotificationManager.IMPORTANCE_HIGH))
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, notificationId, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(notificationId++, n)
    }

    private companion object {
        const val TAG = "Actions"
        const val CHANNEL = "assistant_actions"
    }
}
