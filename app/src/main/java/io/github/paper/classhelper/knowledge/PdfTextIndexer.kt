package io.github.paper.classhelper.knowledge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.paper.classhelper.SettingsStore
import io.github.paper.classhelper.data.ChunkRow
import io.github.paper.classhelper.data.CourseDb
import io.github.paper.classhelper.ocr.OcrModelManager
import io.github.paper.classhelper.ocr.PpOcrV6Engine
import io.github.paper.classhelper.pdf.PdfWorkspaceManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Text-layer first. Image-only pages use PP-OCRv6 when installed, and ML Kit remains a fail-safe.
 * Explicit force OCR means all pages are rendered/recognized; normal indexing OCRs only sparse pages.
 */
class PdfTextIndexer(
    private val context: Context,
    private val db: CourseDb,
    private val settings: SettingsStore,
    private val ocrModels: OcrModelManager? = null
) {
    suspend fun index(
        workspace: PdfWorkspaceManager.Workspace,
        force: Boolean = false,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Int {
        val row = db.getDocument(workspace.id)
        if (!force && (row?.indexedAt ?: 0L) > 0L) {
            val existing = db.allChunks(workspace.id, 10_000)
            if (existing.isNotEmpty()) return existing.size
        }

        val chunks = mutableListOf<ChunkRow>()
        val ocrPages = mutableListOf<Int>()
        PDDocument.load(workspace.workingFile).use { doc ->
            val stripper = PDFTextStripper()
            for (page in 0 until doc.numberOfPages) {
                stripper.startPage = page + 1; stripper.endPage = page + 1
                val text = runCatching { stripper.getText(doc).trim() }.getOrDefault("")
                if (force || text.replace(Regex("\\s+"), "").length < MIN_TEXT_CHARS) ocrPages += page
                chunks += ChunkRow(workspace.id, page, "${workspace.title} · P${page + 1}", text.take(MAX_PAGE_CHARS))
                onProgress(page + 1, doc.numberOfPages, "提取 PDF 文本")
            }
        }

        if ((settings.autoOcr || force) && ocrPages.isNotEmpty()) {
            val recognized = ocrPages(workspace, ocrPages, force, onProgress)
            for ((page, text) in recognized) if (text.isNotBlank() && page in chunks.indices) {
                chunks[page] = chunks[page].copy(text = text.trim().take(MAX_PAGE_CHARS))
            }
        }
        db.replaceChunks(workspace.id, chunks)
        return chunks.size
    }

    private suspend fun ocrPages(
        workspace: PdfWorkspaceManager.Workspace,
        pages: List<Int>,
        force: Boolean,
        onProgress: (Int, Int, String) -> Unit
    ): Map<Int, String> {
        val out = linkedMapOf<Int, String>()
        val pfd = ParcelFileDescriptor.open(workspace.workingFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val mlKit = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val rapid = ocrModels?.modelDirectory()?.let { dir -> runCatching { PpOcrV6Engine(dir) }.getOrNull() }
        try {
            PdfRenderer(pfd).use { renderer ->
                pages.forEachIndexed { index, pageIndex ->
                    if (pageIndex !in 0 until renderer.pageCount) return@forEachIndexed
                    val usingRapid = rapid != null
                    val phase = if (usingRapid) "PP-OCRv6 高精度识别 P${pageIndex + 1}" else "兼容 OCR（ML Kit）P${pageIndex + 1}"
                    onProgress(index + 1, pages.size, phase)
                    renderer.openPage(pageIndex).use { page ->
                        val target = when {
                            usingRapid && (settings.ocrHighAccuracy || force) -> RAPID_LONG_EDGE_HIGH
                            usingRapid -> RAPID_LONG_EDGE
                            force -> MLKIT_LONG_EDGE_HIGH
                            else -> MLKIT_LONG_EDGE
                        }
                        val scale = renderScale(page.width, page.height, target)
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val rapidText = if (rapid != null) runCatching {
                                rapid.recognize(bitmap, settings.ocrHighAccuracy || force).text
                            }.getOrDefault("") else ""
                            if (rapidText.isNotBlank()) out[pageIndex] = rapidText
                            else {
                                if (rapid != null) onProgress(index + 1, pages.size, "PP-OCRv6 未得到有效文字，自动兼容识别 P${pageIndex + 1}")
                                out[pageIndex] = recognizeMlKit(mlKit, bitmap)
                            }
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } finally {
            rapid?.close(); mlKit.close(); pfd.close()
        }
        return out
    }

    private fun renderScale(width: Int, height: Int, targetLongEdge: Int): Float {
        val maxSide = maxOf(width, height).coerceAtLeast(1)
        var scale = targetLongEdge.toFloat() / maxSide
        scale = scale.coerceIn(1f, 4f)
        val pixels = width.toDouble() * height.toDouble() * scale * scale
        if (pixels > MAX_RENDER_PIXELS) scale *= sqrt(MAX_RENDER_PIXELS / pixels).toFloat()
        return scale.coerceAtLeast(1f)
    }

    private suspend fun recognizeMlKit(recognizer: TextRecognizer, bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
            .addOnFailureListener { if (cont.isActive) cont.resume("") }
    }

    companion object {
        private const val MIN_TEXT_CHARS = 20
        private const val MAX_PAGE_CHARS = 30_000
        private const val MLKIT_LONG_EDGE = 1600
        private const val MLKIT_LONG_EDGE_HIGH = 2200
        private const val RAPID_LONG_EDGE = 2400
        private const val RAPID_LONG_EDGE_HIGH = 3000
        private const val MAX_RENDER_PIXELS = 12_000_000.0
    }
}
