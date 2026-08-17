package io.github.paper.classhelper.knowledge

import android.content.Context
import android.net.Uri

/** Stable extension point for PPTX/DOCX/Markdown importers. */
interface DocumentImporter {
    fun supports(mime: String?, uri: Uri): Boolean
    suspend fun import(context: Context, uri: Uri): ImportedDocument
}

data class ImportedSection(val index: Int, val title: String, val text: String)
data class ImportedDocument(val title: String, val sections: List<ImportedSection>)
