package com.bac9j.bratan

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Реализация для Claude API (можно добавить OpenAIProvider по аналогии).
 * [FIX] У Gemma это была заглушка: всегда «COMPLEX_SCHEDULE» 0.98 без запроса, ключ вшит в код.
 * Теперь реальный HTTP-запрос; ключ берётся из настроек приложения.
 */
class ClaudeApiProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL
) : CloudApiProvider {

    override val providerName = "Claude"

    override suspend fun analyzeCommand(text: String): CommandResult? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 200)
                .put("system", SYSTEM_PROMPT)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", "Сейчас: ${LocalDateTime.now()}\nКоманда: $text")
                ))

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                // [FIX] Блокирующий I/O не отменяется withTimeoutOrNull — таймауты сокета обязательны.
                connectTimeout = 1500
                readTimeout = 1800
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode != 200) {
                    Log.w(TAG, "HTTP ${conn.responseCode}")
                    return@withContext null
                }
                parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Сетевая ошибка: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun parse(response: String): CommandResult? {
        val content = JSONObject(response).getJSONArray("content")
        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val j = JSONObject(text.substring(start, end + 1))
        val actions = j.optJSONArray("actions")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val params = j.optJSONObject("params")?.let { p -> p.keys().asSequence().associateWith { p.optString(it) } } ?: emptyMap()
        return CommandResult(j.optString("intent", "UNKNOWN"), actions, j.optDouble("confidence", 0.0), CommandResult.Source.CLOUD, params)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val TAG = "Claude"
        private const val SYSTEM_PROMPT =
            "Ты классификатор голосовых команд Android-ассистента. Ответь ТОЛЬКО JSON без пояснений: " +
            "{\"intent\":\"ИМЯ_КАПСОМ\",\"actions\":[...],\"confidence\":0.0-1.0,\"params\":{\"title\":\"...\",\"start\":\"2026-01-31T18:30:00\"}}. " +
            "Допустимые actions: enable_wifi, disable_wifi, flashlight_on, flashlight_off, create_calendar_event. " +
            "start — локальное время ISO-8601 без часового пояса, считай от «Сейчас». " +
            "Если команда не подходит ни под одно действие: intent \"UNKNOWN\", actions [], confidence 0."
    }
}

/**
 * [OPT] Circuit Breaker (оптимизация из анализа Gemma, в её коде отсутствовала):
 * 3 сбоя подряд — облако отключается на 10 минут, без ожидания таймаутов.
 */
class CircuitBreakerProvider(
    private val delegate: CloudApiProvider,
    private val maxFailures: Int = 3,
    private val cooldownMs: Long = 10 * 60_000L,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) : CloudApiProvider {

    override val providerName: String get() = delegate.providerName

    private val failures = AtomicInteger(0)
    @Volatile private var openUntil = 0L

    override suspend fun analyzeCommand(text: String): CommandResult? {
        if (clock() < openUntil) return null
        val result = try {
            delegate.analyzeCommand(text)
        } catch (e: CancellationException) {
            onFailure() // сюда же попадает таймаут withTimeoutOrNull из интерактора
            throw e
        }
        if (result == null) onFailure() else failures.set(0)
        return result
    }

    private fun onFailure() {
        if (failures.incrementAndGet() >= maxFailures) {
            openUntil = clock() + cooldownMs
            failures.set(0)
        }
    }
}
