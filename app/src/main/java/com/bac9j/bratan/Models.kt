package com.bac9j.bratan

// ==============================================================================
// 1. ДОМЕННЫЙ СЛОЙ (CORE INTERFACES) - Описания того, ЧТО должно делаться
// Пометки: [FIX] — исправление ошибки Gemma, [OPT] — добавленная оптимизация.
// Все оптимизации Gemma сохранены без изменений.
// ==============================================================================

/** Базовый результат обработки команды */
data class CommandResult(
    val intent: String,
    val actions: List<String>,
    val confidence: Double,
    val source: Source,
    /** [FIX] Слоты команды (title, start). Без них нельзя создать событие. Значение по умолчанию — старый код не ломается. */
    val params: Map<String, String> = emptyMap()
) {
    enum class Source { LOCAL, CLOUD, CACHE }
}

/** Интерфейс для любых облачных API (Claude, GPT, Gemini) */
interface CloudApiProvider {
    val providerName: String
    suspend fun analyzeCommand(text: String): CommandResult?
}

/** Интерфейс для локальных нейросетей (TFLite) */
interface LocalModelProvider {
    suspend fun analyzeCommand(text: String): CommandResult?
    fun releaseResources()

    /** [OPT] Реакция на нагрев (PowerManager.THERMAL_STATUS_*). По умолчанию ничего не делает. */
    fun adaptToThermal(status: Int) {}
}

/** Интерфейс стратегии выбора (какой ответ лучше) */
interface HybridMergeStrategy {
    fun merge(local: CommandResult?, cloudResponses: List<CommandResult>): CommandResult
}

/** [FIX] У Gemma «ActionExecutor — не показан». Без него команда распознаётся, но ничего не делает. */
interface ActionExecutor {
    fun execute(result: CommandResult)
}
