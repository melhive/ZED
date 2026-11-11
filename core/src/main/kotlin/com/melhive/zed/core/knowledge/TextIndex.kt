package com.melhive.zed.core.knowledge

import kotlin.math.ln

/** Splits text into overlapping chunks on paragraph/sentence boundaries. */
object TextChunker {
    fun chunk(text: String, targetChars: Int = 900, overlapChars: Int = 150): List<String> {
        val clean = text.replace("\r\n", "\n").replace(Regex("[ \\t]+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= targetChars) return listOf(clean)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + targetChars, clean.length)
            if (end < clean.length) {
                val window = clean.substring(start, end)
                val cut = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "), window.lastIndexOf('\n'), window.lastIndexOf(' '))
                    .firstOrNull { it > targetChars / 2 }
                if (cut != null) end = start + cut + 1
            }
            chunks += clean.substring(start, end).trim()
            if (end >= clean.length) break
            start = maxOf(end - overlapChars, start + 1)
        }
        return chunks.filter { it.isNotBlank() }
    }
}

object Tokenizer {
    private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were", "it", "this", "that", "with", "as", "at", "by", "be", "from", "my", "me", "i", "you", "what", "how", "do", "does")
    fun tokens(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }
}

/**
 * In-memory BM25 ranking over candidate chunks. The Android layer uses SQLite
 * FTS to fetch candidates and this scorer to rank them consistently.
 */
class Bm25(private val k1: Double = 1.2, private val b: Double = 0.75) {
    data class Doc(val id: Long, val text: String)
    data class Hit(val id: Long, val score: Double)

    fun rank(query: String, docs: List<Doc>, limit: Int = 5): List<Hit> {
        val q = Tokenizer.tokens(query).distinct()
        if (q.isEmpty() || docs.isEmpty()) return emptyList()
        val tokenized = docs.map { it.id to Tokenizer.tokens(it.text) }
        val avgLen = tokenized.map { it.second.size }.average().coerceAtLeast(1.0)
        val df = q.associateWith { term -> tokenized.count { term in it.second } }
        val n = docs.size.toDouble()
        return tokenized.map { (id, toks) ->
            val tf = toks.groupingBy { it }.eachCount()
            var score = 0.0
            for (term in q) {
                val f = tf[term] ?: continue
                val idf = ln(1 + (n - df.getValue(term) + 0.5) / (df.getValue(term) + 0.5))
                score += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * toks.size / avgLen))
            }
            Hit(id, score)
        }.filter { it.score > 0 }.sortedByDescending { it.score }.take(limit)
    }
}
