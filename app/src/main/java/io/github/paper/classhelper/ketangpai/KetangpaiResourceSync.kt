package io.github.paper.classhelper.ketangpai

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.paper.classhelper.SettingsStore
import io.github.paper.classhelper.data.ChunkRow
import io.github.paper.classhelper.data.CourseDb
import io.github.paper.classhelper.data.DocumentRow
import io.github.paper.classhelper.knowledge.DocxImporter
import io.github.paper.classhelper.knowledge.PptxImporter
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** Imports one KETANGPAI course into the local retrieval database without turning it into the active PDF. */
class KetangpaiResourceSync(
    context: Context,
    private val db: CourseDb,
    private val settings: SettingsStore,
) {
    private val appContext = context.applicationContext

    suspend fun sync(
        client: KetangpaiClient,
        course: KetangpaiCourse,
        onProgress: (String) -> Unit = {},
    ): KetangpaiSyncResult = withContext(Dispatchers.IO) {
        require(client.ensureSession()) { "课堂派登录已失效，请重新登录" }
        onProgress("正在读取《${course.name}》课程资料…")
        val resources = client.listResources(course)
        val documentId = courseDocumentId(course)
        val stagingId = "$documentId:sync"
        val pending = ArrayList<ChunkRow>(CHUNK_BATCH_SIZE)
        val database = db.writableDatabase
        database.delete("document_chunks", "document_id=?", arrayOf(stagingId))

        var chunkIndex = 0
        var pdfPages = 0
        var officeSections = 0
        var failedFiles = 0

        fun flush() {
            if (pending.isEmpty()) return
            database.beginTransaction()
            try {
                val stmt = database.compileStatement("INSERT INTO document_chunks(document_id,page,title,text) VALUES(?,?,?,?)")
                try {
                    pending.forEach { ch ->
                        stmt.clearBindings()
                        stmt.bindString(1, ch.documentId)
                        stmt.bindLong(2, ch.page.toLong())
                        stmt.bindString(3, ch.title)
                        stmt.bindString(4, ch.text)
                        stmt.executeInsert()
                    }
                } finally {
                    stmt.close()
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            pending.clear()
        }

        fun addChunk(title: String, raw: String) {
            val clean = raw.replace(Regex("[\\t\\r ]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
            if (clean.length < 2) return
            clean.chunked(MAX_CHUNK_CHARS).forEachIndexed { part, text ->
                pending += ChunkRow(stagingId, chunkIndex++, title + if (part == 0) "" else " · ${part + 1}", text)
                if (pending.size >= CHUNK_BATCH_SIZE) flush()
            }
        }

        try {
            addChunk(
                course.name,
                buildString {
                    appendLine("课堂派课程：${course.name}")
                    if (course.teacher.isNotBlank()) appendLine("教师：${course.teacher}")
                    appendLine("本地同步资料数：${resources.size}")
                },
            )

            resources.forEachIndexed { index, resource ->
                onProgress("${index + 1}/${resources.size} · ${resource.name}")
                addChunk(
                    "${course.name} · ${resource.name}",
                    buildString {
                        appendLine("课程：${course.name}")
                        appendLine("资料：${resource.name}")
                        if (resource.sourceTitle.isNotBlank() && resource.sourceTitle != resource.name) appendLine("来源：${resource.sourceTitle}")
                        if (resource.contentType.isNotBlank()) appendLine("内容类型：${resource.contentType}")
                        if (resource.size.isNotBlank()) appendLine("大小：${resource.size}")
                        if (resource.url.isNotBlank()) appendLine("资源地址：${resource.url}")
                    },
                )

                if (resource.url.isBlank()) return@forEachIndexed
                val ext = resource.extension
                if (ext !in INDEXABLE_EXTENSIONS) return@forEachIndexed
                val temp = File(appContext.cacheDir, "ketangpai-sync/${safeName(resource.id)}.${ext.ifBlank { "bin" }}")
                temp.parentFile?.mkdirs()
                temp.delete()
                if (!client.downloadResource(resource, temp, MAX_FILE_BYTES)) {
                    failedFiles++
                    return@forEachIndexed
                }
                try {
                    when (ext) {
                        "pdf" -> pdfPages += extractPdf(temp, "${resource.name}", ::addChunk)
                        "docx" -> officeSections += extractDocx(temp, resource.name, ::addChunk)
                        "pptx" -> officeSections += extractPptx(temp, resource.name, ::addChunk)
                        "txt", "md" -> addChunk(resource.name, temp.readText(Charsets.UTF_8).take(MAX_TEXT_FILE_CHARS))
                        "html", "htm" -> addChunk(resource.name, Jsoup.parse(temp.readText(Charsets.UTF_8).take(MAX_TEXT_FILE_CHARS)).text())
                    }
                } catch (_: Throwable) {
                    failedFiles++
                } finally {
                    temp.delete()
                }
            }
            flush()

            database.beginTransaction()
            try {
                database.delete("document_chunks", "document_id=?", arrayOf(documentId))
                database.execSQL(
                    "INSERT INTO document_chunks(document_id,page,title,text) SELECT ?,page,title,text FROM document_chunks WHERE document_id=? ORDER BY page",
                    arrayOf(documentId, stagingId),
                )
                database.delete("document_chunks", "document_id=?", arrayOf(stagingId))
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }

            db.upsertDocument(
                DocumentRow(
                    id = documentId,
                    sourceUri = "ketangpai://course/${course.id}",
                    workingPath = "",
                    title = "课堂派 · ${course.name}",
                    dirty = false,
                    kind = "ketangpai",
                    indexedAt = System.currentTimeMillis(),
                ),
            )
            settings.ketangpaiCourseId = course.id
            settings.ketangpaiCourseName = course.name
            settings.ketangpaiCourseDocumentId = documentId
            settings.ketangpaiLastSync = System.currentTimeMillis()

            KetangpaiSyncResult(
                documentId = documentId,
                resources = resources.size,
                indexedChunks = chunkIndex,
                extractedPdfPages = pdfPages,
                importedOfficeSections = officeSections,
                failedFiles = failedFiles,
            )
        } finally {
            pending.clear()
            database.delete("document_chunks", "document_id=?", arrayOf(stagingId))
        }
    }

    private fun extractPdf(file: File, title: String, add: (String, String) -> Unit): Int {
        var pages = 0
        PDDocument.load(file, "", MemoryUsageSetting.setupTempFileOnly()).use { document ->
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
        return pages
    }

    private suspend fun extractDocx(file: File, title: String, add: (String, String) -> Unit): Int {
        val imported = DocxImporter().import(appContext, Uri.fromFile(file))
        imported.sections.forEach { section ->
            if (section.text.isNotBlank()) add("$title · ${section.title}", section.text.take(MAX_OFFICE_SECTION_CHARS))
        }
        return imported.sections.size
    }

    private suspend fun extractPptx(file: File, title: String, add: (String, String) -> Unit): Int {
        val imported = PptxImporter().import(appContext, Uri.fromFile(file))
        imported.sections.forEach { section ->
            if (section.text.isNotBlank()) add("$title · ${section.title}", section.text.take(MAX_OFFICE_SECTION_CHARS))
        }
        return imported.sections.size
    }

    private fun courseDocumentId(course: KetangpaiCourse): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(course.id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "ktp-" + hex.take(28)
    }

    private fun safeName(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    companion object {
        private const val CHUNK_BATCH_SIZE = 24
        private const val MAX_CHUNK_CHARS = 16_000
        private const val MAX_PDF_PAGE_CHARS = 16_000
        private const val MAX_OFFICE_SECTION_CHARS = 24_000
        private const val MAX_TEXT_FILE_CHARS = 2_000_000
        private const val MIN_PDF_TEXT_CHARS = 12
        private const val MAX_PDF_PAGES = 600
        private const val MAX_FILE_BYTES = 100L * 1024L * 1024L
        private val INDEXABLE_EXTENSIONS = setOf("pdf", "docx", "pptx", "txt", "md", "html", "htm")
    }
}
