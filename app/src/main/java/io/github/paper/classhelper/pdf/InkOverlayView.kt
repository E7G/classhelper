package io.github.paper.classhelper.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Low-latency annotation surface that stays in the same viewport as PDFView.
 *
 * One finger draws/erases. Two fingers pan/zoom through [onViewportGesture] without
 * reloading or refitting the PDF, so entering annotation mode never moves the page.
 */
class InkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    enum class Tool { PEN, HIGHLIGHTER, ERASER }
    data class PdfPoint(val x: Float, val y: Float)
    data class Stroke(
        val points: List<PdfPoint>,
        val widthPt: Float,
        val color: Int,
        val opacity: Float,
        val tool: Tool,
    )

    private data class Preview(val stroke: Stroke, val path: Path = Path(), val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG))

    var onStrokeFinished: ((Stroke) -> Unit)? = null
    var onEraserPathFinished: ((List<PdfPoint>, Float) -> Unit)? = null
    /** scaleFactor, focusX, focusY, deltaX, deltaY */
    var onViewportGesture: ((Float, Float, Float, Float, Float) -> Unit)? = null

    private var llx = 0f
    private var lly = 0f
    private var pdfWidth = 595f
    private var pdfHeight = 842f
    private var rotation = 0
    private var pageViewport = RectF()

    private var tool = Tool.PEN
    private var penColor = 0xff111111.toInt()
    private var penWidthDp = 2.2f
    private var eraserRadiusDp = 22f

    private val currentScreen = mutableListOf<Pair<Float, Float>>()
    private val previewPath = Path()
    private val committed = mutableListOf<Preview>()

    private val eraserScreen = mutableListOf<Pair<Float, Float>>()
    private val eraserTrail = Path()
    private var eraserCursorX = 0f
    private var eraserCursorY = 0f
    private var eraserActive = false
    private var eraserFeedbackActive = false

    private var viewportGestureActive = false
    private var inputReady = false
    private var singleGestureStarted = false
    private var lastGestureFocusX = 0f
    private var lastGestureFocusY = 0f
    private var lastGestureSpan = 0f

    private val eraserPrimaryColor: Int by lazy {
        val value = TypedValue()
        if (context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true)) value.data
        else Color.DKGRAY
    }

    fun setPdfPageGeometry(
        lowerLeftX: Float,
        lowerLeftY: Float,
        widthPt: Float,
        heightPt: Float,
        pageRotation: Int,
    ) {
        if (widthPt <= 0 || heightPt <= 0) return
        llx = lowerLeftX
        lly = lowerLeftY
        pdfWidth = widthPt
        pdfHeight = heightPt
        rotation = ((pageRotation % 360) + 360) % 360
        invalidate()
    }

    /** Screen-space rectangle occupied by the current PDF page after zoom + pan. */
    fun setPageViewport(rect: RectF) {
        if (pageViewport == rect) return
        pageViewport.set(rect)
        rebuildCommittedPreview()
        invalidate()
    }

    fun setTool(value: Tool) {
        tool = value
        resetTransientGesture()
        if (value == Tool.ERASER && !pageViewport.isEmpty) {
            eraserCursorX = pageViewport.centerX()
            eraserCursorY = pageViewport.centerY()
            // A faint idle cursor makes the selected eraser mode immediately visible.
            eraserFeedbackActive = true
        }
        invalidate()
    }

    fun getTool(): Tool = tool
    fun setPenColor(value: Int) { penColor = value; invalidate() }
    fun setPenWidthDp(value: Float) { penWidthDp = value.coerceIn(0.8f, 8f); invalidate() }
    fun setEraserRadiusDp(value: Float) { eraserRadiusDp = value.coerceIn(12f, 42f); invalidate() }
    /** Keep this view enabled while geometry is loading so touches never fall through to PDFView. */
    fun setInputReady(value: Boolean) {
        inputReady = value
        if (!value) {
            singleGestureStarted = false
            resetTransientGesture()
        }
    }
    fun clearCommittedPreview() { committed.clear(); previewPath.reset(); invalidate() }
    fun removeLastCommittedPreview() { if (committed.isNotEmpty()) committed.removeAt(committed.lastIndex); invalidate() }
    fun clearEraserFeedback() {
        eraserFeedbackActive = false
        eraserScreen.clear()
        eraserTrail.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        committed.forEach { canvas.drawPath(it.path, it.paint) }
        if (!previewPath.isEmpty) canvas.drawPath(previewPath, paintForTool())

        if (tool == Tool.ERASER && (eraserActive || eraserFeedbackActive)) {
            val radiusPx = eraserRadiusDp * resources.displayMetrics.density
            if (!eraserTrail.isEmpty) {
                canvas.drawPath(eraserTrail, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = eraserPrimaryColor
                    alpha = 34
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = radiusPx * 2f
                })
            }
            canvas.drawCircle(eraserCursorX, eraserCursorY, radiusPx, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = eraserPrimaryColor
                alpha = 22
                style = Paint.Style.FILL
            })
            canvas.drawCircle(eraserCursorX, eraserCursorY, radiusPx, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = eraserPrimaryColor
                alpha = 210
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * resources.displayMetrics.density
            })
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // When this overlay is visible we own the complete gesture stream. Never return false:
        // doing so hands the same one-finger gesture to PDFView and makes the page move while
        // the user is trying to write. Two-finger navigation is forwarded explicitly below.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                viewportGestureActive = false
                // Do not require DOWN to land inside an exact computed page rectangle. PDFView's
                // offsets can differ by a few pixels while zoom/autoSpacing settles; rejecting the
                // gesture here made the pen look completely dead. Stroke points are clamped to
                // the page below, so a near-edge touch remains safe.
                singleGestureStarted = inputReady && !pageViewport.isEmpty
                if (singleGestureStarted) {
                    if (tool == Tool.ERASER) startEraser(event.x, event.y)
                    else startStroke(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    // A second finger always means navigation. Discard the unfinished one-finger
                    // mark instead of creating an accidental dot/erase while pinching.
                    currentScreen.clear(); previewPath.reset()
                    eraserScreen.clear(); eraserTrail.reset(); eraserActive = false
                    singleGestureStarted = false
                    beginViewportGesture(event)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (viewportGestureActive || event.pointerCount >= 2) {
                    if (!viewportGestureActive) beginViewportGesture(event)
                    updateViewportGesture(event)
                    return true
                }

                if (!singleGestureStarted) return true
                if (tool == Tool.ERASER) {
                    for (i in 0 until event.historySize) appendEraser(event.getHistoricalX(i), event.getHistoricalY(i))
                    appendEraser(event.x, event.y)
                } else {
                    for (i in 0 until event.historySize) appendStroke(event.getHistoricalX(i), event.getHistoricalY(i))
                    appendStroke(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Keep consuming until the whole pinch gesture finishes. This prevents the
                // remaining finger from suddenly turning into a pen stroke.
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (viewportGestureActive) {
                    viewportGestureActive = false
                    singleGestureStarted = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    resetTransientGesture()
                    return true
                }
                if (singleGestureStarted) {
                    if (tool == Tool.ERASER) finishEraser() else finishStroke(event.x, event.y)
                }
                singleGestureStarted = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                viewportGestureActive = false
                singleGestureStarted = false
                parent?.requestDisallowInterceptTouchEvent(false)
                resetTransientGesture()
                return true
            }
        }
        return true
    }

    private fun startStroke(x: Float, y: Float) {
        currentScreen.clear()
        val cx = x.coerceIn(pageViewport.left, pageViewport.right)
        val cy = y.coerceIn(pageViewport.top, pageViewport.bottom)
        currentScreen += cx to cy
        previewPath.reset()
        previewPath.moveTo(cx, cy)
        invalidate()
    }

    private fun appendStroke(x: Float, y: Float) {
        val cx = x.coerceIn(pageViewport.left, pageViewport.right)
        val cy = y.coerceIn(pageViewport.top, pageViewport.bottom)
        val previous = currentScreen.lastOrNull()
        if (previous != null && hypot(cx - previous.first, cy - previous.second) < 0.6f) return
        if (previous != null) {
            val mx = (previous.first + cx) * 0.5f
            val my = (previous.second + cy) * 0.5f
            // Incremental quadratic smoothing: no full Path rebuild as a stroke grows.
            previewPath.quadTo(previous.first, previous.second, mx, my)
        } else previewPath.moveTo(cx, cy)
        currentScreen += cx to cy
        invalidate()
    }

    private fun finishStroke(x: Float, y: Float) {
        if (currentScreen.isEmpty()) return
        appendStroke(x, y)
        val rect = RectF(pageViewport)
        val pdfPoints = currentScreen.mapNotNull { (sx, sy) -> screenToPdf(sx, sy, rect) }
        if (pdfPoints.size >= 2) {
            val scale = rect.width() / displayWidthPt()
            val currentPaint = paintForTool()
            val widthPt = if (scale > 0f) currentPaint.strokeWidth / scale else 1.5f
            val stroke = Stroke(
                pdfPoints,
                widthPt.coerceIn(0.5f, 30f),
                penColor,
                if (tool == Tool.HIGHLIGHTER) 0.28f else 1f,
                tool,
            )
            committed += Preview(stroke)
            rebuildCommittedPreview()
            onStrokeFinished?.invoke(stroke)
        }
        currentScreen.clear()
        previewPath.reset()
        invalidate()
    }

    private fun startEraser(x: Float, y: Float) {
        eraserScreen.clear(); eraserTrail.reset()
        eraserFeedbackActive = false
        eraserActive = true
        appendEraser(x, y, initial = true)
    }

    private fun appendEraser(x: Float, y: Float, initial: Boolean = false) {
        val cx = x.coerceIn(pageViewport.left, pageViewport.right)
        val cy = y.coerceIn(pageViewport.top, pageViewport.bottom)
        val previous = eraserScreen.lastOrNull()
        if (!initial && previous != null && hypot(cx - previous.first, cy - previous.second) < 0.8f) return
        if (previous == null) eraserTrail.moveTo(cx, cy)
        else {
            val mx = (previous.first + cx) * 0.5f
            val my = (previous.second + cy) * 0.5f
            eraserTrail.quadTo(previous.first, previous.second, mx, my)
        }
        eraserScreen += cx to cy
        eraserCursorX = cx; eraserCursorY = cy
        hideCommittedPreviewAt(cx, cy, eraserRadiusDp * resources.displayMetrics.density)
        invalidate()
    }

    private fun finishEraser() {
        val rect = RectF(pageViewport)
        val pdfPath = eraserScreen.mapNotNull { (sx, sy) -> screenToPdf(sx, sy, rect) }
        if (pdfPath.isNotEmpty()) {
            val scale = rect.width() / displayWidthPt()
            val radiusPt = (eraserRadiusDp * resources.displayMetrics.density / scale.coerceAtLeast(0.001f))
                .coerceIn(4f, 64f)
            onEraserPathFinished?.invoke(pdfPath, radiusPt)
        }
        // Keep the last swept footprint visible until ReaderActivity finishes the short
        // background PDF commit/re-render. This removes the "I swiped but nothing happened"
        // gap for annotations that are already embedded in the PDF bitmap.
        eraserActive = false
        eraserFeedbackActive = pdfPath.isNotEmpty()
        invalidate()
    }

    /** Stroke/object eraser: as soon as the circular footprint touches a local stroke, the
     * whole stroke disappears from the live canvas. The PDF-side writer uses the same distance
     * model, so the immediate preview matches what is persisted after finger-up. */
    private fun hideCommittedPreviewAt(x: Float, y: Float, radiusPx: Float) {
        val pdf = screenToPdf(x, y, pageViewport) ?: return
        val scale = pageViewport.width() / displayWidthPt()
        val radiusPt = radiusPx / scale.coerceAtLeast(0.001f)
        var changed = false
        for (i in committed.lastIndex downTo 0) {
            val stroke = committed[i].stroke
            val threshold = radiusPt + stroke.widthPt * 0.5f
            val hit = if (stroke.points.size == 1) {
                hypot(pdf.x - stroke.points[0].x, pdf.y - stroke.points[0].y) <= threshold
            } else {
                stroke.points.zipWithNext().any { (a, b) ->
                    pointSegmentDistance(pdf.x, pdf.y, a.x, a.y, b.x, b.y) <= threshold
                }
            }
            if (hit) {
                committed.removeAt(i)
                changed = true
            }
        }
        if (changed) invalidate()
    }

    private fun rebuildCommittedPreview() {
        if (pageViewport.isEmpty) return
        val scale = pageViewport.width() / displayWidthPt()
        committed.forEach { preview ->
            val points = preview.stroke.points.mapNotNull { pdfToScreen(it, pageViewport) }
            preview.path.reset()
            if (points.isNotEmpty()) {
                preview.path.moveTo(points.first().first, points.first().second)
                for (i in 1 until points.size) {
                    val prev = points[i - 1]
                    val now = points[i]
                    val mx = (prev.first + now.first) * 0.5f
                    val my = (prev.second + now.second) * 0.5f
                    preview.path.quadTo(prev.first, prev.second, mx, my)
                }
            }
            preview.paint.apply {
                color = preview.stroke.color
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = (preview.stroke.widthPt * scale).coerceAtLeast(0.8f)
                alpha = (preview.stroke.opacity * 255f).toInt().coerceIn(12, 255)
                isAntiAlias = true
            }
        }
    }

    private fun pointSegmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 0.0001f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun beginViewportGesture(event: MotionEvent) {
        if (event.pointerCount < 2) return
        viewportGestureActive = true
        val aX = event.getX(0); val aY = event.getY(0)
        val bX = event.getX(1); val bY = event.getY(1)
        lastGestureFocusX = (aX + bX) * 0.5f
        lastGestureFocusY = (aY + bY) * 0.5f
        lastGestureSpan = hypot(aX - bX, aY - bY).coerceAtLeast(1f)
    }

    private fun updateViewportGesture(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val aX = event.getX(0); val aY = event.getY(0)
        val bX = event.getX(1); val bY = event.getY(1)
        val focusX = (aX + bX) * 0.5f
        val focusY = (aY + bY) * 0.5f
        val span = hypot(aX - bX, aY - bY).coerceAtLeast(1f)
        val scaleFactor = (span / lastGestureSpan).coerceIn(0.82f, 1.22f)
        val dx = focusX - lastGestureFocusX
        val dy = focusY - lastGestureFocusY
        onViewportGesture?.invoke(scaleFactor, focusX, focusY, dx, dy)
        lastGestureFocusX = focusX
        lastGestureFocusY = focusY
        lastGestureSpan = span
    }

    private fun resetTransientGesture() {
        currentScreen.clear(); previewPath.reset()
        eraserScreen.clear(); eraserTrail.reset(); eraserActive = false; eraserFeedbackActive = false
        invalidate()
    }

    private fun paintForTool(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = penColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = (if (tool == Tool.HIGHLIGHTER) 13f else penWidthDp) * resources.displayMetrics.density
        alpha = if (tool == Tool.HIGHLIGHTER) 72 else 255
    }

    private fun screenToPdf(x: Float, y: Float, rect: RectF): PdfPoint? {
        if (!rect.contains(x, y) || rect.width() <= 0f || rect.height() <= 0f) return null
        val dw = displayWidthPt(); val dh = displayHeightPt()
        val xr = (x - rect.left) / rect.width() * dw
        val yr = dh - (y - rect.top) / rect.height() * dh
        val (u, v) = when (rotation) {
            90 -> (pdfWidth - yr) to xr
            180 -> (pdfWidth - xr) to (pdfHeight - yr)
            270 -> yr to (pdfHeight - xr)
            else -> xr to yr
        }
        return PdfPoint(llx + u.coerceIn(0f, pdfWidth), lly + v.coerceIn(0f, pdfHeight))
    }

    private fun pdfToScreen(point: PdfPoint, rect: RectF): Pair<Float, Float>? {
        if (rect.width() <= 0f || rect.height() <= 0f) return null
        val u = (point.x - llx).coerceIn(0f, pdfWidth)
        val v = (point.y - lly).coerceIn(0f, pdfHeight)
        val (xr, yr) = when (rotation) {
            90 -> v to (pdfWidth - u)
            180 -> (pdfWidth - u) to (pdfHeight - v)
            270 -> (pdfHeight - v) to u
            else -> u to v
        }
        val dw = displayWidthPt()
        val dh = displayHeightPt()
        val x = rect.left + xr / dw * rect.width()
        val y = rect.top + (dh - yr) / dh * rect.height()
        return x to y
    }

    private fun displayWidthPt() = if (rotation == 90 || rotation == 270) pdfHeight else pdfWidth
    private fun displayHeightPt() = if (rotation == 90 || rotation == 270) pdfWidth else pdfHeight
}
