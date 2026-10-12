package com.bratan.assistant

data class CommandResult(
    val intent: String,
    val confidence: Float,
    val actions: List<String> = emptyList(),
    val params: Map<String, String> = emptyMap(),
    val source: Source
) {
    enum class Source { CACHE, RULES, TFLITE, CLOUD }
}

interface IntentProvider {
    suspend fun process(text: String): CommandResult?
}

interface ActionExecutor {
    fun execute(result: CommandResult)
}

object Actions {
    const val FLASHLIGHT_ON = "flashlight_on"
    const val FLASHLIGHT_OFF = "flashlight_off"
    const val WIFI_ON = "enable_wifi"
    const val WIFI_OFF = "disable_wifi"
    const val CALENDAR = "create_calendar_event"
    val WHITELIST = setOf(FLASHLIGHT_ON, FLASHLIGHT_OFF, WIFI_ON, WIFI_OFF, CALENDAR)
}
