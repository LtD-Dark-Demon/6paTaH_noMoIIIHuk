package com.bac9j.bratan

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ==============================================================================
// 4. СЛОЙ ИНТЕРФЕЙСА (ANDROID SERVICE) - Взаимодействие с системой
// ==============================================================================

class ModularVoiceService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // В реальном приложении это делается через Dagger/Hilt (Dependency Injection)
    // Здесь собираем зависимости вручную для примера
    private lateinit var assistantInteractor: SmartAssistantInteractor
    private lateinit var localModel: LocalModelProvider
    private lateinit var actionExecutor: ActionExecutor
    private var speechRecognizer: SpeechRecognizer? = null

    private var restartJob: Job? = null
    private var errorStreak = 0
    private var preferOffline = true
    private var started = false
    private var lastText = ""
    private var lastTextAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { localModel.adaptToThermal(it) }

    override fun onCreate() {
        super.onCreate()

        // Сборка архитектуры
        val apiKey = Prefs.apiKey(this)
        val cloud: List<CloudApiProvider> =
            if (apiKey.isBlank()) emptyList() // без ключа ничего не уходит в сеть
            else listOf(CircuitBreakerProvider(ClaudeApiProvider(apiKey))) // Легко добавить: OpenAiProvider(...)

        localModel = ChainLocalModel(listOf(RuleBasedIntentProvider(), TFLiteModelProcessor(this)))
        assistantInteractor = SmartAssistantInteractor(
            localModel = localModel,
            cloudApis = cloud,
            mergeStrategy = ConfidenceMergeStrategy()
        )
        actionExecutor = AndroidActionExecutor(this)

        // [OPT] Умный WakeLock: PARTIAL с жёстким таймаутом (система сама сбросит, даже если код зависнет)
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bratan:command").apply { setReferenceCounted(false) }
        // [OPT] Thermal throttling: потоки TFLite 2 -> 1 при нагреве
        localModel.adaptToThermal(pm.currentThermalStatus)
        pm.addThermalStatusListener(mainExecutor, thermalListener)

        setupSpeechRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // [FIX] Android 14: тип сервиса передаётся и в startForeground (плюс в манифесте), иначе SecurityException.
        // Без RECORD_AUDIO или при запуске из фона (после убийства системой) сервис не стартует — выходим чисто.
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                stopSelf(); return START_NOT_STICKY
            }
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запустить foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (speechRecognizer == null) {
            AssistantBus.post("Распознавание речи недоступно (нужно приложение Google / служба распознавания)")
            stopSelf()
            return START_NOT_STICKY
        }
        // [FIX] onStartCommand вызывается повторно — второй startListening давал ERROR_RECOGNIZER_BUSY.
        if (!started) {
            started = true
            startListening()
        }
        return START_STICKY
    }

    private fun setupSpeechRecognizer() {
        // [FIX] Проверка наличия службы распознавания (на Android 11+ нужен <queries> в манифесте).
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    errorStreak = 0
                    val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spokenText.isNullOrBlank()) {
                        handleVoiceInput(spokenText)
                    }
                    restartListeningSafely()
                }

                override fun onError(error: Int) {
                    // [FIX] Раньше любая ошибка = перезапуск через 500 мс бесконечно (в т.ч. при отозванном разрешении).
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            AssistantBus.post("Нет разрешения на микрофон")
                            stopSelf()
                        }
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            restartListeningSafely() // штатная тишина
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            speechRecognizer?.cancel()
                            restartListeningSafely(1000)
                        }
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                            // Нет офлайн-пакета русского — продолжаем в онлайн-режиме
                            preferOffline = false
                            restartListeningSafely(1000)
                        }
                        else -> {
                            // [OPT] Экспоненциальная пауза 0.5 -> 8 с при серии ошибок
                            errorStreak++
                            restartListeningSafely(minOf(500L shl minOf(errorStreak, 4), 8000L))
                        }
                    }
                }

                // Остальные методы интерфейса (оставлены пустыми для краткости)
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun handleVoiceInput(raw: String) {
        // [OPT] Debounce: одинаковая фраза в течение 1.5 с игнорируется
        val now = SystemClock.elapsedRealtime()
        if (raw == lastText && now - lastTextAt < 1500) return
        lastText = raw
        lastTextAt = now

        // [FIX] Раньше в обработку (и в облако) уходила ЛЮБАЯ услышанная речь. Теперь только фразы со стартовым словом.
        val text = stripWakeWord(raw) ?: return

        serviceScope.launch {
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            try {
                // Передаем текст в Доменный слой (Interactor). Сервису не нужно знать про нейросети.
                val result = assistantInteractor.processVoiceCommand(text)

                Log.d(TAG, "Результат: ${result.intent}, Источник: ${result.source}")
                AssistantBus.post("«$text» → ${result.intent} (${result.source}, ${"%.2f".format(result.confidence)})")

                // [FIX] Вызов исполнителя действий (у Gemma отсутствовал)
                if (result.intent != "UNKNOWN" && result.actions.isNotEmpty()) {
                    actionExecutor.execute(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обработки: ${e.message}")
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }

    private fun stripWakeWord(raw: String): String? {
        val t = raw.trim()
        val ww = Prefs.wakeWord(this).lowercase()
        if (ww.isEmpty()) return t.takeIf { it.length >= 2 }
        if (!t.lowercase().startsWith(ww)) return null
        return t.substring(ww.length).trimStart(',', ' ', '.', '!', '-').takeIf { it.length >= 2 }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline) // Оптимизация Android 14
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun restartListeningSafely(delayMs: Long = 500) {
        restartJob?.cancel() // [OPT] не копим перезапуски
        restartJob = serviceScope.launch {
            delay(delayMs) // Защита от спама и рекурсии (ошибка из прошлого анализа)
            startListening()
        }
    }

    private fun createNotification(): Notification {
        val channelId = "assistant_channel"
        val channel = NotificationChannel(channelId, "Фоновый Ассистент", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ModularVoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Братан на связи")
            .setContentText("Слушаю ваши команды...")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Стоп", stop)
            .build()
    }

    override fun onDestroy() {
        restartJob?.cancel()
        serviceJob.cancel() // Отменяет все корутины и сетевые запросы
        getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
        assistantInteractor.cleanUp() // Высвобождает ресурсы TFLite
        speechRecognizer?.destroy()
        speechRecognizer = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "VoiceService"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.bac9j.bratan.STOP"
        const val WAKELOCK_TIMEOUT_MS = 10_000L
    }
}
