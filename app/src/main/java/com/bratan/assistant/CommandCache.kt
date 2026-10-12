package com.bratan.assistant

import android.os.SystemClock

/** LRU + TTL + нечёткое совпадение (Jaccard по основам слов). */
class CommandCache(
    private val maxSize: Int = 100,
    private val ttlMs: Long = 10 * 60_000L
) {
    private data class Entry(val result: CommandResult, val stems: Set<String>, val at: Long)
    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Entry>) = size > maxSize
    }

    @Synchronized
    fun get(text: String): CommandResult? {
        val now = SystemClock.elapsedRealtime()
        val key = normalize(text)
        map[key]?.let {
            if (now - it.at < ttlMs) return it.result.copy(source = CommandResult.Source.CACHE)
            map.remove(key)
        }
        if (key.any { it.isDigit() }) return null
        val stems = stems(key)
        if (stems.isEmpty()) return null
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.at >= ttlMs) { it.remove(); continue }
            if (jaccard(stems, e.value.stems) >= 0.8) {
                return e.value.result.copy(source = CommandResult.Source.CACHE)
            }
        }
        return null
    }

    @Synchronized
    fun put(text: String, result: CommandResult) {
        if (result.confidence <= 0.8f) return
        val key = normalize(text)
        map[key] = Entry(result, stems(key), SystemClock.elapsedRealtime())
    }

    private fun normalize(s: String) =
        s.lowercase().replace(Regex("[^а-яёa-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun stems(s: String) =
        s.split(' ').filter { it.length > 2 }.map { it.take(5) }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        return a.intersect(b).size.toFloat() / a.union(b).size
    }
}
