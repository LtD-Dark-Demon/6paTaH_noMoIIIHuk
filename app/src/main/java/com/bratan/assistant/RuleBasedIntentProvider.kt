package com.bratan.assistant

class RuleBasedIntentProvider : IntentProvider {
    private val on = Regex("(включ|вруб|зажг|активир)")
    private val off = Regex("(выключ|отключ|выруб|погас|потуш)")
    private val flash = Regex("(фонар|вспышк|свет)")
    private val wifi = Regex("(вай[ -]?фай|wi[ -]?fi|wifi|интернет)")
    private val remind = Regex("^(напомни|запиши|добавь\\s+(событие|напоминание))\\s*")

    override suspend fun process(text: String): CommandResult? {
        val t = text.lowercase()
        return when {
            off.containsMatchIn(t) && flash.containsMatchIn(t) -> r("FLASHLIGHT_OFF", Actions.FLASHLIGHT_OFF)
            on.containsMatchIn(t) && flash.containsMatchIn(t) -> r("FLASHLIGHT_ON", Actions.FLASHLIGHT_ON)
            off.containsMatchIn(t) && wifi.containsMatchIn(t) -> r("WIFI_OFF", Actions.WIFI_OFF)
            on.containsMatchIn(t) && wifi.containsMatchIn(t) -> r("WIFI_ON", Actions.WIFI_ON)
            remind.containsMatchIn(t) -> {
                val title = t.replace(remind, "").trim().ifBlank { "Напоминание" }
                // Ниже порога 0.85: при наличии API облако сможет извлечь дату/время.
                // Без API локальный результат всё равно останется доступен через merge().
                CommandResult("CREATE_REMINDER", 0.80f, listOf(Actions.CALENDAR),
                    mapOf("title" to title), CommandResult.Source.RULES)
            }
            else -> null
        }
    }

    private fun r(intent: String, action: String) =
        CommandResult(intent, 0.92f, listOf(action), emptyMap(), CommandResult.Source.RULES)
}
