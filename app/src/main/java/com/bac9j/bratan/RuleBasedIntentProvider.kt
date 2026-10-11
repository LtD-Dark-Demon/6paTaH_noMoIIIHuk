package com.bac9j.bratan

/**
 * [FIX] У Gemma локальная модель возвращала ВСЕГДА «TURN_ON_WIFI» с уверенностью 0.92 (заглушка),
 * которая ещё и кэшировалась. Это правила, работающие реально без модели и без сети.
 */
class RuleBasedIntentProvider : LocalModelProvider {

    private class Rule(val intent: String, val regex: Regex, val actions: List<String>, val paramName: String? = null)

    private val on = "(?:включи|включить|вруби|запусти|зажги)"
    private val off = "(?:выключи|выключить|отключи|отключить|вырубай|вырубить|погаси)"
    private val wifi = "(?:вай[\\s-]?фай|wi[\\s-]?fi)"
    private val torch = "(?:фонарик|фонарь)"

    private val rules = listOf(
        Rule("WIFI_ON", Regex("$on\\s+$wifi"), listOf("enable_wifi")),
        Rule("WIFI_OFF", Regex("$off\\s+$wifi"), listOf("disable_wifi")),
        Rule("FLASHLIGHT_ON", Regex("$on\\s+$torch"), listOf("flashlight_on")),
        Rule("FLASHLIGHT_OFF", Regex("$off\\s+$torch"), listOf("flashlight_off")),
        Rule("CREATE_REMINDER", Regex("^(?:напомни|напомнить)(?:\\s+мне)?\\s+(.+)$"), listOf("create_calendar_event"), "title")
    )

    override suspend fun analyzeCommand(text: String): CommandResult? {
        val t = text.lowercase().replace('ё', 'е').replace(Regex("[,.!?]"), " ").replace(Regex("\\s+"), " ").trim()
        for (rule in rules) {
            val m = rule.regex.find(t) ?: continue
            val params = if (rule.paramName != null && m.groupValues.size > 1) mapOf(rule.paramName to m.groupValues[1]) else emptyMap()
            return CommandResult(rule.intent, rule.actions, 0.92, CommandResult.Source.LOCAL, params)
        }
        return null
    }

    override fun releaseResources() {}
}

/** Цепочка локальных провайдеров: правила (мгновенно) -> TFLite. Первый уверенный результат выигрывает. */
class ChainLocalModel(private val providers: List<LocalModelProvider>) : LocalModelProvider {

    override suspend fun analyzeCommand(text: String): CommandResult? {
        var best: CommandResult? = null
        for (p in providers) {
            val r = p.analyzeCommand(text) ?: continue
            if (r.confidence >= ConfidenceMergeStrategy.CONFIDENT_THRESHOLD) return r
            if (best == null || r.confidence > best.confidence) best = r
        }
        return best
    }

    override fun releaseResources() = providers.forEach { it.releaseResources() }

    override fun adaptToThermal(status: Int) = providers.forEach { it.adaptToThermal(status) }
}
