package com.bratan.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ClaudeApiProvider(
    private val apiKey: String,
    private val model: String = "claude-haiku-5-5"
) : IntentProvider {

    override suspend fun process(text: String): CommandResult? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 300)
            .put("system", SYSTEM_PROMPT)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", text)))
            .toString()

        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2000   // [FIX] withTimeoutOrNull не прерывает блокирующий I/O
            readTimeout = 2000
            doOutput = true
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("content-type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            parse(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(resp: String): CommandResult? {
        val content = JSONObject(resp).getJSONArray("content")
            .getJSONObject(0).getString("text")
        val json = JSONObject(content.substring(
            content.indexOf('{'), content.lastIndexOf('}') + 1))
        val actions = ArrayList<String>()
        json.optJSONArray("actions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.getString(i)
                if (a in Actions.WHITELIST) actions.add(a)   // белый список
            }
        }
        val params = HashMap<String, String>()
        json.optJSONObject("params")?.let { p ->
            p.keys().forEach { k -> params[k] = p.getString(k) }
        }
        val intent = json.optString("intent", "UNKNOWN")
        if (intent == "UNKNOWN" || actions.isEmpty()) return null
        return CommandResult(intent,
            json.optDouble("confidence", 0.7).toFloat().coerceIn(0f, 1f),
            actions, params, CommandResult.Source.CLOUD)
    }

    companion object {
        private val SYSTEM_PROMPT = """
Ты — классификатор голосовых команд ассистента. Ответь ТОЛЬКО JSON без пояснений:
{"intent":"ИМЯ","confidence":0.0-1.0,"actions":["..."],"params":{"title":"...","epoch_ms":"..."}}
Разрешённые actions: flashlight_on, flashlight_off, enable_wifi, disable_wifi, create_calendar_event.
Для create_calendar_event извлеки title и, если указано время, epoch_ms (миллисекунды Unix; сегодня = текущая дата пользователя).
Если команда не подходит: {"intent":"UNKNOWN","confidence":0,"actions":[]}
""".trim()
    }
}
