package io.github.paper.classhelper.knowledge

import io.github.paper.classhelper.data.ChunkRow
import io.github.paper.classhelper.data.CourseDb

/**
 * Low-memory on-device retrieval. The active PDF stays first; a bound Chaoxing course can be
 * supplied as a second preferred document before the global library fallback.
 *
 * Important: never cache or materialize an entire document here. Large PDFs plus PDFView and the
 * local ASR model can otherwise push 256 MiB Android heaps over the limit. Chinese fallback search
 * is performed in SQLite with a small LIKE candidate set, then ranked in memory.
 */
class KnowledgeRepository(private val db: CourseDb) {
    data class ContextHit(val label: String, val page: Int?, val text: String)
    data class PageMatch(val page: Int, val label: String, val confidence: Float)

    /** Kept for callers that index/import documents; retrieval no longer keeps a document cache. */
    fun invalidate(documentId: String) = Unit
    fun invalidateAll() = Unit

    fun retrieve(
        question: String,
        currentDocumentId: String?,
        currentPage: Int,
        preferredDocumentId: String? = null,
        maxChars: Int = 12_000,
    ): List<ContextHit> {
        val out = LinkedHashMap<String, ContextHit>()
        if (currentDocumentId != null) {
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

        if (preferredDocumentId != null && preferredDocumentId != currentDocumentId) {
            hybridSearch(preferredDocumentId, question, 10).forEach { ch ->
                out.putIfAbsent("${ch.documentId}:${ch.page}", ch.toHit())
            }
            if (out.values.none { it.label.startsWith("学习通 ·") }) {
                db.allChunks(preferredDocumentId, 2).forEach { ch ->
                    out.putIfAbsent("${ch.documentId}:${ch.page}", ch.toHit())
                }
            }
        }

        // FTS remains the cheap global first pass. The bounded lexical fallback below is only for
        // queries (especially Chinese) that the tokenizer did not match well.
        db.searchChunks(null, question, 8).forEach { ch ->
            out.putIfAbsent("${ch.documentId}:${ch.page}", ch.toHit())
        }
        lexicalRank(db.allChunksAcross(GLOBAL_FALLBACK_CANDIDATES), question, 8).forEach { ch ->
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

    /** Match a finalized classroom utterance to the most likely page without loading the PDF text corpus. */
    fun matchPage(speech: String, currentDocumentId: String): PageMatch? {
        val terms = lexicalTerms(speech)
        if (terms.size < 2) return null
        val candidates = LinkedHashMap<String, ChunkRow>()
        db.searchChunks(currentDocumentId, speech, 12).forEach {
            candidates["${it.documentId}:${it.page}"] = it
        }
        val likeQuery = terms.sortedByDescending { it.length }.take(LIKE_TERMS).joinToString(" ")
        if (likeQuery.isNotBlank()) {
            db.searchCurrentDocumentLike(currentDocumentId, likeQuery, PAGE_MATCH_CANDIDATES).forEach {
                candidates.putIfAbsent("${it.documentId}:${it.page}", it)
            }
        }
        val scored = rankWithScore(candidates.values.toList(), terms, 1).firstOrNull() ?: return null
        if (scored.second < 0.20f) return null
        return PageMatch(scored.first.page, scored.first.title, scored.second)
    }

    private fun hybridSearch(documentId: String, query: String, limit: Int): List<ChunkRow> {
        val merged = LinkedHashMap<String, ChunkRow>()
        db.searchChunks(documentId, query, limit).forEach { merged["${it.documentId}:${it.page}"] = it }

        val terms = lexicalTerms(query)
        val likeQuery = terms.sortedByDescending { it.length }.take(LIKE_TERMS).joinToString(" ")
        if (likeQuery.isNotBlank()) {
            val bounded = db.searchCurrentDocumentLike(
                documentId,
                likeQuery,
                maxOf(MIN_LIKE_CANDIDATES, limit * 4),
            )
            rankWithScore(bounded, terms, limit).forEach { (ch, _) ->
                merged.putIfAbsent("${ch.documentId}:${ch.page}", ch)
            }
        }
        return merged.values.take(limit)
    }

    private fun lexicalRank(candidates: List<ChunkRow>, query: String, limit: Int): List<ChunkRow> =
        rankWithScore(candidates, lexicalTerms(query), limit).map { it.first }

    private fun rankWithScore(candidates: List<ChunkRow>, terms: List<String>, limit: Int): List<Pair<ChunkRow, Float>> {
        if (terms.isEmpty() || candidates.isEmpty()) return emptyList()
        val denominator = terms.sumOf { if (it.length >= 3) 1.35 else 1.0 }.toFloat().coerceAtLeast(1f)
        return candidates.asSequence().mapNotNull { chunk ->
            // Do not manufacture another 18k-character copy for every one of thousands of rows;
            // candidate lists are deliberately small and the inspected text is capped.
            val haystack = (chunk.title + "\n" + chunk.text.take(RANK_TEXT_CHARS)).lowercase()
            var hits = 0f
            for (term in terms) {
                if (term in haystack) hits += if (term.length >= 3) 1.35f else 1f
            }
            if (hits <= 0f) null else chunk to (hits / denominator).coerceAtMost(1f)
        }.sortedByDescending { it.second }.take(limit).toList()
    }

    private fun lexicalTerms(raw: String): List<String> {
        var text = raw.lowercase().replace(Regex("\\s+"), "")
        listOf(
            "为什么", "是什么", "怎么", "如何", "什么", "请问", "这个问题", "大家想一下", "谁来回答",
            "老师", "那么", "所以", "我们", "这里", "一下", "是不是", "有没有"
        ).forEach { text = text.replace(it, "") }

        val terms = LinkedHashSet<String>()
        Regex("[a-z0-9][a-z0-9_+.#-]{1,}").findAll(raw.lowercase()).forEach { terms += it.value }
        val zhRuns = Regex("[\\p{IsHan}]{2,}").findAll(text).map { it.value }
        for (run in zhRuns) {
            if (run.length >= 3) run.windowed(3).forEach { terms += it }
            run.windowed(2).forEach { terms += it }
        }
        return terms.take(36)
    }

    private fun ChunkRow.toHit(): ContextHit = if (documentId.startsWith("cx-")) {
        ContextHit("学习通 · $title", null, text)
    } else {
        ContextHit("$title · P${page + 1}", page, text)
    }

    companion object {
        private const val GLOBAL_FALLBACK_CANDIDATES = 192
        private const val PAGE_MATCH_CANDIDATES = 48
        private const val MIN_LIKE_CANDIDATES = 24
        private const val LIKE_TERMS = 4
        private const val RANK_TEXT_CHARS = 10_000
    }
}
