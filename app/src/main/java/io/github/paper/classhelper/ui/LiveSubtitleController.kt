package io.github.paper.classhelper.ui

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.classroom.ClassroomBus
import io.github.paper.classhelper.classroom.ClassroomUiState
import java.lang.ref.WeakReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YouTube-style live captions for the reader screen.
 *
 * This deliberately sits outside ReaderActivity's PDF/annotation gesture hierarchy so showing
 * captions cannot steal touches from PDFView or InkOverlayView. The controller reuses the existing
 * ClassroomBus stream and only queries finalized transcript rows when historyVersion changes.
 */
object LiveSubtitleController : Application.ActivityLifecycleCallbacks {
    private const val FINAL_HOLD_MS = 4_500L
    private const val CURRENT_CHAR_WINDOW = 128
    private const val PREVIOUS_CHAR_WINDOW = 82

    private val mainHandler = Handler(Looper.getMainLooper())
    private var installed = false
    private lateinit var app: ClassHelperApp
    private var stateJob: Job? = null
    private var readerRef: WeakReference<ReaderActivity>? = null
    private var overlay: LiveSubtitleView? = null
    private var hideRunnable: Runnable? = null
    private var latestState = ClassroomUiState()
    private var cachedSessionId: String? = null
    private var cachedHistoryVersion = Long.MIN_VALUE
    private var cachedFinals: List<String> = emptyList()
    private var lastRenderedSignature = ""

    fun install(application: ClassHelperApp) {
        if (installed) return
        installed = true
        app = application
        application.registerActivityLifecycleCallbacks(this)
        stateJob = application.applicationScope.launch {
            ClassroomBus.state.collectLatest { state ->
                latestState = state
                if (state.sessionId != cachedSessionId || state.historyVersion != cachedHistoryVersion) {
                    cachedSessionId = state.sessionId
                    cachedHistoryVersion = state.historyVersion
                    cachedFinals = if (state.sessionId == null) {
                        emptyList()
                    } else {
                        runCatching {
                            app.graph.db.recentTranscripts(2, state.sessionId).map { it.text.trim() }.filter { it.isNotBlank() }
                        }.getOrDefault(emptyList())
                    }
                }
                withContext(Dispatchers.Main) { render(state) }
            }
        }
    }

    private fun render(state: ClassroomUiState) {
        val activity = readerRef?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val caption = ensureOverlay(activity)

        if (!state.listening && !state.stopping) {
            cancelHide()
            lastRenderedSignature = ""
            caption.hide(animated = true)
            return
        }

        val partial = compact(state.partial, CURRENT_CHAR_WINDOW)
        val latestFinal = compact(cachedFinals.lastOrNull().orEmpty(), CURRENT_CHAR_WINDOW)
        val previousFinal = compact(
            if (partial.isNotBlank()) cachedFinals.lastOrNull().orEmpty() else cachedFinals.dropLast(1).lastOrNull().orEmpty(),
            PREVIOUS_CHAR_WINDOW
        )
        val current = partial.ifBlank { latestFinal }

        if (current.isBlank()) {
            cancelHide()
            caption.hide(animated = true)
            return
        }

        val isPartial = partial.isNotBlank()
        val signature = "$previousFinal\u0000$current\u0000$isPartial"
        if (signature == lastRenderedSignature) return
        lastRenderedSignature = signature

        caption.show(previousFinal, current, isPartial)
        cancelHide()
        if (!isPartial) {
            val runnable = Runnable {
                overlay?.hide(animated = true)
                hideRunnable = null
            }
            hideRunnable = runnable
            mainHandler.postDelayed(runnable, FINAL_HOLD_MS)
        }
    }

    private fun compact(raw: String, maxChars: Int): String {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= maxChars) return clean
        return "…" + clean.takeLast(maxChars)
    }

    private fun ensureOverlay(activity: ReaderActivity): LiveSubtitleView {
        overlay?.let { existing -> if (existing.parent != null) return existing }
        val root = activity.findViewById<FrameLayout>(android.R.id.content)
        val view = LiveSubtitleView(activity)
        val density = activity.resources.displayMetrics.density
        val widthDp = activity.resources.displayMetrics.widthPixels / density
        val horizontalMarginDp = when {
            widthDp >= 1000f -> ((widthDp - 760f) / 2f).toInt().coerceAtLeast(24)
            widthDp >= 600f -> 72
            else -> 16
        }
        root.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                val h = (horizontalMarginDp * density).toInt()
                leftMargin = h
                rightMargin = h
                bottomMargin = (86f * density).toInt()
            }
        )
        overlay = view
        return view
    }

    private fun cancelHide() {
        hideRunnable?.let(mainHandler::removeCallbacks)
        hideRunnable = null
    }

    private fun detachOverlay() {
        cancelHide()
        overlay?.let { view ->
            view.animate().cancel()
            (view.parent as? ViewGroup)?.removeView(view)
        }
        overlay = null
        lastRenderedSignature = ""
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is ReaderActivity) return
        readerRef = WeakReference(activity)
        lastRenderedSignature = ""
        render(latestState)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity !== readerRef?.get()) return
        readerRef = null
        detachOverlay()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity !== readerRef?.get()) return
        readerRef = null
        detachOverlay()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

private class LiveSubtitleView(activity: Activity) : LinearLayout(activity) {
    private val density = resources.displayMetrics.density
    private val previousLine = captionTextView(14f, Color.argb(190, 255, 255, 255), false).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        visibility = View.GONE
    }
    private val currentLine = captionTextView(19f, Color.WHITE, true).apply {
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.START
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        alpha = 0f
        translationY = dp(10).toFloat()
        elevation = dp(8).toFloat()
        setPadding(dp(16), dp(10), dp(16), dp(11))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(11).toFloat()
            setColor(Color.argb(218, 10, 10, 10))
            setStroke(dp(1), Color.argb(55, 255, 255, 255))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(previousLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(currentLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })
    }

    fun show(previous: String, current: String, partial: Boolean) {
        previousLine.text = previous
        previousLine.visibility = if (previous.isBlank()) View.GONE else View.VISIBLE
        currentLine.text = current
        currentLine.alpha = if (partial) 1f else 0.96f
        contentDescription = if (previous.isBlank()) "实时字幕：$current" else "实时字幕：$previous，$current"

        if (visibility != View.VISIBLE || alpha < 0.95f) {
            animate().cancel()
            visibility = View.VISIBLE
            alpha = 0f
            translationY = dp(10).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(150L).start()
        }
    }

    fun hide(animated: Boolean) {
        if (visibility != View.VISIBLE) return
        animate().cancel()
        if (!animated) {
            visibility = View.GONE
            alpha = 0f
            return
        }
        animate()
            .alpha(0f)
            .translationY(dp(6).toFloat())
            .setDuration(220L)
            .withEndAction {
                visibility = View.GONE
                translationY = dp(10).toFloat()
            }
            .start()
    }

    private fun captionTextView(sizeSp: Float, color: Int, bold: Boolean) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setLineSpacing(dp(2).toFloat(), 1f)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
