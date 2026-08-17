package io.github.paper.classhelper.knowledge

import io.github.paper.classhelper.data.ChunkRow
import io.github.paper.classhelper.data.CourseDb
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight on-device retrieval.
 *
 * SQLite FTS is fast for Latin/space-delimited text, while Chinese classroom
 * speech often has no token boundaries. A small character n-gram fallback is
 * therefore used for Chinese so PDF matching does not depend on an embedding
 * model staying resident in RAM.
 */
class KnowledgeRepository(private val db: CourseDb) {
    data class ContextHit(val label: String, val page: Int?, val text: String)
    data class PageMatch(val page: Int, val label: String, val confidence: Float)

    private val documentCache = ConcurrentHashMap<String, List<ChunkRow>>()

    fun invalidate(documentId: String) {
        documentCache.remove(documentId)
    }

    fun invalidateAll() {
        documentCache.clear()
    }

    fun retrieve(question: String, currentDocumentId: String?, currentPage: Int, maxChars: Int = 12_000): List<ContextHit> {
        val out = LinkedHashMap<String, ContextHit>()
        if (currentDocumentId != null) {
            // Current reading location is deliberately first: it is cheap and usually
            // the most relevant classroom context even before lexical search succeeds.
            db.chunksNearPage(currentDocumentId, currentPage, 2).forEach { ch ->
                out["${ch.documentId}:${ch.page}"] = ch.toHit()
            }
            db.pdfNotes(currentDocumentId, currentPage).forEach { ch ->
                out["note:${ch.documentId}:${ch.page}:${ch.text.hashCode()}"] = ch.toHit()
            }
            hybridSearch(currentDocumentId, question, 8).forEach { ch ->
                out["${ch.documentId}:${ch.page}"] = ch.toHit()
            }
        }

        // Search all imported materials too. FTS candidates are cheap; the n-gram
        // fallback is capped so a large library cannot monopolize a question request.
        db.searchChunks(null, question, 8).forEach { ch ->
            out.putIfAbsent("${ch.documentId}:${ch.page}", ch.toHit())
        }
        lexicalRank(db.allChunksAcross(2_000), question, 8).forEach { ch ->
            out.putIfAbsent("${ch.documentId}:${ch.page}", ch.toHit())
        }

        var used = 0
        return buildList {
            out.values.forEach { h ->
                if (used >= maxChars) return@forEach
                val remain = maxChars - used
                val clipped = if (h.text.length > remain) h.copy(text = h.text.take(remain)) else h
                add(clipped)
                used += clipped.text.length
            }
        }
    }

    /** Match a finalized classroom utterance to the most likely page of the current PDF. */
    fun matchPage(speech: String, currentDocumentId: String): PageMatch? {
        val terms = lexicalTerms(speech)
        if (terms.size < 2) return null
        val scored = rankWithScore(cachedDocument(currentDocumentId), terms, 1).firstOrNull() ?: return null
        // Conservative display threshold: this only suggests a page, it never forces navigation.
        if (scored.second < 0.20f) return null
        return PageMatch(scored.first.page, scored.first.title, scored.second)
    }

    private fun hybridSearch(documentId: String, query: String, limit: Int): List<ChunkRow> {
        val merged = LinkedHashMap<String, ChunkRow>()
        db.searchChunks(documentId, query, limit).forEach { merged["${it.documentId}:${it.page}"] = it }
        lexicalRank(cachedDocument(documentId), query, limit).forEach { merged.putIfAbsent("${it.documentId}:${it.page}", it) }
        return merged.values.take(limit)
    }

    private fun cachedDocument(documentId: String): List<ChunkRow> {
        val cached = documentCache[documentId]
        if (!cached.isNullOrEmpty()) return cached
        val loaded = db.allChunks(documentId, 1_500)
        // Do not cache empty results: indexing may still be running in the background.
        if (loaded.isNotEmpty()) documentCache[documentId] = loaded
        return loaded
    }

    private fun lexicalRank(candidates: List<ChunkRow>, query: String, limit: Int): List<ChunkRow> =
        rankWithScore(candidates, lexicalTerms(query), limit).map { it.first }

    private fun rankWithScore(candidates: List<ChunkRow>, terms: List<String>, limit: Int): List<Pair<ChunkRow, Float>> {
        if (terms.isEmpty() || candidates.isEmpty()) return emptyList()
        return candidates.asSequence().mapNotNull { chunk ->
            val haystack = (chunk.title + "\n" + chunk.text.take(18_000)).lowercase()
            var hits = 0f
            for (term in terms) {
                if (term in haystack) hits += if (term.length >= 3) 1.35f else 1f
            }
            if (hits <= 0f) null else chunk to (hits / terms.sumOf { if (it.length >= 3) 1.35 else 1.0 }.toFloat()).coerceAtMost(1f)
        }.sortedByDescending { it.second }.take(limit).toList()
    }

    private fun lexicalTerms(raw: String): List<String> {
        var text = raw.lowercase().replace(Regex("\\s+"), "")
        // Remove high-frequency classroom/question scaffolding before building Chinese n-grams.
        listOf(
            "为什么", "是什么", "怎么", "如何", "什么", "请问", "这个问题", "大家想一下", "谁来回答",
            "老师", "那么", "所以", "我们", "这里", "一下", "是不是", "有没有"
        ).forEach { text = text.replace(it, "") }

        val terms = LinkedHashSet<String>()
        Regex("[a-z0-9][a-z0-9_+.#-]{1,}").findAll(raw.lowercase()).forEach { terms += it.value }
        val zhRuns = Regex("[\\p{IsHan}]{2,}").findAll(text).map { it.value }
        for (run in zhRuns) {
            // Trigrams carry more precision; bigrams protect short technical terms.
            if (run.length >= 3) run.windowed(3).forEach { terms += it }
            run.windowed(2).forEach { terms += it }
        }
        return terms.take(36)
    }

    private fun ChunkRow.toHit() = ContextHit("$title · P${page + 1}", page, text)
}
