package com.bratan.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) startService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        val etKey = findViewById<EditText>(R.id.etApiKey)
        val etWake = findViewById<EditText>(R.id.etWakeWord)
        val tvLog = findViewById<TextView>(R.id.tvLog)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        etKey.setText(prefs.apiKey)
        etWake.setText(prefs.wakeWord)

        findViewById<LinearLayout>(R.id.xiaomiCard).visibility =
            if (XiaomiCompat.isXiaomi) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnAutostart).setOnClickListener {
            startActivity(XiaomiCompat.autostart(this))
        }
        findViewById<Button>(R.id.btnPowerKeeper).setOnClickListener {
            startActivity(XiaomiCompat.powerKeeper(this))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            prefs.apiKey = etKey.text.toString()
            prefs.wakeWord = etWake.text.toString()
            stopService(Intent(this, ModularVoiceService::class.java))
            requestAndStart()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, ModularVoiceService::class.java))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ServiceBus.log.collect { tvLog.text = it.joinToString("\n") } }
                launch { ServiceBus.running.collect {
                    tvStatus.text = if (it) "● Работает" else "○ Остановлен" } }
            }
        }

        handleAutostart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutostart()
    }

    private fun handleAutostart() {
        if (intent?.getBooleanExtra(RecoveryNotifier.EXTRA_AUTOSTART, false) != true) return
        intent.removeExtra(RecoveryNotifier.EXTRA_AUTOSTART)
        stopService(Intent(this, ModularVoiceService::class.java))
        requestAndStart()
    }

    private fun requestAndStart() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private fun startService() {
        startForegroundService(Intent(this, ModularVoiceService::class.java))
    }
}
