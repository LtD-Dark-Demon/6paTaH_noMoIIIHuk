package com.bratan.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Связь сервис → UI без binder-а. */
object ServiceBus {
    val log = MutableStateFlow<List<String>>(emptyList())
    val running = MutableStateFlow(false)
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun post(msg: String) {
        log.value = (log.value + "${fmt.format(Date())}  $msg").takeLast(20)
    }
}
