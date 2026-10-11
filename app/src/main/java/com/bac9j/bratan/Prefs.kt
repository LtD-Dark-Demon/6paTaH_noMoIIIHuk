package com.bac9j.bratan

import android.content.Context

/** Настройки. Ключ API хранится в приватном SharedPreferences (allowBackup=false), а не в коде. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("bratan", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("api_key", "")?.trim().orEmpty()
    fun wakeWord(c: Context): String = sp(c).getString("wake_word", "братан")?.trim().orEmpty()

    fun save(c: Context, apiKey: String, wakeWord: String) {
        sp(c).edit().putString("api_key", apiKey.trim()).putString("wake_word", wakeWord.trim()).apply()
    }
}

/** Журнал последних команд для экрана (сервис и Activity в одном процессе, оба на главном потоке). */
object AssistantBus {
    private val lines = ArrayDeque<String>()
    var listener: ((String) -> Unit)? = null

    fun post(message: String) {
        lines.addFirst(message)
        while (lines.size > 20) lines.removeLast()
        listener?.invoke(snapshot())
    }

    fun snapshot(): String = lines.joinToString("\n")
}
