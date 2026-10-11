package com.bac9j.bratan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

// ==============================================================================
// 3. БИЗНЕС-ЛОГИКА (USE CASES) - Координация слоев
// ==============================================================================

/**
 * Координатор. Ему неважно, как работают модели под капотом.
 * Ему передают список Cloud-провайдеров и одного локального.
 * Принцип Open/Closed: можно добавлять провайдеров, не меняя этот класс.
 */
class SmartAssistantInteractor(
    private val localModel: LocalModelProvider,
    private val cloudApis: List<CloudApiProvider>,
    private val mergeStrategy: HybridMergeStrategy,
    private val cloudTimeoutMs: Long = 2000L
) {
    private val semanticCache = CommandCache()

    suspend fun processVoiceCommand(voiceText: String): CommandResult = coroutineScope {
        // 1. Проверка кэша (Оптимизация скорости)
        semanticCache.get(voiceText)?.let {
            return@coroutineScope it.copy(source = CommandResult.Source.CACHE)
        }

        // 2. Локальная модель (20-40 мс).
        // [FIX] Было: облако стартовало параллельно ВСЕГДА и ждалось через awaitAll, даже когда локальная
        // модель уверена — запрос уходил зря (деньги, батарея), хотя в merge() написано «сеть не ждем».
        // Теперь облако запускается только если локальный результат не уверен. Остальное — как у Gemma.
        val localResult = try {
            localModel.analyzeCommand(voiceText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        val localIsConfident = localResult != null && localResult.confidence >= ConfidenceMergeStrategy.CONFIDENT_THRESHOLD
        val cloudResults = if (localIsConfident || cloudApis.isEmpty()) {
            emptyList()
        } else {
            cloudApis.map { api ->
                async {
                    // Ограничиваем ожидание сети 2-ю секундами
                    withTimeoutOrNull(cloudTimeoutMs) { api.analyzeCommand(voiceText) }
                }
            }.awaitAll().filterNotNull()
        }

        // 3. Слияние результатов через заданную стратегию
        val finalResult = mergeStrategy.merge(localResult, cloudResults)

        // 4. Кэширование успешного результата
        if (finalResult.confidence > 0.8) {
            semanticCache.put(voiceText, finalResult)
        }

        return@coroutineScope finalResult
    }

    fun cleanUp() {
        localModel.releaseResources()
        semanticCache.clear()
    }
}
