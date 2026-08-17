package io.github.paper.classhelper.knowledge

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Small OOXML text extractors. No Apache POI is bundled, keeping the Android
 * runtime small. They intentionally extract reference text only, not layout.
 */
class DocxImporter : DocumentImporter {
    override fun supports(mime: String?, uri: Uri): Boolean =
        mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || uri.toString().endsWith(".docx", true)

    override suspend fun import(context: Context, uri: Uri): ImportedDocument {
        val bytes = readZipEntry(context, uri, "word/document.xml") ?: error("DOCX 缺少 word/document.xml")
        val paragraphs = extractXmlText(bytes, textTag = "t", paragraphTag = "p")
        return ImportedDocument(uri.lastPathSegment ?: "Word", chunkParagraphs(paragraphs))
    }
}

class PptxImporter : DocumentImporter {
    override fun supports(mime: String?, uri: Uri): Boolean =
        mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" || uri.toString().endsWith(".pptx", true)

    override suspend fun import(context: Context, uri: Uri): ImportedDocument {
        val slides = mutableListOf<Pair<Int, ByteArray>>()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val m = Regex("ppt/slides/slide(\\d+)\\.xml").matchEntire(entry.name)
                    if (m != null) slides += m.groupValues[1].toInt() to zip.readBytes()
                    zip.closeEntry(); entry = zip.nextEntry
                }
            }
        }
        val sections = slides.sortedBy { it.first }.map { (n, bytes) ->
            val lines = extractXmlText(bytes, textTag = "t", paragraphTag = "p")
            ImportedSection(n - 1, "第 $n 张", lines.joinToString("\n"))
        }.filter { it.text.isNotBlank() }
        return ImportedDocument(uri.lastPathSegment ?: "PowerPoint", sections)
    }
}

private fun readZipEntry(context: Context, uri: Uri, wanted: String): ByteArray? {
    context.contentResolver.openInputStream(uri).use { input ->
        if (input == null) return null
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == wanted) return zip.readBytes()
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
    }
    return null
}

private fun extractXmlText(bytes: ByteArray, textTag: String, paragraphTag: String): List<String> {
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
    val paragraphs = mutableListOf<String>()
    val current = StringBuilder()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> if (parser.name == textTag) {
                val next = parser.next()
                if (next == XmlPullParser.TEXT) current.append(parser.text)
            }
            XmlPullParser.END_TAG -> if (parser.name == paragraphTag && current.isNotBlank()) {
                paragraphs += current.toString().trim(); current.clear()
            }
        }
        event = parser.next()
    }
    if (current.isNotBlank()) paragraphs += current.toString().trim()
    return paragraphs
}

private fun chunkParagraphs(paragraphs: List<String>, chars: Int = 3500): List<ImportedSection> {
    val out = mutableListOf<ImportedSection>()
    val buf = StringBuilder()
    var index = 0
    for (p in paragraphs) {
        if (buf.length + p.length > chars && buf.isNotEmpty()) {
            out += ImportedSection(index, "第 ${index + 1} 节", buf.toString().trim()); index++; buf.clear()
        }
        buf.appendLine(p)
    }
    if (buf.isNotBlank()) out += ImportedSection(index, "第 ${index + 1} 节", buf.toString().trim())
    return out
}
