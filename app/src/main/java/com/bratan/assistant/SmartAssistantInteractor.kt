package com.bratan.assistant

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class SmartAssistantInteractor(
    private val cache: CommandCache,
    private val local: IntentProvider,
    private val cloud: IntentProvider?,
    private val cloudTimeoutMs: Long = 2000L,
    private val localConfidentThreshold: Float = 0.85f
) {
    suspend fun process(text: String): CommandResult? = coroutineScope {
        cache.get(text)?.let { return@coroutineScope it }

        val localResult = local.process(text)
        if (localResult != null && localResult.confidence >= localConfidentThreshold) {
            cache.put(text, localResult)
            return@coroutineScope localResult
        }

        val cloudResult = if (cloud != null) {
            withTimeoutOrNull(cloudTimeoutMs) {
                async { cloud.process(text) }.await()
            }
        } else null

        val merged = ConfidenceMergeStrategy.merge(listOfNotNull(localResult, cloudResult))
        merged?.let { cache.put(text, it) }
        merged
    }
}
