package com.bratan.assistant

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("bratan_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var wakeWord: String
        get() = sp.getString("wake_word", "братан") ?: "братан"
        set(v) = sp.edit().putString("wake_word", v.trim().lowercase()).apply()
}
