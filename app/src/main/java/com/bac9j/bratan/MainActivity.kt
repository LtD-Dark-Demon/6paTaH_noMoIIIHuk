package com.bac9j.bratan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Минимальный экран: ключ, стартовое слово, старт/стоп, журнал команд. Без XML-разметки и лишних зависимостей. */
class MainActivity : Activity() {

    private lateinit var keyInput: EditText
    private lateinit var wakeInput: EditText
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        keyInput = EditText(this).apply {
            hint = "Ключ Anthropic API (необязательно)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.apiKey(this@MainActivity))
        }
        wakeInput = EditText(this).apply {
            hint = "Стартовое слово (пусто = слушать всё)"
            setText(Prefs.wakeWord(this@MainActivity))
        }
        val start = Button(this).apply { text = "Старт"; setOnClickListener { startAssistant() } }
        val stop = Button(this).apply {
            text = "Стоп"
            setOnClickListener { stopService(Intent(this@MainActivity, ModularVoiceService::class.java)) }
        }
        val battery = Button(this).apply {
            text = "Настройки батареи (исключить из оптимизации)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        log = TextView(this).apply { textSize = 14f }

        listOf(keyInput, wakeInput, start, stop, battery, log).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        log.text = AssistantBus.snapshot()
        AssistantBus.listener = { log.text = it }
    }

    override fun onPause() {
        AssistantBus.listener = null
        super.onPause()
    }

    private fun startAssistant() {
        Prefs.save(this, keyInput.text.toString(), wakeInput.text.toString())
        if (!ensurePermissions()) return
        // Сервис с типом microphone можно стартовать только при открытом приложении.
        // Если он уже запущен — перезапускаем, чтобы подхватить новый ключ.
        stopService(Intent(this, ModularVoiceService::class.java))
        startForegroundService(Intent(this, ModularVoiceService::class.java))
    }

    private fun ensurePermissions(): Boolean {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) return true
        requestPermissions(need.toTypedArray(), REQUEST_PERMISSIONS)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            startAssistant()
        }
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 100
    }
}
