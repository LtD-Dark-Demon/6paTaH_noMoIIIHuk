package com.bratan.assistant

import android.content.Context

/** WordPiece-токенизатор (lowercase). */
class BertTokenizer(private val vocab: Map<String, Int>) {
    private val cls = vocab["[CLS]"] ?: 101
    private val sep = vocab["[SEP]"] ?: 102
    private val unk = vocab["[UNK]"] ?: 100
    private val pad = vocab["[PAD]"] ?: 0

    fun encode(text: String, maxLen: Int): Pair<IntArray, IntArray> {
        val ids = ArrayList<Int>(maxLen)
        ids.add(cls)
        for (word in basicTokenize(text)) {
            if (ids.size >= maxLen - 1) break
            ids.addAll(wordPiece(word))
        }
        if (ids.size > maxLen - 1) while (ids.size > maxLen - 1) ids.removeAt(ids.size - 1)
        ids.add(sep)
        val input = IntArray(maxLen) { pad }
        val mask = IntArray(maxLen)
        for (i in ids.indices) { input[i] = ids[i]; mask[i] = 1 }
        return input to mask
    }

    private fun basicTokenize(text: String): List<String> =
        text.lowercase().replace(Regex("([\\p{Punct}])"), " $1 ").split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun wordPiece(word: String): List<Int> {
        if (word.length > 100) return listOf(unk)
        val out = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var id: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                vocab[sub]?.let { id = it }
                if (id != null) break
                end--
            }
            if (id == null) return listOf(unk)
            out.add(id!!)
            start = end
        }
        return out
    }

    companion object {
        fun fromAsset(context: Context, name: String): BertTokenizer? = try {
            val vocab = HashMap<String, Int>(32000)
            context.assets.open(name).bufferedReader().useLines { lines ->
                lines.forEachIndexed { i, line -> vocab[line.trim()] = i }
            }
            BertTokenizer(vocab)
        } catch (e: Exception) { null }
    }
}
