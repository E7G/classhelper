package io.github.paper.classhelper.ui

import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.R

/**
 * Material 3 Expressive dialog shell used across ClassHelper.
 * It deliberately avoids AlertDialog's legacy title/message/list layouts: title, body and list
 * choices are composed as Material content so spacing and hierarchy stay consistent everywhere.
 */
object Md3eDialogUi {
    data class Item(
        val title: String,
        val supporting: String = "",
        val danger: Boolean = false,
    )

    fun showList(
        context: Context,
        title: String,
        items: List<Item>,
        closeLabel: String = "关闭",
        onSelected: (Int) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        val cards = mutableListOf<View>()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        items.forEachIndexed { index, item ->
            val card = MaterialCardView(context).apply {
                radius = 22f * density
                cardElevation = 0f
                strokeWidth = dp(density, 1)
                strokeColor = colorAttr(context, com.google.android.material.R.attr.colorOutlineVariant, 0x33000000)
                setCardBackgroundColor(
                    colorAttr(
                        context,
                        if (item.danger) com.google.android.material.R.attr.colorErrorContainer
                        else com.google.android.material.R.attr.colorSurfaceContainerHigh,
                        0xfff2f2f2.toInt(),
                    )
                )
                isClickable = true
                isFocusable = true
                isLongClickable = title == "书签" && context is ReaderActivity
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(density, 18), dp(density, 13), dp(density, 18), dp(density, 13))
            }
            row.addView(TextView(context).apply {
                text = item.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorAttr(context, if (item.danger) com.google.android.material.R.attr.colorOnErrorContainer else com.google.android.material.R.attr.colorOnSurface, 0xff1d1b20.toInt()))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (item.supporting.isNotBlank() || (title == "书签" && context is ReaderActivity)) {
                row.addView(TextView(context).apply {
                    text = when {
                        title == "书签" && context is ReaderActivity && item.supporting.isNotBlank() -> "${item.supporting} · 长按编辑或删除"
                        title == "书签" && context is ReaderActivity -> "长按编辑或删除"
                        else -> item.supporting
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTextColor(colorAttr(context, if (item.danger) com.google.android.material.R.attr.colorOnErrorContainer else com.google.android.material.R.attr.colorOnSurfaceVariant, 0xff49454f.toInt()))
                    setLineSpacing(0f, 1.08f)
                    maxLines = 5
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(density, 4)
                    }
                })
            }
            card.addView(row)
            list.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(density, if (index == 0) 0 else 8)
            })
            cards += card
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(list)
        }
        val body = contentWithTitle(context, title, scroll)
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(body)
            .setNegativeButton(closeLabel, null)
            .create()
        cards.forEachIndexed { index, card ->
            card.setOnClickListener {
                dialog.dismiss()
                onSelected(index)
            }
            if (title == "书签" && context is ReaderActivity) {
                card.setOnLongClickListener {
                    dialog.dismiss()
                    showBookmarkActions(context, index)
                    true
                }
            }
        }
        dialog.show()
    }

    private fun showBookmarkActions(context: ReaderActivity, index: Int) {
        val app = context.application as? ClassHelperApp ?: return
        val documentId = app.graph.settings.currentDocumentId ?: return
        val bookmark = app.graph.db.bookmarks(documentId).getOrNull(index) ?: return
        val displayLabel = bookmark.label.ifBlank { "P${bookmark.page + 1}" }
        showList(
            context = context,
            title = "第 ${bookmark.page + 1} 页书签",
            items = listOf(
                Item("编辑名称", displayLabel),
                Item("删除书签", "从当前 PDF 的书签列表中移除", danger = true),
            ),
        ) { action ->
            when (action) {
                0 -> showBookmarkRename(context, documentId, bookmark.page, displayLabel)
                1 -> showConfirm(
                    context = context,
                    title = "删除书签？",
                    message = "将删除第 ${bookmark.page + 1} 页的书签“$displayLabel”。PDF 和批注不会受到影响。",
                    positiveLabel = "删除",
                    danger = true,
                ) {
                    app.graph.db.writableDatabase.delete(
                        "bookmarks",
                        "document_id=? AND page=?",
                        arrayOf(documentId, bookmark.page.toString()),
                    )
                    Toast.makeText(context, "书签已删除", Toast.LENGTH_SHORT).show()
                    refreshBookmarkList(context)
                }
            }
        }
    }

    private fun showBookmarkRename(context: ReaderActivity, documentId: String, page: Int, currentLabel: String) {
        val input = TextInputLayout(context).apply {
            hint = "书签名称"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val edit = TextInputEditText(context).apply {
            setText(currentLabel)
            setSelectAllOnFocus(true)
            maxLines = 2
        }
        input.addView(
            edit,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        showContent(
            context = context,
            title = "编辑第 ${page + 1} 页书签",
            content = input,
            positiveLabel = "保存",
        ) {
            val app = context.application as? ClassHelperApp ?: return@showContent
            val label = edit.text?.toString()?.trim().orEmpty().ifBlank { "P${page + 1}" }.take(80)
            val values = ContentValues().apply { put("label", label) }
            app.graph.db.writableDatabase.update(
                "bookmarks",
                values,
                "document_id=? AND page=?",
                arrayOf(documentId, page.toString()),
            )
            Toast.makeText(context, "书签已更新", Toast.LENGTH_SHORT).show()
            refreshBookmarkList(context)
        }
        edit.requestFocus()
    }

    private fun refreshBookmarkList(context: ReaderActivity) {
        context.findViewById<View>(R.id.bookmarkListButton)?.post {
            context.findViewById<View>(R.id.bookmarkListButton)?.performClick()
        }
    }

    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        positiveLabel: String,
        negativeLabel: String = "取消",
        danger: Boolean = false,
        onNegative: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        onPositive: () -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        val messageView = TextView(context).apply {
            text = message
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colorAttr(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xff49454f.toInt()))
            setLineSpacing(dp(density, 3).toFloat(), 1f)
        }
        showContent(
            context = context,
            title = title,
            content = messageView,
            positiveLabel = positiveLabel,
            negativeLabel = negativeLabel,
            danger = danger,
            onNegative = onNegative,
            onCancel = onCancel,
            onPositive = onPositive,
        )
    }

    fun showContent(
        context: Context,
        title: String,
        content: View,
        positiveLabel: String,
        negativeLabel: String = "取消",
        danger: Boolean = false,
        onNegative: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        onPositive: () -> Unit,
    ) {
        val body = contentWithTitle(context, title, content)
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(body)
            .setNegativeButton(negativeLabel) { _, _ -> onNegative?.invoke() }
            .setPositiveButton(positiveLabel) { _, _ -> onPositive() }
            .create()
        dialog.setOnCancelListener { onCancel?.invoke() }
        dialog.setOnShowListener {
            if (danger) {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(
                    colorAttr(context, androidx.appcompat.R.attr.colorError, 0xffba1a1a.toInt())
                )
            }
        }
        dialog.show()
    }

    fun contentWithTitle(context: Context, title: String, content: View): LinearLayout {
        val density = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(density, 24), dp(density, 22), dp(density, 24), dp(density, 4))
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorAttr(context, com.google.android.material.R.attr.colorOnSurface, 0xff1d1b20.toInt()))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(density, 16)
            })
        }
    }

    private fun dp(density: Float, value: Int): Int = (value * density + 0.5f).toInt()

    private fun colorAttr(context: Context, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) value.data else fallback
    }
}
