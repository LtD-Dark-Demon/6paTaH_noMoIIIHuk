package com.bratan.assistant

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/** Фонарик с учётом занятости камеры (CAMERA_IN_USE) и текущего состояния. */
class TorchController(ctx: Context) {
    private val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val available = ConcurrentHashMap<String, Boolean>()
    @Volatile private var state = false

    private val cameraId: String? = runCatching {
        cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeUnavailable(id: String) { available[id] = false }
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            available[id] = true
            if (id == cameraId) state = enabled
        }
    }

    init { cm.registerTorchCallback(callback, Handler(Looper.getMainLooper())) }

    fun set(on: Boolean): String {
        val id = cameraId ?: return "Вспышка не найдена"
        if (available[id] == false) return "Фонарик занят камерой"
        if (state == on) return if (on) "Фонарик уже включён" else "Фонарик уже выключен"
        return try {
            cm.setTorchMode(id, on)
            if (on) "Фонарик включён" else "Фонарик выключен"
        } catch (e: CameraAccessException) {
            if (e.reason == CameraAccessException.CAMERA_IN_USE) "Фонарик занят камерой"
            else "Ошибка фонарика (${e.reason})"
        } catch (e: Exception) {
            "Ошибка фонарика"
        }
    }

    fun release() = runCatching { cm.unregisterTorchCallback(callback) }
}
