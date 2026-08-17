package io.github.paper.classhelper.ui

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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
            if (item.supporting.isNotBlank()) {
                row.addView(TextView(context).apply {
                    text = item.supporting
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
        }
        dialog.show()
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
