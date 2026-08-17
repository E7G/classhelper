package io.github.paper.classhelper.knowledge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.paper.classhelper.data.ChunkRow
import io.github.paper.classhelper.data.CourseDb
import io.github.paper.classhelper.data.DocumentRow
import java.security.MessageDigest

class ReferenceImportManager(
    private val context: Context,
    private val db: CourseDb
) {
    private val importers: List<DocumentImporter> = listOf(PlainTextImporter(), DocxImporter(), PptxImporter())

    suspend fun import(uri: Uri): Result<Int> = runCatching {
        val mime = context.contentResolver.getType(uri)
        val importer = importers.firstOrNull { it.supports(mime, uri) }
            ?: error("暂不支持这种资料格式")
        val doc = importer.import(context, uri)
        val id = "ref-" + sha256(uri.toString()).take(28)
        val title = displayName(uri) ?: doc.title
        val kind = when (mime) {
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "text/markdown", "text/x-markdown" -> "md"
            "text/plain" -> if (title.endsWith(".md", true)) "md" else "txt"
            else -> title.substringAfterLast('.', "reference").lowercase()
        }
        db.upsertDocument(DocumentRow(id, uri.toString(), "", title, false, kind = kind, indexedAt = System.currentTimeMillis()))
        db.replaceChunks(id, doc.sections.map {
            ChunkRow(id, it.index, "$title · ${it.title}", it.text.take(30_000))
        })
        doc.sections.size
    }

    private fun displayName(uri: Uri): String? = context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
