package io.github.paper.classhelper.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import io.github.paper.classhelper.R
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.util.Collections
import java.util.WeakHashMap

/**
 * Renders AI-authored content as native Android rich text instead of exposing raw Markdown/LaTeX.
 *
 * ReaderActivity still assigns normal text. This observer turns those assignments into Markwon
 * spans, while aggressively reusing an already-rendered result when the same classroom state is
 * emitted again. That matters because LaTeX spans carry Drawables and repeated parsing of an
 * unchanged answer can otherwise create large short-lived allocations next to PDFView's bitmap
 * cache and push the process into OOM.
 */
object AiRichTextRenderer {
    private const val MAX_ANSWER_CHARS = 48_000
    private const val MAX_HISTORY_CHARS = 24_000
    private const val TRUNCATED_NOTICE = "\n\n> 内容过长，界面显示已截断；完整内容仍保存在课堂记录中。"

    private data class RenderKey(val length: Int, val hash: Int)

    private val boundViews = Collections.newSetFromMap(WeakHashMap<TextView, Boolean>())
    private val renderingViews = Collections.newSetFromMap(WeakHashMap<TextView, Boolean>())
    private val markwonByView = WeakHashMap<TextView, Markwon>()
    private val lastKeyByView = WeakHashMap<TextView, RenderKey>()
    private val lastRenderedByView = WeakHashMap<TextView, CharSequence>()

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = bind(activity)
            override fun onActivityResumed(activity: Activity) = bind(activity)
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun bind(activity: Activity) {
        bind(activity.findViewById(R.id.answerPreview))
        bind(activity.findViewById(R.id.historyText))
    }

    private fun bind(view: TextView?) {
        if (view == null || !boundViews.add(view)) return

        markwonByView[view] = buildMarkwon(view)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (renderingViews.contains(view)) return
                render(view, s?.toString().orEmpty())
            }
        }
        view.addTextChangedListener(watcher)

        // Render any content that was assigned during ReaderActivity.onCreate before lifecycle binding.
        render(view, view.text?.toString().orEmpty())
    }

    private fun render(view: TextView, raw: String) {
        val display = boundedForDisplay(view, raw)
        val key = RenderKey(display.length, display.hashCode())

        // ClassroomBus emits for status/transcript/page changes as well as answer changes. Reader's
        // normal `.text = state.answer` therefore often assigns the same answer repeatedly. Restore
        // the previous Spannable instead of reparsing Markdown/LaTeX and allocating new math drawables.
        if (lastKeyByView[view] == key) {
            val rendered = lastRenderedByView[view]
            if (rendered != null && view.text !== rendered) {
                if (!renderingViews.add(view)) return
                try {
                    view.text = rendered
                } finally {
                    renderingViews.remove(view)
                }
            }
            return
        }

        if (!renderingViews.add(view)) return
        try {
            val markwon = markwonByView[view] ?: buildMarkwon(view).also { markwonByView[view] = it }
            markwon.setMarkdown(view, MarkdownMathNormalizer.normalize(display))
            lastKeyByView[view] = key
            lastRenderedByView[view] = view.text
        } catch (_: Exception) {
            // Rendering must never hide an answer. Fall back to bounded raw source on malformed
            // Markdown or unsupported LaTeX. Deliberately do not catch OutOfMemoryError here.
            view.text = display
            lastKeyByView[view] = key
            lastRenderedByView[view] = view.text
        } finally {
            renderingViews.remove(view)
        }
    }

    private fun boundedForDisplay(view: TextView, raw: String): String {
        val maxChars = when (view.id) {
            R.id.historyText -> MAX_HISTORY_CHARS
            R.id.answerPreview -> MAX_ANSWER_CHARS
            else -> MAX_ANSWER_CHARS
        }
        if (raw.length <= maxChars) return raw
        return raw.take(maxChars) + TRUNCATED_NOTICE
    }

    private fun buildMarkwon(view: TextView): Markwon {
        // Never let a cached renderer retain a ReaderActivity through its Context. A WeakHashMap
        // key is not enough if the value points back to Activity -> View hierarchy -> key.
        val appContext = view.context.applicationContext
        return Markwon.builder(appContext)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(
                JLatexMathPlugin.create(view.textSize, object : JLatexMathPlugin.BuilderConfigure {
                    override fun configureBuilder(builder: JLatexMathPlugin.Builder) {
                        builder.inlinesEnabled(true)
                    }
                }),
            )
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(appContext))
            .usePlugin(TaskListPlugin.create(appContext))
            .build()
    }
}

/**
 * Markwon's LaTeX extension uses double-dollar delimiters for inline math and double-dollar lines
 * for blocks. AI providers commonly emit the wider Markdown/LaTeX dialect (single-dollar math,
 * backslash-parentheses and backslash-brackets), so normalize those forms while leaving fenced and
 * inline code untouched.
 */
object MarkdownMathNormalizer {
    private const val MARKWON_MATH = "\$\$"

    fun normalize(markdown: String): String {
        if (markdown.isEmpty()) return markdown
        val lines = markdown.split('\n')
        val out = StringBuilder(markdown.length + 32)
        var fence: Char? = null
        var displayBracket = false

        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            val fenceChar = when {
                trimmed.startsWith("```") -> '`'
                trimmed.startsWith("~~~") -> '~'
                else -> null
            }

            if (fenceChar != null && (fence == null || fence == fenceChar)) {
                fence = if (fence == null) fenceChar else null
                out.append(line)
            } else if (fence != null) {
                out.append(line)
            } else if (trimmed == "\\[") {
                displayBracket = true
                out.append(MARKWON_MATH)
            } else if (trimmed == "\\]" && displayBracket) {
                displayBracket = false
                out.append(MARKWON_MATH)
            } else if (displayBracket) {
                out.append(line)
            } else {
                out.append(normalizeOutsideCodeSpans(line))
            }
            if (index != lines.lastIndex) out.append('\n')
        }
        return out.toString()
    }

    private fun normalizeOutsideCodeSpans(line: String): String {
        if ('`' !in line) return normalizeMathDelimiters(line)
        val out = StringBuilder(line.length + 8)
        var i = 0
        while (i < line.length) {
            if (line[i] != '`') {
                val next = line.indexOf('`', i).let { if (it < 0) line.length else it }
                out.append(normalizeMathDelimiters(line.substring(i, next)))
                i = next
                continue
            }

            var ticks = 1
            while (i + ticks < line.length && line[i + ticks] == '`') ticks++
            val delimiter = "`".repeat(ticks)
            val close = line.indexOf(delimiter, i + ticks)
            if (close < 0) {
                out.append(line.substring(i))
                break
            }
            out.append(line, i, close + ticks)
            i = close + ticks
        }
        return out.toString()
    }

    private fun normalizeMathDelimiters(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("\\(", i) -> {
                    val close = text.indexOf("\\)", i + 2)
                    if (close >= 0) {
                        out.append(MARKWON_MATH).append(text, i + 2, close).append(MARKWON_MATH)
                        i = close + 2
                    } else {
                        out.append(text[i++])
                    }
                }
                text.startsWith("\\[", i) -> {
                    val close = text.indexOf("\\]", i + 2)
                    if (close >= 0) {
                        out.append(MARKWON_MATH).append(text, i + 2, close).append(MARKWON_MATH)
                        i = close + 2
                    } else {
                        out.append(text[i++])
                    }
                }
                text.startsWith(MARKWON_MATH, i) -> {
                    val close = text.indexOf(MARKWON_MATH, i + MARKWON_MATH.length)
                    if (close >= 0) {
                        out.append(text, i, close + MARKWON_MATH.length)
                        i = close + MARKWON_MATH.length
                    } else {
                        out.append(text.substring(i))
                        break
                    }
                }
                text[i] == '$' && !isEscaped(text, i) -> {
                    val close = findSingleDollarClose(text, i + 1)
                    if (close > i + 1) {
                        val body = text.substring(i + 1, close)
                        if (looksLikeMath(body)) {
                            out.append(MARKWON_MATH).append(body).append(MARKWON_MATH)
                        } else {
                            out.append('$').append(body).append('$')
                        }
                        i = close + 1
                    } else {
                        out.append(text[i++])
                    }
                }
                else -> out.append(text[i++])
            }
        }
        return out.toString()
    }

    private fun findSingleDollarClose(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == '$' && !isEscaped(text, i)) {
                if ((i + 1 < text.length && text[i + 1] == '$') || (i > 0 && text[i - 1] == '$')) {
                    i++
                    continue
                }
                return i
            }
            i++
        }
        return -1
    }

    private fun looksLikeMath(body: String): Boolean {
        val value = body.trim()
        if (value.isEmpty()) return false
        // Keep ordinary currency-like values readable instead of turning them into formulas.
        if (value.matches(Regex("[+-]?\\d+(?:[.,]\\d+)?(?:\\s*(?:USD|CNY|RMB|元|美元|人民币))?", RegexOption.IGNORE_CASE))) {
            return false
        }
        return value.any { it.isLetter() } || value.any { it in "=+-*/^_{}[]()\\<>" }
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var slashes = 0
        var i = index - 1
        while (i >= 0 && text[i] == '\\') {
            slashes++
            i--
        }
        return slashes % 2 == 1
    }
}
