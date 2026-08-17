package io.github.paper.classhelper.knowledge

import android.content.Context
import android.net.Uri

class PlainTextImporter : DocumentImporter {
    override fun supports(mime: String?, uri: Uri): Boolean =
        mime == "text/plain" || mime == "text/markdown" || mime == "text/x-markdown" || uri.toString().endsWith(".md", true)

    override suspend fun import(context: Context, uri: Uri): ImportedDocument {
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        val sections = text.split(Regex("(?m)(?=^#{1,3}\\s+)")).filter { it.isNotBlank() }
            .mapIndexed { i, part ->
                val title = part.lineSequence().firstOrNull()?.removePrefix("#")?.trim().orEmpty().ifBlank { "第 ${i + 1} 节" }
                ImportedSection(i, title, part.trim())
            }
        return ImportedDocument(uri.lastPathSegment ?: "Markdown", sections.ifEmpty { listOf(ImportedSection(0, "正文", text)) })
    }
}
