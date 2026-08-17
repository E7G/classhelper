package io.github.paper.classhelper.ui

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ahmer.pdfviewer.PDFView
import com.ahmer.pdfviewer.listener.OnErrorListener
import com.ahmer.pdfviewer.listener.OnLoadCompleteListener
import com.ahmer.pdfviewer.listener.OnPageChangeListener
import com.ahmer.pdfviewer.listener.OnPageScrollListener
import com.ahmer.pdfviewer.listener.OnRenderListener
import com.ahmer.pdfviewer.util.FitPolicy
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.R
import io.github.paper.classhelper.asr.AsrModelManager
import io.github.paper.classhelper.classroom.AutoNotePipeline
import io.github.paper.classhelper.classroom.ClassroomBus
import io.github.paper.classhelper.classroom.ClassroomService
import io.github.paper.classhelper.classroom.QuestionPipeline
import io.github.paper.classhelper.data.ChunkRow
import io.github.paper.classhelper.knowledge.PdfTextIndexer
import io.github.paper.classhelper.knowledge.ReferenceImportManager
import io.github.paper.classhelper.pdf.InkOverlayView
import io.github.paper.classhelper.pdf.PdfAnnotationWriter
import io.github.paper.classhelper.pdf.PdfWorkspaceManager
import io.github.paper.classhelper.util.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class ReaderActivity : AppCompatActivity() {
    private lateinit var app: ClassHelperApp
    private lateinit var pdfView: PDFView
    private lateinit var inkOverlay: InkOverlayView
    private lateinit var writer: PdfAnnotationWriter
    private lateinit var notes: AutoNotePipeline
    private lateinit var manualQuestions: QuestionPipeline
    private var workspace: PdfWorkspaceManager.Workspace? = null
    private var currentPage = 0
    private var pageCount = 0
    private var drawMode = false
    private var suggestedPage: Int? = null
    private var observedHistoryVersion = -1L
    private var selectedSessionId: String? = null
    private var pendingExportSessionId: String? = null
    private var eraserCommitJob: Job? = null
    private var annotationRefreshRunnable: Runnable? = null
    private val inkGeometryCache = mutableMapOf<Int, FloatArray>()
    private var inkGeometryWorkspaceId: String? = null
    private val chromeHandler = Handler(Looper.getMainLooper())
    private var chromeVisible = false
    private var pdfTouchDownX = 0f
    private var pdfTouchDownY = 0f
    private var pdfTouchMoved = false
    private var pdfTouchActive = false
    private var surfacedQuestion = ""
    private val autoHideChrome = Runnable { hideReaderChrome() }
    private val chromeAutoHideDelayMs = 6_000L
    private val chromeScrollHideDelayMs = 2_800L
    private sealed interface AnnotationAction {
        data class Add(val command: PdfAnnotationWriter.Command) : AnnotationAction
        data class Delete(val command: PdfAnnotationWriter.Command) : AnnotationAction
    }
    private val undoStack = ArrayDeque<AnnotationAction>()
    private val redoStack = ArrayDeque<AnnotationAction>()
    private val homeModelListener: (AsrModelManager.State) -> Unit = { state ->
        runOnUiThread { if (!isDestroyed) renderHomeModelState(state) }
    }

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }; openUri(it) }
    }
    private val importReferences = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        lifecycleScope.launch {
            findViewById<TextView>(R.id.saveStatusText).text = "正在导入 ${uris.size} 份资料…"
            val manager = ReferenceImportManager(this@ReaderActivity, app.graph.db)
            var ok = 0
            val errors = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                uris.forEach { uri -> manager.import(uri).onSuccess { ok++ }.onFailure { errors += (it.message ?: "未知错误") } }
            }
            app.graph.knowledge.invalidateAll()
            findViewById<TextView>(R.id.saveStatusText).text = "已导入 $ok/${uris.size} 份资料"
            if (errors.isNotEmpty()) toast(errors.first())
        }
    }
    private val saveAsPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val ws = workspace ?: return@registerForActivityResult
        if (uri != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                writer.flushNow(ws, syncSource = false).getOrThrow()
                runCatching { app.graph.workspace.exportTo(ws, uri) }
            }
            result.onSuccess { toast("已另存 PDF") }.onFailure { toast("另存失败：${it.message}") }
        }
    }
    private val exportSession = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val sessionId = pendingExportSessionId ?: return@registerForActivityResult
        if (uri != null) lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val text = buildSessionMarkdown(sessionId)
                contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            }.onSuccess { withContext(Dispatchers.Main) { toast("课堂记录已导出") } }
                .onFailure { withContext(Dispatchers.Main) { toast("导出失败：${it.message}") } }
        }
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestNotificationThenStart() else toast("需要麦克风权限才能帮你听课")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notification permission improves background visibility, but denying it must not block local ASR.
        startListeningService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        app = application as ClassHelperApp
        pdfView = findViewById(R.id.pdfView)
        inkOverlay = findViewById(R.id.inkOverlay)
        pdfView.setMinZoom(1f)
        pdfView.setMidZoom(2f)
        pdfView.setMaxZoom(4f)
        // Do NOT install an OnTouchListener on PDFView. AhmerPdfViewer owns its gesture
        // pipeline internally; replacing it can break pinch/drag after leaving annotation mode.
        // InkOverlayView sits above PDFView only while editing and consumes one-finger ink.
        writer = PdfAnnotationWriter(app.graph.db, app.graph.workspace, lifecycleScope)
        notes = AutoNotePipeline(app, lifecycleScope)
        manualQuestions = QuestionPipeline(this, lifecycleScope)

        configureSidePanelWidth()
        bindButtons(); bindInk(); observeClassroom()
        showEmptyState()

        val incoming = intent?.data
        when {
            incoming != null -> openUri(incoming)
            app.graph.settings.currentDocumentId != null -> app.graph.workspace.reopen(app.graph.settings.currentDocumentId!!)?.let { ws ->
                workspace = ws; showWorkspace(ws, app.graph.settings.currentPage)
            }
        }
        showPreviousCrashIfAny()
    }

    private fun showPreviousCrashIfAny() {
        val crash = CrashReporter.read(this) ?: return
        val preview = crash.take(4500)
        Md3eDialogUi.showConfirm(
            context = this,
            title = "检测到上一次闪退",
            message = preview,
            positiveLabel = "复制日志",
            negativeLabel = "清除",
            onNegative = { CrashReporter.clear(this) },
            onCancel = { CrashReporter.clear(this) },
        ) {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("ClassHelper crash", crash))
            CrashReporter.clear(this)
            toast("崩溃日志已复制")
        }
    }

    private fun bindButtons() {
        val openAction = View.OnClickListener { openPdf.launch(arrayOf("application/pdf")) }
        val settingsAction = View.OnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.openButton).setOnClickListener(openAction)
        findViewById<Button>(R.id.emptyOpenButton).setOnClickListener(openAction)
        findViewById<Button>(R.id.settingsButton).setOnClickListener(settingsAction)
        findViewById<Button>(R.id.emptySettingsButton).setOnClickListener(settingsAction)
        findViewById<Button>(R.id.emptyListenButton).setOnClickListener { requestListening() }
        findViewById<View>(R.id.chromeWakeHandle).setOnClickListener { showReaderChrome(autoHide = true) }
        findViewById<Button>(R.id.homeModelButton).setOnClickListener {
            when (app.graph.asrModels.currentState()) {
                is AsrModelManager.State.Preparing,
                is AsrModelManager.State.Downloading -> app.graph.asrModels.cancelDownload()
                is AsrModelManager.State.Ready -> requestListening()
                else -> app.graph.asrModels.download()
            }
        }
        findViewById<Button>(R.id.closePanelButton).setOnClickListener { closeClassroomPanel() }
        findViewById<Button>(R.id.moreButton).setOnClickListener {
            if (drawMode) { toast("请先点“完成批注”再打开更多工具"); return@setOnClickListener }
            val bar = findViewById<View>(R.id.moreToolsBar)
            val opening = bar.visibility != View.VISIBLE
            bar.visibility = if (opening) View.VISIBLE else View.GONE
            if (opening) showReaderChrome(autoHide = false) else showReaderChrome(autoHide = true)
        }
        findViewById<Button>(R.id.panelButton).setOnClickListener {
            if (findViewById<View>(R.id.sidePanel).visibility == View.VISIBLE) closeClassroomPanel() else openClassroomPanel()
        }
        findViewById<Button>(R.id.jumpMatchedPageButton).setOnClickListener { suggestedPage?.let { jumpTo(it) } }
        findViewById<Button>(R.id.listenButton).setOnClickListener {
            val state = ClassroomBus.state.value
            when {
                state.stopping -> Unit
                state.listening -> {
                    val button = findViewById<Button>(R.id.listenButton)
                    button.isEnabled = false
                    button.text = "正在结束…"
                    findViewById<TextView>(R.id.asrStatusText).text = "正在停止录音并收尾…"
                    startService(Intent(this, ClassroomService::class.java).setAction(ClassroomService.ACTION_STOP))
                }
                else -> requestListening()
            }
        }
        findViewById<Button>(R.id.prevButton).setOnClickListener { jumpTo(currentPage - 1) }
        findViewById<Button>(R.id.nextButton).setOnClickListener { jumpTo(currentPage + 1) }
        findViewById<Button>(R.id.jumpPageButton).setOnClickListener { promptJumpPage() }
        findViewById<Button>(R.id.jumpPageButton).setOnLongClickListener { fitCurrentPage(); true }
        findViewById<Button>(R.id.fitPageButton).setOnClickListener { fitCurrentPage() }
        findViewById<Button>(R.id.searchButton).setOnClickListener { promptPdfSearch() }
        findViewById<Button>(R.id.outlineButton).setOnClickListener { showOutline() }
        findViewById<Button>(R.id.bookmarkButton).setOnClickListener { toggleBookmark() }
        findViewById<Button>(R.id.bookmarkListButton).setOnClickListener { showBookmarks() }
        findViewById<Button>(R.id.drawButton).setOnClickListener { toggleDrawMode() }
        findViewById<Button>(R.id.penButton).setOnClickListener { selectTool(InkOverlayView.Tool.PEN) }
        findViewById<Button>(R.id.highlightButton).setOnClickListener {
            inkOverlay.setPenColor(0xffffdf44.toInt()); selectTool(InkOverlayView.Tool.HIGHLIGHTER)
        }
        findViewById<Button>(R.id.eraserButton).setOnClickListener { selectEraser() }
        findViewById<Button>(R.id.eraserButton).setOnLongClickListener {
            clearCurrentPageAnnotations()
            true
        }
        findViewById<Button>(R.id.undoButton).setOnClickListener { undoAnnotation() }
        findViewById<Button>(R.id.redoButton).setOnClickListener { redoAnnotation() }
        findViewById<Button>(R.id.noteButton).setOnClickListener { promptTextNote() }
        findViewById<Button>(R.id.forceOcrButton).setOnClickListener { startForceOcr() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { savePdfNow() }
        findViewById<Button>(R.id.saveAsButton).setOnClickListener { workspace?.let { saveAsPdf.launch(it.title.ifBlank { "课堂批注.pdf" }) } }
        findViewById<Button>(R.id.closeAnswerButton).setOnClickListener { findViewById<View>(R.id.answerCard).visibility = View.GONE }
        findViewById<Button>(R.id.answerToPdfButton).setOnClickListener { attachAnswerToPdf() }
        findViewById<Button>(R.id.importReferenceButton).setOnClickListener {
            importReferences.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/markdown", "text/plain"
            ))
        }
        findViewById<Button>(R.id.referencesButton).setOnClickListener { showReferenceLibrary() }
        findViewById<Button>(R.id.sessionsButton).setOnClickListener { showSessions() }
        findViewById<Button>(R.id.summarizeButton).setOnClickListener {
            findViewById<TextView>(R.id.saveStatusText).text = "正在整理课堂笔记…"
            notes.summarizeNow(selectedSessionId ?: ClassroomBus.state.value.sessionId) { result ->
                runOnUiThread { findViewById<TextView>(R.id.saveStatusText).text = result.fold({ "课堂笔记已整理" }, { "整理失败：${it.message}" }); refreshHistory() }
            }
        }
        findViewById<Button>(R.id.exportSessionButton).setOnClickListener { exportCurrentSession() }
        findViewById<Button>(R.id.manualAskButton).setOnClickListener {
            val edit = findViewById<EditText>(R.id.manualQuestionEdit); val q = edit.text.toString().trim()
            if (q.isNotBlank()) { manualQuestions.answer(q, ClassroomBus.state.value.sessionId ?: selectedSessionId); edit.text.clear() }
        }
    }

    private fun bindInk() {
        inkOverlay.setPenWidthDp(app.graph.settings.penWidthDp)
        inkOverlay.setPenColor(app.graph.settings.penColor)
        inkOverlay.setEraserRadiusDp(22f)

        inkOverlay.onStrokeFinished = { stroke -> workspace?.let { ws ->
            // The stroke is already visible in InkOverlayView; persistence can happen without
            // blocking the touch thread.
            val cmd = writer.queueInk(ws, currentPage, stroke)
            undoStack.addLast(AnnotationAction.Add(cmd)); redoStack.clear()
            findViewById<TextView>(R.id.saveStatusText).text = "批注已记录 · 自动保存中"
        } }

        inkOverlay.onEraserPathFinished = eraser@{ path, radius ->
            val ws = workspace ?: return@eraser
            val page = currentPage
            findViewById<TextView>(R.id.saveStatusText).text = "正在擦除…"
            writer.queueErasePath(ws, page, path, radius) { result ->
                runOnUiThread {
                    if (result.deletedCount == 0) {
                        inkOverlay.clearEraserFeedback()
                        findViewById<TextView>(R.id.saveStatusText).text = "没有擦到批注"
                        return@runOnUiThread
                    }
                    result.undoableCommands.forEach { undoStack.addLast(AnnotationAction.Delete(it)) }
                    redoStack.clear()
                    findViewById<TextView>(R.id.saveStatusText).text = "已擦除 ${result.deletedCount} 条"
                    // Coalesce quick consecutive eraser swipes. The cursor/trail stays on screen
                    // until the final render, while only one PDF commit/reload is performed.
                    eraserCommitJob?.cancel()
                    eraserCommitJob = lifecycleScope.launch {
                        delay(180)
                        val saved = withContext(Dispatchers.IO) { writer.flushNow(ws, syncSource = false) }
                        // Never recycle/reload PDFView in the middle of annotation. Reloading here
                        // used to reset the editing gesture pipeline and made the pen/eraser seem
                        // to disappear after the first erase. The live overlay already reflects
                        // the removal; the final raster refresh happens when annotation ends.
                        if (saved.isSuccess) {
                            // Existing annotations are part of PDFView's rendered bitmap. Deleting
                            // them only on disk made the eraser look broken until annotation mode
                            // ended. Recycle/reload once per coalesced eraser gesture, preserving the
                            // exact page/zoom/pan and keeping the overlay/tool active.
                            val viewport = capturePdfViewport()
                            inkOverlay.setInputReady(false)
                            findViewById<TextView>(R.id.saveStatusText).text = "擦除完成 · 正在刷新"
                            reloadPdfPreservingViewport(viewport)
                        } else {
                            inkOverlay.clearEraserFeedback()
                            findViewById<TextView>(R.id.saveStatusText).text = "擦除已记录，PDF 写入失败"
                            saved.onFailure { toast(it.message ?: "擦除写入失败") }
                        }
                    }
                }
            }
        }

        inkOverlay.onViewportGesture = viewportGesture@{ scaleFactor, focusX, focusY, dx, dy ->
            if (!drawMode || pdfView.isRecycled) return@viewportGesture
            val oldZoom = pdfView.zoom.coerceAtLeast(0.01f)
            val newZoom = (oldZoom * scaleFactor).coerceIn(pdfView.minZoom, pdfView.maxZoom)
            val relative = newZoom / oldZoom
            if (kotlin.math.abs(relative - 1f) > 0.001f) {
                pdfView.zoomCenteredRelativeTo(relative, PointF(focusX, focusY))
            }
            if (kotlin.math.abs(dx) > 0.05f || kotlin.math.abs(dy) > 0.05f) {
                pdfView.moveRelativeTo(dx, dy)
            }
            pdfView.loadPageByOffset()
            updateInkViewport()
        }
    }

    private fun openUri(uri: Uri) {
        lifecycleScope.launch {
            findViewById<TextView>(R.id.saveStatusText).text = "正在打开…"
            val result = withContext(Dispatchers.IO) { runCatching { app.graph.workspace.open(uri) } }
            result.onSuccess { ws ->
                workspace = ws; app.graph.settings.currentDocumentId = ws.id; app.graph.settings.currentPage = 0
                undoStack.clear(); redoStack.clear(); showWorkspace(ws, 0); reindexPdf(force = false)
            }.onFailure { showEmptyState(); toast("打开 PDF 失败：${it.message}") }
        }
    }

    private fun showWorkspace(ws: PdfWorkspaceManager.Workspace, page: Int) {
        if (inkGeometryWorkspaceId != ws.id) {
            synchronized(inkGeometryCache) { inkGeometryCache.clear() }
            inkGeometryWorkspaceId = ws.id
        }
        preloadInkGeometry(page.coerceAtLeast(0))
        findViewById<View>(R.id.emptyState).visibility = View.GONE
        findViewById<View>(R.id.mainToolbar).visibility = View.VISIBLE
        findViewById<TextView>(R.id.titleText).text = ws.title
        findViewById<TextView>(R.id.saveStatusText).text = "轻点页面显示工具 · 停止操作后自动隐藏"
        if (app.graph.db.pendingJournal(ws.id).isNotEmpty()) writer.schedule(ws, 300)
        loadPdf(page.coerceAtLeast(0), fitWidth = true)
        showReaderChrome(autoHide = true)
    }

    private data class PdfViewport(val page: Int, val zoom: Float, val x: Float, val y: Float)
    private var viewportRestoreAfterLoad: PdfViewport? = null
    private var fitWidthAfterLoad = true

    private fun loadPdf(page: Int, fitWidth: Boolean = true, restore: PdfViewport? = null) {
        val ws = workspace ?: return
        currentPage = page.coerceAtLeast(0)
        viewportRestoreAfterLoad = restore
        fitWidthAfterLoad = fitWidth && restore == null
        pdfView.recycle()
        // Ahmer PDFViewer defines 1x as its minimum level. With FitPolicy.BOTH that level is
        // the complete page; keep a generous upper bound for handwriting/detail inspection.
        pdfView.setMinZoom(1f)
        pdfView.setMaxZoom(5f)
        val config = pdfView.fromFile(ws.workingFile)
            .defaultPage(currentPage)
            .enableAnnotationRendering(true)
            .enableAntialiasing(true)
            .enableSwipe(true)
            .swipeHorizontal(false)
            // FitPolicy.BOTH defines zoom=1 as a true whole-page view. We can still open
            // at fit-width, but the user is now able to pinch back to an entire page.
            .pageFitPolicy(FitPolicy.BOTH)
            .fitEachPage(true)
            .autoSpacing(true)
            .pageSnap(false)
            .spacing(6)
            .onLoad(object : OnLoadCompleteListener {
                override fun loadComplete(totalPages: Int) {
                    pageCount = totalPages
                    currentPage = currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                    updatePageLabel()
                }
            })
            .onRender(object : OnRenderListener {
                override fun onInitiallyRendered(totalPages: Int) {
                    val restoreState = viewportRestoreAfterLoad
                    viewportRestoreAfterLoad = null
                    if (restoreState != null) {
                        pdfView.zoomTo(restoreState.zoom.coerceIn(pdfView.minZoom, pdfView.maxZoom))
                        pdfView.moveTo(restoreState.x, restoreState.y)
                        pdfView.loadPageByOffset()
                    } else if (fitWidthAfterLoad) {
                        // Avoid PDFView#getPageSize(): its public return type is
                        // com.ahmer.pdfium.util.SizeF. Some ahmer-pdfviewer builds do not
                        // expose that transitive type to the app module, causing Kotlin
                        // "Cannot access class SizeF" at compile time. fitToWidth() keeps
                        // SizeF internal to the viewer and is the library's documented API.
                        pdfView.fitToWidth(currentPage)
                        val widthZoom = pdfView.zoom.coerceIn(pdfView.minZoom, pdfView.maxZoom)
                        pdfView.setMidZoom(
                            widthZoom.coerceAtLeast(1.25f).coerceAtMost(pdfView.maxZoom)
                        )
                    }
                    if (drawMode) {
                        configureInkForPage(currentPage)
                        writer.warmEraserIndex(ws, currentPage)
                    } else {
                        preloadInkGeometry(currentPage)
                    }
                    updateInkViewport()
                    inkOverlay.clearEraserFeedback()
                }
            })
            .onPageScroll(object : OnPageScrollListener {
                override fun onPageScrolled(page: Int, positionOffset: Float) {
                    if (drawMode) updateInkViewport()
                }
            })
            .onPageChange(object : OnPageChangeListener {
                override fun onPageChanged(page: Int, totalPages: Int) {
                    currentPage = page
                    pageCount = totalPages
                    app.graph.settings.currentPage = page
                    updatePageLabel()
                    if (drawMode) {
                        inkOverlay.clearCommittedPreview()
                        configureInkForPage(page)
                        writer.warmEraserIndex(ws, page)
                        updateInkViewport()
                    } else {
                        preloadInkGeometry(page)
                    }
                }
            })
            .onError(object : OnErrorListener {
                override fun onError(t: Throwable?) { toast("PDF 渲染失败：${t?.message ?: "未知错误"}") }
            })
        config.load()
    }

    private fun fitCurrentPage() {
        if (pdfView.isRecycled || pageCount <= 0) return
        // FitPolicy.BOTH defines 1x as the complete page. Do not reload the document: keep the
        // same PDFView instance so entering/leaving annotation remains visually continuous.
        pdfView.zoomTo(1f)
        pdfView.jumpTo(currentPage, false)
        pdfView.loadPageByOffset()
        if (drawMode) updateInkViewport()
        findViewById<TextView>(R.id.saveStatusText).text = "整页显示 · 双指可继续缩放"
        showReaderChrome(autoHide = !drawMode)
    }

    private fun capturePdfViewport(): PdfViewport? = if (pdfView.isRecycled) null else
        PdfViewport(currentPage, pdfView.zoom, pdfView.currentXOffset, pdfView.currentYOffset)

    private fun reloadPdfPreservingViewport(state: PdfViewport?) {
        val page = state?.page ?: currentPage
        loadPdf(page, fitWidth = false, restore = state)
    }

    private fun updateInkViewport() {
        if (!drawMode || pdfView.isRecycled) return
        val file = pdfView.pdfFile ?: return
        if (currentPage !in 0 until file.pagesCount) return
        val zoom = pdfView.zoom
        // PdfFile#getPageSize() leaks ahmer-pdfium's SizeF into the caller. Reconstruct
        // the exact rendered page rectangle from scalar APIs instead. For the vertical
        // viewer used here, getSecondaryPageOffset() is the horizontal centering offset
        // and getPageLength() is the rendered page height.
        val secondaryOffset = file.getSecondaryPageOffset(currentPage, zoom)
        val width = (pdfView.toCurrentScale(file.maxPageWidth) - secondaryOffset * 2f)
            .coerceAtLeast(1f)
        val height = file.getPageLength(currentPage, zoom).coerceAtLeast(1f)
        val left = pdfView.currentXOffset + secondaryOffset
        val top = pdfView.currentYOffset + file.getPageOffset(currentPage, zoom)
        inkOverlay.setPageViewport(RectF(left, top, left + width, top + height))
    }

    private fun preloadInkGeometry(pageIndex: Int) {
        val ws = workspace ?: return
        synchronized(inkGeometryCache) { if (inkGeometryCache.containsKey(pageIndex)) return }
        lifecycleScope.launch(Dispatchers.IO) {
            val geometry = readInkGeometry(ws, pageIndex) ?: return@launch
            synchronized(inkGeometryCache) { inkGeometryCache[pageIndex] = geometry }
        }
    }

    private fun readInkGeometry(ws: PdfWorkspaceManager.Workspace, pageIndex: Int): FloatArray? = runCatching {
        PDDocument.load(ws.workingFile).use { doc ->
            check(doc.numberOfPages > 0) { "PDF 没有页面" }
            val page = doc.getPage(pageIndex.coerceIn(0, doc.numberOfPages - 1))
            val box = page.cropBox
            floatArrayOf(box.lowerLeftX, box.lowerLeftY, box.width, box.height, page.rotation.toFloat())
        }
    }.getOrNull()

    private fun configureInkForPage(pageIndex: Int) {
        val ws = workspace ?: return

        // Ink must use the real CropBox/rotation from the beginning. Older builds allowed the
        // first stroke to use a display-size fallback and then silently swapped to PDFBox geometry;
        // the stroke still looked fine on screen but the eraser later searched a different PDF
        // coordinate system and could never hit it. Geometry is pre-warmed while reading, so the
        // normal path remains instant. On a cache miss we keep consuming touches for a very short
        // preparation phase instead of persisting coordinates that cannot be erased reliably.
        val cached = synchronized(inkGeometryCache) { inkGeometryCache[pageIndex]?.copyOf() }
        inkOverlay.isEnabled = drawMode
        inkOverlay.isClickable = drawMode
        updateInkViewport()
        writer.warmEraserIndex(ws, pageIndex)

        if (cached != null) {
            inkOverlay.setPdfPageGeometry(cached[0], cached[1], cached[2], cached[3], cached[4].toInt())
            inkOverlay.setInputReady(drawMode)
            return
        }

        inkOverlay.setInputReady(false)
        if (drawMode) findViewById<TextView>(R.id.saveStatusText).text = "准备批注坐标…"
        lifecycleScope.launch(Dispatchers.IO) {
            val geometry = readInkGeometry(ws, pageIndex)
            if (geometry != null) synchronized(inkGeometryCache) { inkGeometryCache[pageIndex] = geometry }
            withContext(Dispatchers.Main) {
                if (workspace?.id != ws.id || currentPage != pageIndex) return@withContext
                if (geometry != null) {
                    inkOverlay.setPdfPageGeometry(geometry[0], geometry[1], geometry[2], geometry[3], geometry[4].toInt())
                } else {
                    // Corrupt/non-standard page metadata should not make annotation permanently
                    // unusable. Freeze one display-space fallback for this editing session rather
                    // than changing coordinate systems after the user starts drawing.
                    applyImmediateInkGeometryFallback(pageIndex)
                }
                if (drawMode) {
                    updateInkViewport()
                    inkOverlay.isEnabled = true
                    inkOverlay.isClickable = true
                    inkOverlay.setInputReady(true)
                    findViewById<TextView>(R.id.saveStatusText).text =
                        if (inkOverlay.getTool() == InkOverlayView.Tool.ERASER)
                            "橡皮 · 拖动擦除 · 长按全清本页"
                        else "单指书写 · 双指缩放/移动"
                }
            }
        }
    }

    private fun applyImmediateInkGeometryFallback(pageIndex: Int) {
        if (pdfView.isRecycled) return
        val file = pdfView.pdfFile ?: return
        if (pageIndex !in 0 until file.pagesCount) return
        // Same SizeF-free fallback as updateInkViewport(). These are display-space
        // dimensions used only until PDFBox supplies the exact CropBox asynchronously.
        val secondaryOffset = file.getSecondaryPageOffset(pageIndex, 1f)
        val width = (file.maxPageWidth - secondaryOffset * 2f).coerceAtLeast(1f)
        val height = file.getPageLength(pageIndex, 1f).coerceAtLeast(1f)
        inkOverlay.setPdfPageGeometry(0f, 0f, width, height, 0)
    }

    private fun toggleDrawMode() {
        val ws = workspace ?: run { toast("请先打开 PDF"); return }
        if (!drawMode) { enterDrawMode(InkOverlayView.Tool.PEN); return }

        // Exit editing immediately. The expensive PDF write is background work; the current
        // viewport is restored after refresh so "完成批注" never jumps or refits the page.
        drawMode = false
        eraserCommitJob?.cancel(); eraserCommitJob = null
        inkOverlay.setInputReady(false)
        inkOverlay.isEnabled = false
        inkOverlay.isClickable = false
        // Remove the editing surface immediately so PDFView regains its native gesture stream
        // before any background PDF write/re-render happens.
        inkOverlay.visibility = View.GONE
        findViewById<View>(R.id.annotationToolbar).visibility = View.GONE
        findViewById<Button>(R.id.drawButton).text = "批注"
        findViewById<Button>(R.id.drawButton).isEnabled = true
        val viewport = capturePdfViewport()
        findViewById<TextView>(R.id.saveStatusText).text = "批注已完成 · 后台写入中"
        showReaderChrome(autoHide = true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { writer.flushNow(ws, syncSource = false) }
            inkOverlay.clearCommittedPreview()
            if (result.isSuccess) {
                scheduleAnnotationRasterRefresh(viewport)
                findViewById<TextView>(R.id.saveStatusText).text = "批注已保存 · PDF 空闲后刷新显示"
            } else {
                findViewById<TextView>(R.id.saveStatusText).text = "批注写入失败，恢复日志已保留"
                result.onFailure { toast(it.message ?: "PDF 写回失败") }
            }
        }
    }

    private fun scheduleAnnotationRasterRefresh(viewport: PdfViewport?) {
        annotationRefreshRunnable?.let { chromeHandler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (drawMode || pdfTouchActive || workspace == null) {
                    chromeHandler.postDelayed(this, 350L)
                    return
                }
                annotationRefreshRunnable = null
                // Preserve whatever zoom/pan the user reached after leaving annotation, not the
                // stale viewport captured when “完成批注” was tapped. Otherwise a successful
                // background refresh appears to undo/disable the user's pinch gesture.
                reloadPdfPreservingViewport(capturePdfViewport() ?: viewport)
            }
        }
        annotationRefreshRunnable = task
        // Give the user a chance to pinch/drag immediately after tapping “完成批注”.
        // If a gesture starts, dispatchTouchEvent marks pdfTouchActive and refresh waits.
        chromeHandler.postDelayed(task, 450L)
    }

    private fun enterDrawMode(tool: InkOverlayView.Tool) {
        val ws = workspace ?: run { toast("请先打开 PDF"); return }
        annotationRefreshRunnable?.let { chromeHandler.removeCallbacks(it) }
        annotationRefreshRunnable = null
        drawMode = true
        inkOverlay.visibility = View.VISIBLE
        inkOverlay.isEnabled = true
        inkOverlay.isClickable = true
        // configureInkForPage() enables input only after a stable page coordinate system is
        // available. Geometry is preloaded during reading, so this is normally immediate.
        updateInkViewport()
        inkOverlay.setInputReady(false)
        findViewById<View>(R.id.annotationToolbar).visibility = View.VISIBLE
        findViewById<View>(R.id.eraserButton).visibility = View.VISIBLE
        findViewById<View>(R.id.moreToolsBar).visibility = View.GONE
        inkOverlay.clearCommittedPreview()
        inkOverlay.setTool(tool)
        updateInkToolUi(tool)
        if (tool == InkOverlayView.Tool.PEN) inkOverlay.setPenColor(app.graph.settings.penColor)
        findViewById<Button>(R.id.drawButton).text = "完成批注"
        findViewById<TextView>(R.id.saveStatusText).text =
            if (tool == InkOverlayView.Tool.ERASER) "橡皮 · 拖动立即擦除 · 长按橡皮全清本页"
            else "单指书写 · 双指缩放/移动"
        showReaderChrome(autoHide = false)
        configureInkForPage(currentPage)
        writer.warmEraserIndex(ws, currentPage)
        updateInkViewport()
    }

    private fun selectTool(tool: InkOverlayView.Tool) {
        if (!drawMode) enterDrawMode(tool) else {
            if (tool == InkOverlayView.Tool.PEN) inkOverlay.setPenColor(app.graph.settings.penColor)
            inkOverlay.setTool(tool)
        }
        updateInkToolUi(tool)
        findViewById<TextView>(R.id.saveStatusText).text = when (tool) { InkOverlayView.Tool.PEN -> "画笔"; InkOverlayView.Tool.HIGHLIGHTER -> "荧光笔"; InkOverlayView.Tool.ERASER -> "橡皮 · 拖动擦除" }
    }

    private fun updateInkToolUi(selected: InkOverlayView.Tool = inkOverlay.getTool()) {
        val selectedBg = resolveThemeColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xffeaddff.toInt())
        val selectedText = resolveThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xff21005d.toInt())
        val normalText = resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xff49454f.toInt())
        listOf(
            R.id.penButton to InkOverlayView.Tool.PEN,
            R.id.eraserButton to InkOverlayView.Tool.ERASER,
            R.id.highlightButton to InkOverlayView.Tool.HIGHLIGHTER,
        ).forEach { (id, tool) ->
            findViewById<MaterialButton>(id).apply {
                val active = tool == selected
                isSelected = active
                backgroundTintList = ColorStateList.valueOf(if (active) selectedBg else Color.TRANSPARENT)
                setTextColor(if (active) selectedText else normalText)
                alpha = if (active) 1f else 0.88f
            }
        }
    }

    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
        } else fallback
    }

    private fun selectEraser() {
        val ws = workspace ?: run { toast("请先打开 PDF"); return }
        if (!drawMode) enterDrawMode(InkOverlayView.Tool.ERASER)
        // No synchronous PDF write here. The writer indexes existing annotations once and also
        // knows about unflushed strokes, so switching to the eraser is immediate.
        writer.warmEraserIndex(ws, currentPage)
        inkOverlay.setTool(InkOverlayView.Tool.ERASER)
        updateInkToolUi(InkOverlayView.Tool.ERASER)
        findViewById<TextView>(R.id.saveStatusText).text = "橡皮 · 拖动擦除 · 长按全清本页"
    }

    private fun clearCurrentPageAnnotations() {
        val ws = workspace ?: run { toast("请先打开 PDF"); return }
        if (!drawMode) return
        val page = currentPage
        findViewById<TextView>(R.id.saveStatusText).text = "正在清空本页批注…"
        inkOverlay.clearCommittedPreview()
        writer.queueClearPage(ws, page) { count ->
            runOnUiThread {
                if (count == 0) {
                    findViewById<TextView>(R.id.saveStatusText).text = "本页没有可清除的 ClassHelper 批注"
                    return@runOnUiThread
                }
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { writer.flushNow(ws, syncSource = false) }
                    if (result.isSuccess) {
                        val viewport = capturePdfViewport()
                        inkOverlay.setInputReady(false)
                        findViewById<TextView>(R.id.saveStatusText).text = "已清空本页 $count 条 · 正在刷新"
                        reloadPdfPreservingViewport(viewport)
                    } else {
                        inkOverlay.clearEraserFeedback()
                        findViewById<TextView>(R.id.saveStatusText).text = "清空已记录，但 PDF 写入失败"
                        result.onFailure { toast(it.message ?: "清空批注失败") }
                    }
                }
            }
        }
    }

    private fun undoAnnotation() {
        val ws = workspace ?: return
        val action = undoStack.removeLastOrNull() ?: run { toast("当前没有可撤销的批注操作"); return }
        when (action) {
            is AnnotationAction.Add -> {
                writer.undo(ws, action.command)
                if (action.command.kind == "ink" || action.command.kind == "highlight") inkOverlay.removeLastCommittedPreview()
            }
            is AnnotationAction.Delete -> writer.redo(ws, action.command)
        }
        redoStack.addLast(action)
        findViewById<TextView>(R.id.saveStatusText).text = "已撤销"
    }

    private fun redoAnnotation() {
        val ws = workspace ?: return
        val action = redoStack.removeLastOrNull() ?: run { toast("没有可重做的批注操作"); return }
        when (action) {
            is AnnotationAction.Add -> writer.redo(ws, action.command)
            is AnnotationAction.Delete -> writer.undo(ws, action.command)
        }
        undoStack.addLast(action)
        findViewById<TextView>(R.id.saveStatusText).text = "已重做；保存后显示"
    }


    private data class DialogTextInput(
        val container: TextInputLayout,
        val editText: TextInputEditText,
    )

    private fun dialogTextInput(
        label: String,
        value: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        minLines: Int = 1,
    ): DialogTextInput {
        val field = TextInputEditText(this).apply {
            this.inputType = inputType
            setText(value)
            if (minLines > 1) {
                this.minLines = minLines
                maxLines = 6
                setSingleLine(false)
            } else {
                setSingleLine(true)
            }
        }
        val container = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = label
            isHintAnimationEnabled = true
            val r = 20f * resources.displayMetrics.density
            setBoxCornerRadii(r, r, r, r)
            boxStrokeWidth = dialogInsetPx(1)
            boxStrokeWidthFocused = dialogInsetPx(2)
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        return DialogTextInput(container, field)
    }

    private fun dialogInsetPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun promptTextNote(prefill: String = "") {
        val ws = workspace ?: run { toast("请先打开 PDF"); return }
        val input = dialogTextInput(
            label = "便签内容",
            value = prefill,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            minLines = 3,
        )
        Md3eDialogUi.showContent(
            context = this,
            title = "PDF 便签",
            content = input.container,
            positiveLabel = "写入",
        ) note@{
            val text = input.editText.text.toString().trim(); if (text.isBlank()) return@note
            lifecycleScope.launch(Dispatchers.IO) {
                val xy = runCatching { PDDocument.load(ws.workingFile).use { doc ->
                    val b = doc.getPage(currentPage.coerceIn(0, doc.numberOfPages - 1)).cropBox
                    (b.lowerLeftX + b.width - 36f) to (b.lowerLeftY + b.height - 36f)
                } }.getOrDefault(24f to 24f)
                withContext(Dispatchers.Main) {
                    val cmd = writer.queueTextNote(ws, currentPage, xy.first, xy.second, text); undoStack.addLast(AnnotationAction.Add(cmd)); redoStack.clear()
                    findViewById<TextView>(R.id.saveStatusText).text = "便签待自动保存…"
                }
            }
        }
    }

    private fun attachAnswerToPdf() {
        val answer = ClassroomBus.state.value.answer.trim()
        val question = ClassroomBus.state.value.lastQuestion.trim()
        if (answer.isBlank()) { toast("当前没有答案"); return }
        promptTextNote(buildString { if (question.isNotBlank()) appendLine("Q：$question"); append("A：$answer") })
    }

    private fun savePdfNow() {
        val ws = workspace ?: return
        findViewById<TextView>(R.id.saveStatusText).text = "正在同步原 PDF…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { writer.flushNow(ws, syncSource = true) }
            result.onSuccess { findViewById<TextView>(R.id.saveStatusText).text = "已保存到原 PDF"; if (!drawMode) loadPdf(currentPage, fitWidth = true) }
                .onFailure { findViewById<TextView>(R.id.saveStatusText).text = "原文件不可写；工作副本和恢复状态已保留"; toast("可使用‘另存’保存：${it.message}") }
        }
    }

    private fun startForceOcr() {
        if (workspace == null) { toast("请先打开 PDF"); return }
        if (app.graph.ocrModels.isReady()) { reindexPdf(force = true); return }
        Md3eDialogUi.showList(
            this,
            "高精度 OCR 模型未安装",
            listOf(
                Md3eDialogUi.Item("下载 PP-OCRv6 高精度模型", "约 30 MB · SHA-256 校验 · 下载后扫描页使用文本检测 + 逐行识别"),
                Md3eDialogUi.Item("先用兼容 OCR", "立即使用现有 ML Kit 对整份 PDF 重新 OCR；准确率可能低于 PP-OCRv6")
            )
        ) { which ->
            if (which == 0) {
                app.graph.ocrModels.download()
                findViewById<TextView>(R.id.saveStatusText).text = "正在下载 PP-OCRv6；可在设置中查看进度，完成后再点 OCR"
            } else reindexPdf(force = true)
        }
    }

    private fun reindexPdf(force: Boolean) {
        val ws = workspace ?: return
        lifecycleScope.launch {
            findViewById<TextView>(R.id.saveStatusText).text = if (force) "正在重新索引 / OCR…" else "正在建立资料索引…"
            withContext(Dispatchers.IO) { writer.flushNow(ws, syncSource = false) }
            val result = withContext(Dispatchers.IO) {
                runCatching { PdfTextIndexer(this@ReaderActivity, app.graph.db, app.graph.settings, app.graph.ocrModels).index(ws, force) { done, total, phase ->
                    runOnUiThread { findViewById<TextView>(R.id.saveStatusText).text = "$phase $done/$total" }
                } }
            }
            result.onSuccess { app.graph.knowledge.invalidate(ws.id); findViewById<TextView>(R.id.saveStatusText).text = "资料索引完成：$it 页" }
                .onFailure { findViewById<TextView>(R.id.saveStatusText).text = "索引失败：${it.message}" }
        }
    }

    private fun promptPdfSearch() {
        val ws = workspace ?: run { toast("请先打开 PDF"); return }
        val input = dialogTextInput(label = "搜索 PDF 文字")
        Md3eDialogUi.showContent(
            context = this,
            title = "PDF 搜索",
            content = input.container,
            positiveLabel = "搜索",
        ) search@{
            val q = input.editText.text.toString().trim(); if (q.isBlank()) return@search
            lifecycleScope.launch(Dispatchers.IO) {
                val hits = (app.graph.db.searchChunks(ws.id, q, 30) + app.graph.db.searchCurrentDocumentLike(ws.id, q, 30)).distinctBy { it.page }
                withContext(Dispatchers.Main) { showSearchResults(q, hits) }
            }
        }
    }

    private fun showSearchResults(query: String, hits: List<ChunkRow>) {
        if (hits.isEmpty()) { toast("没有找到“$query”"); return }
        Md3eDialogUi.showList(
            this,
            "搜索结果 ${hits.size}",
            hits.map { Md3eDialogUi.Item("第 ${it.page + 1} 页", snippet(it.text, query)) },
        ) { which -> jumpTo(hits[which].page) }
    }

    private fun snippet(text: String, query: String): String {
        val flat = text.replace(Regex("\\s+"), " "); val i = flat.indexOf(query, ignoreCase = true)
        return if (i >= 0) flat.substring((i - 30).coerceAtLeast(0), (i + query.length + 60).coerceAtMost(flat.length)) else flat.take(90)
    }

    private fun promptJumpPage() {
        if (pageCount <= 0) return
        val input = dialogTextInput(label = "页码 1–$pageCount", inputType = InputType.TYPE_CLASS_NUMBER)
        Md3eDialogUi.showContent(
            context = this,
            title = "跳转页码",
            content = input.container,
            positiveLabel = "跳转",
        ) {
            input.editText.text.toString().toIntOrNull()?.let { jumpTo(it - 1) }
        }
    }

    private fun toggleBookmark() {
        val ws = workspace ?: return
        val added = app.graph.db.toggleBookmark(ws.id, currentPage, "P${currentPage + 1}")
        toast(if (added) "已加入书签" else "已移除书签"); updatePageLabel()
    }

    private fun showBookmarks() {
        val ws = workspace ?: return
        val rows = app.graph.db.bookmarks(ws.id)
        if (rows.isEmpty()) { toast("暂无书签"); return }
        Md3eDialogUi.showList(
            this,
            "书签",
            rows.map { Md3eDialogUi.Item("第 ${it.page + 1} 页", it.label) },
        ) { i -> jumpTo(rows[i].page) }
    }

    private fun showOutline() {
        val ws = workspace ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val items = runCatching {
                PDDocument.load(ws.workingFile).use { doc ->
                    val out = mutableListOf<Pair<String, Int>>()
                    fun walk(item: PDOutlineItem?, depth: Int) {
                        var cur = item
                        while (cur != null && out.size < 500) {
                            val page = runCatching { cur.findDestinationPage(doc) }.getOrNull()
                            val index = if (page == null) -1 else (0 until doc.numberOfPages).firstOrNull { doc.getPage(it).cosObject == page.cosObject } ?: -1
                            if (index >= 0) out += ("  ".repeat(depth) + cur.title.orEmpty()) to index
                            walk(cur.firstChild, depth + 1); cur = cur.nextSibling
                        }
                    }
                    walk(doc.documentCatalog.documentOutline?.firstChild, 0); out
                }
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                if (items.isEmpty()) toast("这个 PDF 没有目录")
                else Md3eDialogUi.showList(
                    this@ReaderActivity,
                    "目录",
                    items.map { Md3eDialogUi.Item(it.first.trimStart(), "第 ${it.second + 1} 页") },
                ) { i -> jumpTo(items[i].second) }
            }
        }
    }

    private fun showReferenceLibrary() {
        val docs = app.graph.db.listDocuments().filter { it.kind != "pdf" }
        if (docs.isEmpty()) { toast("资料库为空"); return }
        Md3eDialogUi.showList(
            this,
            "参考资料",
            docs.map { Md3eDialogUi.Item(it.title, "${it.kind.uppercase()} · 点按查看管理选项") },
        ) { i ->
            val doc = docs[i]
            Md3eDialogUi.showConfirm(
                this,
                doc.title,
                "已建立 ${app.graph.db.allChunks(doc.id, 10_000).size} 个文本块。删除只移除应用内索引，不修改原文件。",
                positiveLabel = "删除索引",
                danger = true,
            ) {
                app.graph.db.deleteDocument(doc.id)
                app.graph.knowledge.invalidate(doc.id)
                toast("已移除")
            }
        }
    }

    private fun showSessions() {
        val sessions = app.graph.db.recentSessions(40)
        if (sessions.isEmpty()) { toast("还没有课堂记录"); return }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        Md3eDialogUi.showList(
            this,
            "课堂记录",
            sessions.map {
                Md3eDialogUi.Item(
                    it.title,
                    "${fmt.format(Date(it.startedAt))}${if (it.endedAt == null) " · 进行中" else ""}",
                )
            },
        ) { i ->
            selectedSessionId = sessions[i].id
            refreshHistory()
            openClassroomPanel()
        }
    }

    private fun exportCurrentSession() {
        val id = ClassroomBus.state.value.sessionId ?: selectedSessionId ?: app.graph.db.recentSessions(1).firstOrNull()?.id
        if (id == null) { toast("没有可导出的课堂记录"); return }
        pendingExportSessionId = id
        val session = app.graph.db.getSession(id)
        val safe = (session?.title ?: "课堂记录").replace(Regex("[\\/:*?\"<>|]"), "_")
        exportSession.launch("$safe.md")
    }

    private fun buildSessionMarkdown(sessionId: String): String {
        val session = app.graph.db.getSession(sessionId)
        val transcripts = app.graph.db.recentTranscripts(20_000, sessionId)
        val qs = app.graph.db.recentQuestions(2_000, sessionId).reversed()
        val notesList = app.graph.db.recentNotes(500, sessionId).reversed()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return buildString {
            appendLine("# ${session?.title ?: "课堂记录"}"); appendLine()
            session?.let { appendLine("- 开始：${fmt.format(Date(it.startedAt))}"); it.endedAt?.let { end -> appendLine("- 结束：${fmt.format(Date(end))}") }; appendLine() }
            if (notesList.isNotEmpty()) { appendLine("## 智能笔记"); notesList.forEach { appendLine(it.text); appendLine() } }
            if (qs.isNotEmpty()) { appendLine("## 课堂问题"); qs.forEach { appendLine("### Q：${it.question}"); appendLine(it.answer); if (it.evidence.isNotBlank()) appendLine("\n依据：${it.evidence}"); appendLine() } }
            appendLine("## 原始转写"); transcripts.forEach { appendLine("- ${fmt.format(Date(it.ts))}  ${it.text}") }
        }
    }

    private fun configureSidePanelWidth() {
        val panel = findViewById<View>(R.id.sidePanel)
        panel.post {
            val density = resources.displayMetrics.density
            val preferred = (360f * density).toInt()
            val available = (resources.displayMetrics.widthPixels - 24f * density).toInt().coerceAtLeast((220f * density).toInt())
            panel.layoutParams = panel.layoutParams.apply { width = min(preferred, available) }
        }
    }

    /**
     * Observe PDF gestures without installing an OnTouchListener on PDFView.
     *
     * AhmerPdfViewer owns its touch listener internally for drag/fling/zoom. Replacing it
     * with an external touch listener can break that internal
     * gesture pipeline on affected versions. We therefore watch the Activity's dispatch
     * stream and always delegate the event to super.dispatchTouchEvent().
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        observeChromeControlInteraction(event)
        observeReaderChromeGesture(event)
        return super.dispatchTouchEvent(event)
    }

    private fun observeChromeControlInteraction(event: MotionEvent) {
        if (!chromeVisible || workspace == null || drawMode) return
        val top = findViewById<View>(R.id.topChrome)
        val bottom = findViewById<View>(R.id.bottomChrome)
        val insideChrome = isPointInside(top, event.rawX, event.rawY) || isPointInside(bottom, event.rawX, event.rawY)
        if (!insideChrome) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> chromeHandler.removeCallbacks(autoHideChrome)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleChromeHide()
        }
    }

    private fun observeReaderChromeGesture(event: MotionEvent) {
        if (!::pdfView.isInitialized || workspace == null || drawMode) {
            pdfTouchActive = false
            return
        }
        if (findViewById<View>(R.id.sidePanel).visibility == View.VISIBLE) {
            pdfTouchActive = false
            return
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Decide once at DOWN whether this gesture belongs to the uncovered PDF.
                // Do not re-evaluate after chrome visibility/layout changes, otherwise an
                // ACTION_UP can be rejected exactly when it is supposed to wake the bars.
                pdfTouchActive = isTouchOnBarePdf(event)
                if (!pdfTouchActive) return
                pdfTouchDownX = event.rawX
                pdfTouchDownY = event.rawY
                pdfTouchMoved = false
                chromeHandler.removeCallbacks(autoHideChrome)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pdfTouchActive) return
                if (!pdfTouchMoved &&
                    (abs(event.rawX - pdfTouchDownX) > touchSlop || abs(event.rawY - pdfTouchDownY) > touchSlop)
                ) {
                    pdfTouchMoved = true
                    findViewById<View>(R.id.moreToolsBar).visibility = View.GONE
                    // Keep controls visible while the finger is still moving. Immediate hiding on
                    // the first few pixels of a drag felt abrupt, especially on e-ink displays.
                    chromeHandler.removeCallbacks(autoHideChrome)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!pdfTouchActive) return
                if (!pdfTouchMoved) {
                    if (chromeVisible) hideReaderChrome(force = true)
                    else showReaderChrome(autoHide = true)
                } else if (chromeVisible) {
                    scheduleChromeHide(chromeScrollHideDelayMs)
                }
                pdfTouchMoved = false
                pdfTouchActive = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!pdfTouchActive) return
                pdfTouchMoved = false
                pdfTouchActive = false
                scheduleChromeHide()
            }
        }
    }

    /** Only react to taps/drags on the uncovered PDF, never toolbar/sidebar controls. */
    private fun isTouchOnBarePdf(event: MotionEvent): Boolean {
        if (!isPointInside(pdfView, event.rawX, event.rawY)) return false
        val blockingOverlays = intArrayOf(
            R.id.emptyState,
            R.id.topChrome,
            R.id.bottomChrome,
            R.id.annotationToolbar,
            R.id.moreToolsBar,
            R.id.sidePanel,
            R.id.chromeWakeHandle
        )
        return blockingOverlays.none { id ->
            val view = findViewById<View>(id)
            view.visibility == View.VISIBLE && isPointInside(view, event.rawX, event.rawY)
        }
    }

    private fun isPointInside(view: View, rawX: Float, rawY: Float): Boolean {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] && rawX < location[0] + view.width &&
            rawY >= location[1] && rawY < location[1] + view.height
    }

    private fun openClassroomPanel() {
        findViewById<View>(R.id.moreToolsBar).visibility = View.GONE
        findViewById<View>(R.id.sidePanel).visibility = View.VISIBLE
        findViewById<View>(R.id.chromeWakeHandle).visibility = View.GONE
        refreshHistory()
        setReaderChromeVisible(false)
    }

    private fun closeClassroomPanel() {
        findViewById<View>(R.id.sidePanel).visibility = View.GONE
        showReaderChrome(autoHide = true)
    }

    private fun showReaderChrome(autoHide: Boolean) {
        if (workspace == null || findViewById<View>(R.id.emptyState).visibility == View.VISIBLE) return
        setReaderChromeVisible(true)
        if (autoHide && !drawMode && findViewById<View>(R.id.moreToolsBar).visibility != View.VISIBLE) scheduleChromeHide()
        else chromeHandler.removeCallbacks(autoHideChrome)
    }

    private fun hideReaderChrome(force: Boolean = false) {
        if (workspace == null) return
        if (!force && (drawMode || findViewById<View>(R.id.sidePanel).visibility == View.VISIBLE || findViewById<View>(R.id.moreToolsBar).visibility == View.VISIBLE)) return
        setReaderChromeVisible(false)
    }

    private fun setReaderChromeVisible(visible: Boolean) {
        chromeHandler.removeCallbacks(autoHideChrome)
        chromeVisible = visible
        findViewById<View>(R.id.topChrome).visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.bottomChrome).visibility = if (visible) View.VISIBLE else View.GONE
        val canOfferWakeHandle = !visible && workspace != null && !drawMode &&
            findViewById<View>(R.id.emptyState).visibility != View.VISIBLE &&
            findViewById<View>(R.id.sidePanel).visibility != View.VISIBLE
        findViewById<View>(R.id.chromeWakeHandle).visibility = if (canOfferWakeHandle) View.VISIBLE else View.GONE
    }

    private fun scheduleChromeHide(delayMs: Long = chromeAutoHideDelayMs) {
        chromeHandler.removeCallbacks(autoHideChrome)
        if (!drawMode && findViewById<View>(R.id.sidePanel).visibility != View.VISIBLE && findViewById<View>(R.id.moreToolsBar).visibility != View.VISIBLE) {
            chromeHandler.postDelayed(autoHideChrome, delayMs)
        }
    }

    private fun showEmptyState() {
        if (!::pdfView.isInitialized) return
        findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
        findViewById<View>(R.id.sidePanel).visibility = View.GONE
        findViewById<View>(R.id.annotationToolbar).visibility = View.GONE
        findViewById<View>(R.id.moreToolsBar).visibility = View.GONE
        findViewById<View>(R.id.mainToolbar).visibility = View.GONE
        findViewById<View>(R.id.chromeWakeHandle).visibility = View.GONE
        setReaderChromeVisible(false)
        findViewById<TextView>(R.id.titleText).text = "课堂阅读"
        findViewById<TextView>(R.id.pageText).text = "还未打开 PDF"
        findViewById<TextView>(R.id.saveStatusText).text = "打开 PDF，或直接开始听课"
    }

    private var pendingListenAfterModelDownload = false
    private var modelReadyPendingStart = false
    private var quickModelListener: ((io.github.paper.classhelper.asr.AsrModelManager.State) -> Unit)? = null

    private fun requestListening() {
        if (!app.graph.asrModels.isReady()) {
            showFirstModelDownloadDialog()
            return
        }
        requestMicAndStartListening()
    }

    private fun requestMicAndStartListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            requestNotificationThenStart()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startListeningService()
        }
    }

    private fun showFirstModelDownloadDialog() {
        val state = app.graph.asrModels.currentState()
        if (state is io.github.paper.classhelper.asr.AsrModelManager.State.Downloading || state is io.github.paper.classhelper.asr.AsrModelManager.State.Preparing) {
            pendingListenAfterModelDownload = true
            toast("语音模型正在下载，完成后会自动开始听课")
            observeQuickModelDownload()
            return
        }
        Md3eDialogUi.showList(
            context = this,
            title = "首次使用语音识别",
            items = listOf(
                Md3eDialogUi.Item("下载并开始", "SenseVoiceSmall INT8 · 约 230 MB · 仅首次下载，之后在本机识别"),
                Md3eDialogUi.Item("模型管理", "查看模型状态、下载进度或删除本地模型"),
            ),
            closeLabel = "稍后",
        ) { which ->
            if (which == 0) {
                pendingListenAfterModelDownload = true
                app.graph.asrModels.download()
                observeQuickModelDownload()
                toast("正在连接模型下载源…")
            } else {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    private fun observeQuickModelDownload() {
        if (quickModelListener != null) return
        lateinit var listener: (io.github.paper.classhelper.asr.AsrModelManager.State) -> Unit
        listener = { state ->
            runOnUiThread {
                when (state) {
                    is io.github.paper.classhelper.asr.AsrModelManager.State.Ready -> {
                        app.graph.asrModels.removeListener(listener)
                        quickModelListener = null
                        if (pendingListenAfterModelDownload) {
                            pendingListenAfterModelDownload = false
                            modelReadyPendingStart = true
                            tryStartAfterModelDownload()
                        }
                    }
                    is io.github.paper.classhelper.asr.AsrModelManager.State.Error -> {
                        app.graph.asrModels.removeListener(listener)
                        quickModelListener = null
                        pendingListenAfterModelDownload = false
                        modelReadyPendingStart = false
                        findViewById<Button>(R.id.listenButton).text = "开始听课"
                        toast("语音模型下载失败：${state.message}")
                    }
                    io.github.paper.classhelper.asr.AsrModelManager.State.Missing -> {
                        if (pendingListenAfterModelDownload && !app.graph.asrModels.isDownloading()) {
                            app.graph.asrModels.removeListener(listener)
                            quickModelListener = null
                            pendingListenAfterModelDownload = false
                            modelReadyPendingStart = false
                            findViewById<Button>(R.id.listenButton).text = "开始听课"
                        }
                    }
                    is io.github.paper.classhelper.asr.AsrModelManager.State.Preparing -> {
                        findViewById<TextView>(R.id.asrStatusText)?.text = state.message
                        findViewById<Button>(R.id.listenButton).text = "连接下载源…"
                    }
                    is io.github.paper.classhelper.asr.AsrModelManager.State.Downloading -> {
                        findViewById<TextView>(R.id.asrStatusText)?.text = "下载 ${state.fileIndex}/${state.fileCount} ${state.fileName} · ${state.overallPercent}%"
                        findViewById<Button>(R.id.listenButton).text = "模型 ${state.overallPercent}%"
                    }
                }
            }
        }
        quickModelListener = listener
        app.graph.asrModels.addListener(listener)
    }


    private fun tryStartAfterModelDownload() {
        if (!modelReadyPendingStart || isFinishing || isDestroyed) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        modelReadyPendingStart = false
        toast("语音模型已就绪，开始听课")
        requestMicAndStartListening()
    }

    override fun onResume() {
        super.onResume()
        tryStartAfterModelDownload()
    }

    private fun startListeningService() {
        // Android 14+ requires microphone FGS to be started while the app is in foreground; this button is that explicit user action.
        ContextCompat.startForegroundService(this, Intent(this, ClassroomService::class.java))
        openClassroomPanel()
    }

    private fun observeClassroom() {
        lifecycleScope.launch {
            ClassroomBus.state.collectLatest { state ->
                findViewById<Button>(R.id.listenButton).apply {
                    isEnabled = !state.stopping
                    text = when {
                        state.stopping -> "正在结束…"
                        state.listening -> "结束听课"
                        else -> "开始听课"
                    }
                }
                findViewById<TextView>(R.id.asrStatusText).text = state.status
                findViewById<TextView>(R.id.liveTranscriptText).text = state.partial.ifBlank {
                    app.graph.db.recentTranscripts(5, state.sessionId ?: selectedSessionId).joinToString("\n") { it.text }
                }
                if (state.lastQuestion.isNotBlank() || state.answer.isNotBlank()) {
                    findViewById<View>(R.id.answerCard).visibility = View.VISIBLE
                    findViewById<TextView>(R.id.questionPreview).text = state.lastQuestion
                    findViewById<TextView>(R.id.answerPreview).text = state.answer.ifBlank { "正在查资料并生成参考答案…" }
                    if (state.lastQuestion.isNotBlank() && state.lastQuestion != surfacedQuestion && !drawMode) {
                        surfacedQuestion = state.lastQuestion
                        openClassroomPanel()
                    }
                }
                suggestedPage = state.matchedPage
                val matchRow = findViewById<View>(R.id.matchRow)
                if (state.matchedPage != null) { matchRow.visibility = View.VISIBLE; findViewById<TextView>(R.id.matchText).text = "可能讲到 P${state.matchedPage + 1} · ${state.matchedLabel}" }
                else matchRow.visibility = View.GONE
                if (state.historyVersion != observedHistoryVersion) { observedHistoryVersion = state.historyVersion; refreshHistory() }
            }
        }
    }

    private fun refreshHistory() {
        val sessionId = ClassroomBus.state.value.sessionId ?: selectedSessionId
        val questions = app.graph.db.recentQuestions(8, sessionId)
        val notesList = app.graph.db.recentNotes(2, sessionId)
        findViewById<TextView>(R.id.historyText).text = buildString {
            questions.forEach { q -> appendLine("Q：${q.question}\nA：${q.answer}\n") }
            if (notesList.isNotEmpty()) { appendLine("—— 自动笔记 ——"); notesList.forEach { appendLine(it.text); appendLine() } }
        }.ifBlank { "暂无课堂问题 / 笔记" }
    }

    private fun renderHomeModelState(state: AsrModelManager.State) {
        val status = findViewById<TextView>(R.id.homeModelStatusText)
        val progress = findViewById<ProgressBar>(R.id.homeModelProgress)
        val action = findViewById<Button>(R.id.homeModelButton)
        val quickListen = findViewById<Button>(R.id.emptyListenButton)
        when (state) {
            AsrModelManager.State.Missing -> {
                status.text = "未安装 · 首次约 230 MB，下载一次后完全本地识别"
                progress.visibility = View.GONE
                progress.isIndeterminate = false
                action.isEnabled = true
                action.text = "后台下载"
                quickListen.text = "直接开始听课"
            }
            is AsrModelManager.State.Preparing -> {
                status.text = state.message
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = true
                action.isEnabled = true
                action.text = "取消"
                quickListen.text = "模型准备中"
            }
            is AsrModelManager.State.Downloading -> {
                val doneMb = state.downloaded / 1024.0 / 1024.0
                val totalMb = state.total / 1024.0 / 1024.0
                status.text = String.format(Locale.getDefault(), "后台下载 %d%% · %s · %.0f / %.0f MB", state.overallPercent, state.fileName, doneMb, totalMb)
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = false
                progress.progress = state.overallPercent
                action.isEnabled = true
                action.text = "取消"
                quickListen.text = "模型 ${state.overallPercent}%"
            }
            is AsrModelManager.State.Ready -> {
                status.text = "已就绪 · SenseVoiceSmall 本地识别可直接使用"
                progress.visibility = View.GONE
                progress.isIndeterminate = false
                action.isEnabled = true
                action.text = "开始听课"
                quickListen.text = "开始听课"
            }
            is AsrModelManager.State.Error -> {
                status.text = "下载失败 · ${state.message}"
                progress.visibility = View.GONE
                progress.isIndeterminate = false
                action.isEnabled = true
                action.text = "重新下载"
                quickListen.text = "开始听课"
            }
        }
    }

    private fun jumpTo(page: Int) {
        if (pageCount <= 0) return
        val p = page.coerceIn(0, pageCount - 1); currentPage = p; app.graph.settings.currentPage = p; pdfView.jumpTo(p, false)
        if (drawMode) configureInkForPage(p); updatePageLabel()
    }

    private fun updatePageLabel() {
        val mark = workspace?.let { if (app.graph.db.isBookmarked(it.id, currentPage)) " ★" else "" }.orEmpty()
        val jumpButton = findViewById<Button>(R.id.jumpPageButton)
        if (pageCount > 0) {
            val pageLabel = "${currentPage + 1} / $pageCount"
            findViewById<TextView>(R.id.pageText).text = "第 $pageLabel 页$mark"
            jumpButton.text = pageLabel
            jumpButton.contentDescription = "当前第 ${currentPage + 1} 页，共 $pageCount 页，点击跳转"
        } else {
            findViewById<TextView>(R.id.pageText).text = "还未打开 PDF"
            jumpButton.text = "页码"
            jumpButton.contentDescription = "跳转页码"
        }
    }

    override fun onStart() {
        super.onStart()
        app.graph.asrModels.addListener(homeModelListener)
        app.graph.asrModels.refresh()
    }

    override fun onStop() {
        app.graph.asrModels.removeListener(homeModelListener)
        // Activity teardown cancels lifecycleScope. Use the process scope so the final PDF sync is
        // not randomly aborted when the user presses Back, switches apps, or Android recreates UI.
        workspace?.let { ws ->
            app.applicationScope.launch(Dispatchers.IO) { writer.flushNow(ws, syncSource = true) }
        }
        super.onStop()
    }

    override fun onDestroy() {
        quickModelListener?.let { app.graph.asrModels.removeListener(it) }
        quickModelListener = null
        pendingListenAfterModelDownload = false
        modelReadyPendingStart = false
        chromeHandler.removeCallbacks(autoHideChrome)
        super.onDestroy()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
