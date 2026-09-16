package io.github.paper.classhelper.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.WeakHashMap

/** Adds discoverable "new blank PDF" entry points to Reader without creating a second PDF editor path. */
object BlankPdfController {
    private const val EMPTY_BUTTON_TAG = "classhelper_blank_pdf_empty"
    private const val TOOL_BUTTON_TAG = "classhelper_blank_pdf_tool"
    private val boundActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = bind(activity)
            override fun onActivityResumed(activity: Activity) = bind(activity)
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) { boundActivities.remove(activity) }
        })
    }

    private fun bind(activity: Activity) {
        if (activity !is ReaderActivity || !boundActivities.add(activity)) return
        addEmptyStateButton(activity)
        addToolButton(activity)
    }

    private fun addEmptyStateButton(activity: ReaderActivity) {
        val open = activity.findViewById<MaterialButton>(R.id.emptyOpenButton) ?: return
        val parent = open.parent as? LinearLayout ?: return
        if (parent.findViewWithTag<MaterialButton>(EMPTY_BUTTON_TAG) != null) return

        val button = MaterialButton(activity).apply {
            tag = EMPTY_BUTTON_TAG
            text = "新建空白 PDF"
            isAllCaps = false
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

    private fun addToolButton(activity: ReaderActivity) {
        val scroll = activity.findViewById<HorizontalScrollView>(R.id.moreToolsBar) ?: return
        val row = scroll.getChildAt(0) as? LinearLayout ?: return
        if (row.findViewWithTag<MaterialButton>(TOOL_BUTTON_TAG) != null) return

        val button = MaterialButton(activity).apply {
            tag = TOOL_BUTTON_TAG
            text = "新建 PDF"
            isAllCaps = false
            minWidth = 0
            setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
            setOnClickListener { promptForName(activity) }
        }
        row.addView(
            button,
            0,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 46)),
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
                    val intent = Intent(activity, ReaderActivity::class.java).apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    activity.startActivity(intent)
                    activity.finish()
                }.onFailure {
                    Toast.makeText(activity, "新建 PDF 失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
