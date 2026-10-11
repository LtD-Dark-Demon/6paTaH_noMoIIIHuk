package com.bac9j.bratan

/** Минимальный BERT WordPiece токенайзер (lowercase, без strip accents). vocab.txt должен совпадать с моделью. */
class BertTokenizer(vocabLines: List<String>) {
    private val vocab = HashMap<String, Int>(vocabLines.size * 2).also { m ->
        vocabLines.forEachIndexed { i, t -> m.putIfAbsent(t.trim(), i) }
    }
    private val padId = vocab["[PAD]"] ?: 0
    private val unkId = vocab["[UNK]"] ?: 100
    private val clsId = vocab["[CLS]"] ?: 101
    private val sepId = vocab["[SEP]"] ?: 102

    /** Пишет в готовые массивы (без аллокаций на каждый вызов). Длина массивов должна быть >= 2. */
    fun encodeInto(text: String, ids: IntArray, mask: IntArray, seg: IntArray) {
        ids.fill(padId); mask.fill(0); seg.fill(0)
        var n = 0
        ids[n] = clsId; mask[n] = 1; n++
        outer@ for (word in basicTokens(text)) {
            for (id in wordPiece(word)) {
                if (n >= ids.size - 1) break@outer // последнее место — под [SEP]
                ids[n] = id; mask[n] = 1; n++
            }
        }
        ids[n] = sepId; mask[n] = 1
    }

    private fun basicTokens(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) } }
        for (c in text.lowercase()) {
            when {
                c.isWhitespace() -> flush()
                c.isLetterOrDigit() -> sb.append(c)
                Character.isISOControl(c) -> {}
                else -> { flush(); out.add(c.toString()) } // пунктуация — отдельный токен
            }
        }
        flush()
        return out
    }

    private fun wordPiece(word: String): List<Int> {
        if (word.length > 100) return listOf(unkId)
        val out = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) { found = id; break }
                end--
            }
            if (found == -1) return listOf(unkId)
            out.add(found)
            start = end
        }
        return out
    }
}
