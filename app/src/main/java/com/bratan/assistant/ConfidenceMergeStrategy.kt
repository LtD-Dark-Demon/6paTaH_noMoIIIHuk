package com.bratan.assistant

object ConfidenceMergeStrategy {
    /** [FIX] максимум уверенности из всех источников, а не «всегда облако». */
    fun merge(results: List<CommandResult>): CommandResult? =
        results.filter { it.intent.isNotBlank() && it.intent != "UNKNOWN" }
            .maxByOrNull { it.confidence }
}
