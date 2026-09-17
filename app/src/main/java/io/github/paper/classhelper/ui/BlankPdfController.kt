package io.github.paper.classhelper.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.ahmer.pdfviewer.PDFView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adds always-discoverable "new blank PDF" entry points to Reader. */
object BlankPdfController {
    private const val EMPTY_BUTTON_TAG = "classhelper_blank_pdf_empty"
    private const val TOP_BUTTON_TAG = "classhelper_blank_pdf_top"

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

    /**
     * Binding is intentionally idempotent and retried on resume. Previous builds marked an
     * Activity as bound before verifying that its views were ready; when that first lookup failed,
     * the entry points were never installed for that Reader instance.
     */
    private fun bind(activity: Activity) {
        if (activity !is ReaderActivity) return
        addEmptyStateButton(activity)
        addTopBarButton(activity)
    }

    private fun addEmptyStateButton(activity: ReaderActivity) {
        val open = activity.findViewById<MaterialButton>(R.id.emptyOpenButton) ?: return
        val parent = open.parent as? LinearLayout ?: return
        if (parent.findViewWithTag<MaterialButton>(EMPTY_BUTTON_TAG) != null) return

        val button = MaterialButton(activity).apply {
            tag = EMPTY_BUTTON_TAG
            text = "新建空白 PDF"
            isAllCaps = false
            contentDescription = "新建并打开空白 PDF"
            setOnClickListener { promptForName(activity) }
        }
        parent.addView(
            button,
            (parent.indexOfChild(open) + 1).coerceAtMost(parent.childCount),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 52)).apply {
                topMargin = dp(activity, 10)
            },
        )
    }

    /** Always visible beside “打开” whenever the Reader chrome is visible. */
    private fun addTopBarButton(activity: ReaderActivity) {
        val open = activity.findViewById<MaterialButton>(R.id.openButton) ?: return
        val row = open.parent as? LinearLayout ?: return
        if (row.findViewWithTag<MaterialButton>(TOP_BUTTON_TAG) != null) return

        val button = MaterialButton(activity).apply {
            tag = TOP_BUTTON_TAG
            text = "新建"
            isAllCaps = false
            minWidth = 0
            textSize = 10f
            contentDescription = "新建并打开空白 PDF"
            setPadding(dp(activity, 8), 0, dp(activity, 8), 0)
            setOnClickListener { promptForName(activity) }
        }
        row.addView(
            button,
            (row.indexOfChild(open) + 1).coerceAtMost(row.childCount),
            LinearLayout.LayoutParams(dp(activity, 62), dp(activity, 46)).apply {
                marginStart = dp(activity, 2)
            },
        )
    }

    private fun promptForName(activity: ReaderActivity) {
        val edit = TextInputEditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            hint = "例如：高数课堂笔记"
        }
        val input = TextInputLayout(activity).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = "PDF 名称"
            addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val padding = dp(activity, 4)
            setPadding(padding, padding, padding, 0)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("新建空白 PDF")
            .setMessage("将创建一页 A4 空白 PDF，并立即打开。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = edit.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    input.error = "请输入 PDF 名称"
                    edit.requestFocus()
                    return@setOnClickListener
                }
                input.error = null
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                createAndOpen(activity, name) { dialog.dismiss() }
            }
            edit.requestFocus()
        }
        dialog.show()
    }

    private fun createAndOpen(activity: ReaderActivity, name: String, dismiss: () -> Unit) {
        val app = activity.application as ClassHelperApp
        app.applicationScope.launch(Dispatchers.IO) {
            val result = runCatching { app.graph.workspace.createBlankSource(name) }
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                result.onSuccess { uri ->
                    dismiss()

                    // Do not stack ReaderActivity instances. Each Reader owns PDFView bitmap caches,
                    // annotation state and rich-text math drawables; keeping old Readers on the back
                    // stack can exhaust the 512 MiB app heap after several document switches.
                    // Recreate the current Reader with the new URI instead: onStop still flushes the
                    // old workspace, while recycle() releases PDF page bitmaps immediately.
                    activity.findViewById<PDFView>(R.id.pdfView)?.recycle()
                    activity.intent = Intent(activity.intent).apply {
                        action = Intent.ACTION_VIEW
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    activity.recreate()
                }.onFailure {
                    Toast.makeText(activity, "新建 PDF 失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
