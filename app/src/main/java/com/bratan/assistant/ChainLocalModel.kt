package com.bratan.assistant

/** Правила → TFLite. Правила быстрее — всегда первыми. */
class ChainLocalModel(
    private val rules: RuleBasedIntentProvider,
    private val tflite: TFLiteModelProcessor
) : IntentProvider {
    override suspend fun process(text: String): CommandResult? {
        rules.process(text)?.let { return it }
        if (!tflite.available) return null
        return tflite.process(text)
    }
}
