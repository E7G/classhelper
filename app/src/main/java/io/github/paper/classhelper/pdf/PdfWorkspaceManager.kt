package io.github.paper.classhelper.pdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import io.github.paper.classhelper.data.CourseDb
import io.github.paper.classhelper.data.DocumentRow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Keeps a private working copy so PDFBox can safely edit the document.
 * Successful saves are copied back to the original SAF Uri when writable.
 */
class PdfWorkspaceManager(
    private val context: Context,
    private val db: CourseDb
) {
    private val root = File(context.filesDir, "pdf-workspaces").apply { mkdirs() }
    private val generatedRoot = File(context.filesDir, "generated-pdfs").apply { mkdirs() }

    data class Workspace(
        val id: String,
        val sourceUri: Uri,
        val workingFile: File,
        val title: String
    )

    /**
     * Creates an app-owned one-page A4 PDF and returns a FileProvider Uri that can be opened through
     * the exact same workspace/import path as any user-selected PDF. This keeps annotation, autosave,
     * export and crash-recovery behavior identical for newly created and imported documents.
     */
    fun createBlankSource(requestedName: String): Uri {
        val fileName = normalizePdfName(requestedName)
        val dir = File(generatedRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val source = File(dir, fileName)
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.A4))
            document.save(source)
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            source,
        )
    }

    fun open(sourceUri: Uri): Workspace {
        val key = sha256(sourceUri.toString())
        val existing = db.getDocument(key)
        if (existing != null && File(existing.workingPath).isFile) {
            return Workspace(existing.id, Uri.parse(existing.sourceUri), File(existing.workingPath), existing.title)
        }

        val title = displayName(sourceUri) ?: "document.pdf"
        val work = File(root, "$key.pdf")
        val tmp = File(root, "$key.importing")
        context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "无法读取 PDF" }
            FileOutputStream(tmp).use { output -> input.copyTo(output, 128 * 1024) }
        }
        if (work.exists()) work.delete()
        check(tmp.renameTo(work)) { "无法创建 PDF 工作副本" }

        db.upsertDocument(
            DocumentRow(
                id = key,
                sourceUri = sourceUri.toString(),
                workingPath = work.absolutePath,
                title = title,
                dirty = false
            )
        )
        return Workspace(key, sourceUri, work, title)
    }

    fun reopen(id: String): Workspace? {
        val row = db.getDocument(id) ?: return null
        val file = File(row.workingPath)
        if (!file.isFile) return null
        return Workspace(row.id, Uri.parse(row.sourceUri), file, row.title)
    }

    /** Copies the complete, already-validated working PDF back to the SAF Uri. */
    fun syncToSource(workspace: Workspace) {
        context.contentResolver.openOutputStream(workspace.sourceUri, "wt").use { out ->
            requireNotNull(out) { "原 PDF 不可写；工作副本已保留" }
            FileInputStream(workspace.workingFile).use { input -> input.copyTo(out, 128 * 1024) }
            out.flush()
        }
        db.setDocumentDirty(workspace.id, false)
    }

    fun exportTo(workspace: Workspace, destination: Uri) {
        context.contentResolver.openOutputStream(destination, "wt").use { out ->
            requireNotNull(out) { "目标位置不可写" }
            FileInputStream(workspace.workingFile).use { input -> input.copyTo(out, 128 * 1024) }
            out.flush()
        }
    }

    fun markDirty(id: String) = db.setDocumentDirty(id, true)

    private fun displayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun normalizePdfName(value: String): String {
        val base = value.trim()
            .removeSuffix(".pdf")
            .removeSuffix(".PDF")
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim(' ', '.')
            .take(96)
            .ifBlank { "空白文档" }
        return "$base.pdf"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32)
}
