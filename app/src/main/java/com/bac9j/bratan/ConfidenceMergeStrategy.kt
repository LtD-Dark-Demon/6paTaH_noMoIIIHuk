package com.bac9j.bratan

/** Умная стратегия объединения результатов (Приоритет локальному, если он уверен) */
class ConfidenceMergeStrategy : HybridMergeStrategy {

    override fun merge(local: CommandResult?, cloudResponses: List<CommandResult>): CommandResult {
        // Если локальная модель отработала быстро и уверена на 85%+, сеть не ждем (экономия батареи)
        if (local != null && local.confidence >= CONFIDENT_THRESHOLD) {
            return local
        }

        // [FIX] Было: облако всегда побеждает локальный, даже если оно менее уверено.
        // Теперь выбираем самый уверенный из ВСЕХ ответов.
        val best = (cloudResponses + listOfNotNull(local)).maxByOrNull { it.confidence }
        return best ?: CommandResult("UNKNOWN", emptyList(), 0.0, CommandResult.Source.LOCAL)
    }

    companion object {
        const val CONFIDENT_THRESHOLD = 0.85
    }
}
