package io.github.paper.classhelper.chaoxing

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.paper.classhelper.SettingsStore
import io.github.paper.classhelper.data.ChunkRow
import io.github.paper.classhelper.data.CourseDb
import io.github.paper.classhelper.data.DocumentRow
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Imports one authorized Chaoxing course into ClassHelper's local retrieval database.
 * Chapter/card text and readable PDF courseware become searchable chunks. Media is represented by
 * metadata + its normal authorized URL instead of being buffered in the app heap.
 */
class ChaoxingResourceSync(
    context: Context,
    private val db: CourseDb,
    private val settings: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun sync(
        client: ChaoxingClient,
        course: ChaoxingCourse,
        onProgress: (String) -> Unit = {},
    ): ChaoxingSyncResult = withContext(Dispatchers.IO) {
        require(client.ensureSession()) { "学习通登录已失效，请重新登录" }
        onProgress("正在读取《${course.name}》章节…")
        val chapters = client.listChapters(course)
        require(chapters.isNotEmpty()) { "没有读取到课程章节；可能课程尚未开放" }

        val documentId = courseDocumentId(course)
        val chunks = mutableListOf<ChunkRow>()
        var chunkIndex = 0
        var resourceCount = 0
        var pdfPages = 0
        var skippedLarge = 0

        fun addChunk(title: String, rawText: String) {
            val clean = rawText.replace(Regex("[\\t\\r ]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n").trim()
            if (clean.length < 2) return
            clean.chunked(MAX_CHUNK_CHARS).forEachIndexed { part, text ->
                val suffix = if (part == 0) "" else " · ${part + 1}"
                chunks += ChunkRow(documentId, chunkIndex++, title + suffix, text)
            }
        }

        chapters.forEachIndexed { chapterIndex, chapter ->
            onProgress("${chapterIndex + 1}/${chapters.size} · ${chapter.title}")
            val chapterText = StringBuilder()
            chapterText.appendLine("课程：${course.name}")
            if (course.teacher.isNotBlank()) chapterText.appendLine("教师：${course.teacher}")
            chapterText.appendLine("章节：${chapter.title}")

            val count = runCatching { client.cardCount(course, chapter) }.getOrDefault(0)
            for (cardIndex in 0 until count) {
                val card = runCatching { client.loadCard(course, chapter, cardIndex) }.getOrNull() ?: continue
                val inline = extractReadableText(card)
                if (inline.isNotBlank()) {
                    chapterText.appendLine()
                    chapterText.appendLine("[章节内容 ${cardIndex + 1}]")
                    chapterText.appendLine(inline)
                }

                val attachments = card.optJSONArray("attachments") ?: JSONArray()
                for (i in 0 until attachments.length()) {
                    val attachment = attachments.optJSONObject(i) ?: continue
                    val property = attachment.optJSONObject("property") ?: JSONObject()
                    val type = attachment.optString("type").ifBlank {
                        if (property.optString("bookname").isNotBlank()) "book" else "resource"
                    }
                    val name = property.optString("name").ifBlank { property.optString("bookname") }
                        .ifBlank { "$type ${i + 1}" }
                    val objectId = property.optString("objectid").takeIf { it.isNotBlank() }
                    resourceCount++

                    val resolved = objectId?.let { runCatching { client.resolveResource(it) }.getOrNull() }
                    chapterText.appendLine()
                    chapterText.appendLine("[资源] $name · $type")
                    property.optString("description").takeIf { it.isNotBlank() }?.let {
                        chapterText.appendLine(cleanHtml(it))
                    }
                    resolved?.sourceUrl?.let { chapterText.appendLine("资源地址：$it") }

                    val pdfUrl = resolved?.pdfUrl
                    if (pdfUrl != null && objectId != null) {
                        val temp = File(appContext.cacheDir, "chaoxing/${safeName(objectId)}.pdf")
                        temp.parentFile?.mkdirs()
                        onProgress("${chapterIndex + 1}/${chapters.size} · 读取课件：$name")
                        if (downloadTo(pdfUrl, temp, MAX_PDF_BYTES)) {
                            pdfPages += extractPdf(temp, "$name · ${chapter.title}", ::addChunk)
                        } else {
                            skippedLarge++
                            chapterText.appendLine("课件过大或下载失败，已保留在线资源地址。")
                        }
                        temp.delete()
                    }
                }
            }
            addChunk("${course.name} · ${chapter.title}", chapterText.toString())
        }

        val source = buildString {
            append("chaoxing://course/").append(course.courseId)
            append("?clazzId=").append(course.classId)
            if (course.cpi.isNotBlank()) append("&cpi=").append(course.cpi)
        }
        db.upsertDocument(
            DocumentRow(
                id = documentId,
                sourceUri = source,
                workingPath = "",
                title = "学习通 · ${course.name}",
                dirty = false,
                kind = "chaoxing",
                indexedAt = System.currentTimeMillis(),
            )
        )
        db.replaceChunks(documentId, chunks)
        settings.chaoxingCourseId = course.courseId
        settings.chaoxingClassId = course.classId
        settings.chaoxingCpi = course.cpi
        settings.chaoxingCourseName = course.name
        settings.chaoxingCourseDocumentId = documentId
        settings.chaoxingLastSync = System.currentTimeMillis()

        ChaoxingSyncResult(
            documentId = documentId,
            chapters = chapters.size,
            resources = resourceCount,
            indexedChunks = chunks.size,
            extractedPdfPages = pdfPages,
            skippedLargeFiles = skippedLarge,
        )
    }

    private fun downloadTo(url: String, file: File, maxBytes: Long): Boolean {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", settings.chaoxingCookie)
            .header("Referer", "https://mooc1-2.chaoxing.com/")
            .get().build()
        return try {
            http.newCall(request).execute().use download@{ response ->
                if (!response.isSuccessful) return@download false
                val body = response.body ?: return@download false
                val declared = body.contentLength()
                if (declared > maxBytes) return@download false
                var total = 0L
                var tooLarge = false
                file.outputStream().buffered(128 * 1024).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) {
                                tooLarge = true
                                break
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (tooLarge) {
                    file.delete()
                    false
                } else file.isFile && file.length() > 0
            }
        } catch (_: Throwable) {
            file.delete()
            false
        }
    }

    private fun extractPdf(file: File, title: String, add: (String, String) -> Unit): Int {
        return runCatching {
            var pages = 0
            PDDocument.load(file).use { document ->
                val stripper = PDFTextStripper()
                val count = minOf(document.numberOfPages, MAX_PDF_PAGES)
                for (page in 0 until count) {
                    stripper.startPage = page + 1
                    stripper.endPage = page + 1
                    val text = stripper.getText(document).trim()
                    if (text.length >= MIN_PDF_TEXT_CHARS) add("$title · P${page + 1}", text.take(MAX_PDF_PAGE_CHARS))
                    pages++
                }
            }
            pages
        }.getOrDefault(0)
    }

    private fun extractReadableText(root: JSONObject): String {
        val out = LinkedHashSet<String>()
        var usedChars = 0
        fun walk(value: Any?, key: String = "", depth: Int = 0) {
            if (depth > 8 || usedChars >= MAX_CARD_TEXT_CHARS) return
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.equals("attachments", true) || k.equals("defaults", true)) continue
                        walk(value.opt(k), k, depth + 1)
                    }
                }
                is JSONArray -> for (i in 0 until minOf(value.length(), 100)) walk(value.opt(i), key, depth + 1)
                is String -> {
                    if (key.lowercase() !in READABLE_KEYS) return
                    val text = cleanHtml(value)
                    if (text.length in 2..MAX_SINGLE_FIELD_CHARS && !looksOpaque(text) && out.add(text)) {
                        usedChars += text.length
                    }
                }
            }
        }
        walk(root)
        return out.joinToString("\n").take(MAX_CARD_TEXT_CHARS)
    }

    private fun cleanHtml(value: String): String = Jsoup.parse(value).text()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun looksOpaque(value: String): Boolean {
        if (value.startsWith("http://") || value.startsWith("https://")) return true
        if (value.length >= 24 && value.count { it.isLetterOrDigit() } > value.length * 0.92 && value.none { it.isWhitespace() }) return true
        return false
    }

    private fun courseDocumentId(course: ChaoxingCourse): String {
        val raw = "${course.courseId}:${course.classId}"
        val hex = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "cx-" + hex.take(28)
    }

    private fun safeName(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152.0 Mobile Safari/537.36 ClassHelper/1.0"
        private const val MAX_CHUNK_CHARS = 24_000
        private const val MAX_CARD_TEXT_CHARS = 24_000
        private const val MAX_SINGLE_FIELD_CHARS = 12_000
        private const val MAX_PDF_PAGE_CHARS = 30_000
        private const val MIN_PDF_TEXT_CHARS = 12
        private const val MAX_PDF_PAGES = 600
        private const val MAX_PDF_BYTES = 80L * 1024L * 1024L
        private val READABLE_KEYS = setOf(
            "name", "bookname", "title", "content", "description", "text", "introduction",
            "abstract", "caption", "author", "knowledgeName", "chapterName", "label", "subtitle"
        ).map { it.lowercase() }.toSet()
    }
}
