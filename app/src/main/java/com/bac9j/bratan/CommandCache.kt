package com.bac9j.bratan

/**
 * Кэш команд (развитие semanticCache Gemma).
 * [FIX] ConcurrentHashMap рос бесконечно и совпадал только по точной строке — теперь LRU + TTL.
 * [OPT] Нечёткое совпадение по общим основам слов (Jaccard). Это лексический, а не векторный
 *       семантический кэш: «чё по погоде» и «какая погода» он НЕ свяжет (нужны эмбеддинги).
 * Нечёткий поиск отключён для команд с цифрами и с параметрами — иначе «через 5 минут» == «через 10 минут».
 */
class CommandCache(
    private val maxSize: Int = 100,
    private val ttlMs: Long = 10 * 60_000L,
    private val similarity: Double = 0.8
) {
    private class Entry(val result: CommandResult, val tokens: Set<String>, val hasDigits: Boolean, val at: Long)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxSize
    }

    @Synchronized
    fun get(text: String): CommandResult? {
        val key = normalize(text)
        val now = System.currentTimeMillis()

        map[key]?.let { e ->
            if (now - e.at <= ttlMs) return e.result
            map.remove(key)
        }

        if (key.any { it.isDigit() }) return null
        val tokens = tokenize(key)
        if (tokens.isEmpty()) return null

        var best: Entry? = null
        var bestSim = 0.0
        val it = map.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.at > ttlMs) { it.remove(); continue }
            if (e.hasDigits || e.result.params.isNotEmpty()) continue
            val sim = jaccard(tokens, e.tokens)
            if (sim > bestSim) { bestSim = sim; best = e }
        }
        return if (bestSim >= similarity) best?.result else null
    }

    @Synchronized
    fun put(text: String, result: CommandResult) {
        val key = normalize(text)
        map[key] = Entry(result, tokenize(key), key.any { it.isDigit() }, System.currentTimeMillis())
    }

    @Synchronized
    fun clear() = map.clear()

    companion object {
        fun normalize(text: String): String =
            text.lowercase().replace('ё', 'е')
                .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
                .trim().replace(Regex("\\s+"), " ")

        private fun tokenize(normalized: String): Set<String> =
            normalized.split(' ').filter { it.isNotEmpty() }.map { it.take(5) }.toSet() // грубый «стемминг»

        private fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val inter = a.count { it in b }
            return inter.toDouble() / (a.size + b.size - inter)
        }
    }
}
