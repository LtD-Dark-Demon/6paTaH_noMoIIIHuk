package com.bratan.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ModularVoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recognizer: SpeechRecognizer? = null
    private lateinit var interactor: SmartAssistantInteractor
    private lateinit var executor: AndroidActionExecutor
    private lateinit var tflite: TFLiteModelProcessor
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager

    private var started = false
    private var errorStreak = 0
    private var lastText = ""
    private var lastTextAt = 0L
    private var restartJob: Job? = null
    private var useOnDevice = false
    private var preferOffline = true
    @Volatile private var lastCallbackAt = 0L

    private val busyModes = setOf(
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_ON &&
                SystemClock.elapsedRealtime() - lastCallbackAt > 15_000) restart(0)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audio = getSystemService(AudioManager::class.java)
        createChannels()
        executor = AndroidActionExecutor(this)
        tflite = TFLiteModelProcessor(this)
        val local = ChainLocalModel(RuleBasedIntentProvider(), tflite)
        val cloud = prefs.apiKey.takeIf { it.isNotBlank() }?.let {
            CircuitBreakerProvider(ClaudeApiProvider(it))
        }
        interactor = SmartAssistantInteractor(CommandCache(), local, cloud)
        useOnDevice = Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        try {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            ServiceBus.post("Сервис не запущен: ${e.javaClass.simpleName}")
            stopSelf(); return START_NOT_STICKY
        }

        if (!started) {
            started = true
            if (!checkPreconditions()) { stopSelf(); return START_NOT_STICKY }
            scope.launch(Dispatchers.Default) { tflite.prepare() }
            if (intent == null) {
                ServiceBus.post("Перезапущен системой — нажмите уведомление")
                RecoveryNotifier.show(this, "Братан перезапущен системой",
                    "Нажмите, чтобы восстановить микрофон")
            } else {
                RecoveryNotifier.cancel(this)
            }
            startListening()
            startWatchdog()
        }
        ServiceBus.running.value = true
        return START_STICKY
    }

    private fun checkPreconditions(): Boolean {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ServiceBus.post("Нет разрешения на микрофон"); return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            ServiceBus.post("Распознавание речи недоступно"); return false
        }
        return true
    }

    private fun startListening() {
        recognizer?.destroy()
        recognizer = (if (useOnDevice && Build.VERSION.SDK_INT >= 31)
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this)
        ).apply { setRecognitionListener(listener) }

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        touch()
        recognizer?.startListening(i)
    }

    private fun touch() { lastCallbackAt = SystemClock.elapsedRealtime() }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            touch(); errorStreak = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) handleText(text)
            restart(500)
        }

        override fun onError(error: Int) {
            touch()
            var delayMs = 500L
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    ServiceBus.post("Нет разрешения на микрофон"); stopSelf(); return
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> errorStreak = 0

                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> when {
                    useOnDevice -> { useOnDevice = false
                        ServiceBus.post("On-device не поддерживает ru-RU → обычный распознаватель") }
                    preferOffline -> { preferOffline = false
                        ServiceBus.post("Нет офлайн-пакета ru-RU → онлайн-распознавание") }
                    else -> errorStreak++
                }

                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { errorStreak++; delayMs = 1500 }

                else -> errorStreak++
            }
            if (errorStreak > 0) delayMs = maxOf(delayMs, 500L shl errorStreak.coerceAtMost(4))
            if (errorStreak >= 8) {
                errorStreak = 4
                ServiceBus.post("Микрофон недоступен, повтор каждые 8 с")
            }
            restart(delayMs)
        }

        override fun onReadyForSpeech(p: Bundle?) = touch()
        override fun onBeginningOfSpeech() = touch()
        override fun onRmsChanged(rms: Float) = touch()
        override fun onEndOfSpeech() = touch()
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    private fun restart(delayMs: Long) {
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            while (started && audio.mode in busyModes) delay(3000)
            if (started) startListening()
        }
    }

    private fun startWatchdog() = scope.launch {
        while (started) {
            delay(30_000)
            if (SystemClock.elapsedRealtime() - lastCallbackAt > 60_000) {
                ServiceBus.post("Распознаватель завис — перезапуск"); restart(0)
            }
            tflite.unloadIfIdle(5 * 60_000)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            ServiceBus.post("Мало памяти — выгружаю TFLite")
            scope.launch(Dispatchers.Default) { tflite.unload() }
        }
    }

    private fun handleText(raw: String) {
        val command = stripWakeWord(raw) ?: return
        val now = SystemClock.elapsedRealtime()
        if (command == lastText && now - lastTextAt < 1500) return
        lastText = command; lastTextAt = now

        ServiceBus.post("Слышу: $raw")
        scope.launch {
            val wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bratan:process")
            wl.acquire(10_000)
            try {
                val result = interactor.process(command)
                if (result != null) {
                    ServiceBus.post("→ ${result.intent} (${result.source}, %.2f)"
                        .format(result.confidence))
                    executor.execute(result)
                } else ServiceBus.post("Не понял: $command")
            } catch (e: Exception) {
                Log.w("Bratan", "process: ${e.message}")
            } finally {
                if (wl.isHeld) wl.release()
            }
        }
    }

    private fun stripWakeWord(raw: String): String? {
        val ww = prefs.wakeWord
        if (ww.isBlank()) return raw
        val t = raw.lowercase().trimStart()
        val first = t.substringBefore(' ').trim(',', '.', '!')
        if (levenshtein(first, ww) > 1) return null
        return t.substringAfter(' ', "").trim().ifBlank { null }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1,
                d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_SERVICE, "Статус сервиса", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ACTIONS, "Действия", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, ModularVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Братан на связи")
            .setContentText("Скажите: «${prefs.wakeWord.ifBlank { "команду" }}, …»")
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Стоп", stopPi).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        started = false
        ServiceBus.running.value = false
        runCatching { unregisterReceiver(screenReceiver) }
        recognizer?.destroy(); recognizer = null
        scope.cancel()
        executor.release()
        tflite.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_SERVICE = "bratan_service"
        const val CHANNEL_ACTIONS = "bratan_actions"
        const val ACTION_STOP = "com.bratan.assistant.STOP"
        const val NOTIF_ID = 1
    }
}
