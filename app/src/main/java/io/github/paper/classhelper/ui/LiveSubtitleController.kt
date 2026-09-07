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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compact live captions for the reader screen.
 *
 * The caption intentionally lives in a small lower-left overlay above the reader bottom chrome.
 * It is non-clickable/non-focusable, so PDF gestures and reader controls remain the touch target.
 */
object LiveSubtitleController : Application.ActivityLifecycleCallbacks {
    private const val FINAL_HOLD_MS = 4_500L
    private const val CURRENT_CHAR_WINDOW = 96

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
    private var cachedFinal = ""
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
                    cachedFinal = if (state.sessionId == null) {
                        ""
                    } else {
                        runCatching {
                            app.graph.db.recentTranscripts(1, state.sessionId)
                                .lastOrNull()
                                ?.text
                                ?.trim()
                                .orEmpty()
                        }.getOrDefault("")
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
        val latestFinal = compact(cachedFinal, CURRENT_CHAR_WINDOW)
        val current = partial.ifBlank { latestFinal }

        if (current.isBlank()) {
            cancelHide()
            caption.hide(animated = true)
            return
        }

        val isPartial = partial.isNotBlank()
        val signature = "$current\u0000$isPartial"
        if (signature == lastRenderedSignature) return
        lastRenderedSignature = signature

        caption.show(current, isPartial)
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
        val captionWidthDp = when {
            widthDp >= 1000f -> 340
            widthDp >= 600f -> 300
            else -> 240
        }.coerceAtMost((widthDp - 24f).toInt().coerceAtLeast(180))
        val edgeMarginDp = if (widthDp >= 600f) 16 else 12

        root.addView(
            view,
            FrameLayout.LayoutParams(
                (captionWidthDp * density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = (edgeMarginDp * density).toInt()
                bottomMargin = (76f * density).toInt()
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
    private val currentLine = captionTextView(14.5f, Color.WHITE, true).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.START
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        visibility = View.GONE
        alpha = 0f
        translationY = dp(6).toFloat()
        elevation = dp(5).toFloat()
        setPadding(dp(10), dp(7), dp(10), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9).toFloat()
            setColor(Color.argb(196, 8, 8, 8))
            setStroke(dp(1), Color.argb(38, 255, 255, 255))
        }

        // The subtitle is visual-only for touch handling; it must never compete with reader controls.
        isClickable = false
        isLongClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        currentLine.isClickable = false
        currentLine.isLongClickable = false
        currentLine.isFocusable = false

        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(currentLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun show(current: String, partial: Boolean) {
        currentLine.text = current
        currentLine.alpha = if (partial) 1f else 0.94f
        contentDescription = "实时字幕：$current"

        if (visibility != View.VISIBLE || alpha < 0.95f) {
            animate().cancel()
            visibility = View.VISIBLE
            alpha = 0f
            translationY = dp(6).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(120L).start()
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
            .translationY(dp(4).toFloat())
            .setDuration(180L)
            .withEndAction {
                visibility = View.GONE
                translationY = dp(6).toFloat()
            }
            .start()
    }

    private fun captionTextView(sizeSp: Float, color: Int, bold: Boolean) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.START
        includeFontPadding = false
        setLineSpacing(dp(1).toFloat(), 1f)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
