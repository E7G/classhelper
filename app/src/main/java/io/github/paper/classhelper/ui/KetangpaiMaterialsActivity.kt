package io.github.paper.classhelper.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.ketangpai.KetangpaiClient
import io.github.paper.classhelper.ketangpai.KetangpaiCourse
import io.github.paper.classhelper.ketangpai.KetangpaiResource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Flat browser for the current user's accessible KETANGPAI course resources. */
class KetangpaiMaterialsActivity : AppCompatActivity() {
    private lateinit var app: ClassHelperApp
    private lateinit var client: KetangpaiClient
    private lateinit var course: KetangpaiCourse
    private lateinit var materialsBody: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var refreshButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ClassHelperApp
        client = KetangpaiClient(app.graph.settings)
        course = courseFromIntent(intent) ?: run {
            finish()
            return
        }
        setContentView(buildContent())
        refreshButton.setOnClickListener { lifecycleScope.launch { loadResources() } }
        lifecycleScope.launch { loadResources() }
    }

    private suspend fun loadResources() {
        setBusy(true, "正在读取《${course.name}》课程资料…")
        materialsBody.removeAllViews()
        runCatching { client.listResources(course) }
            .onSuccess { resources ->
                renderResources(resources)
                val restricted = resources.count { !it.downloadAllowed }
                val suffix = if (restricted > 0) " · $restricted 个资料仅显示元数据" else ""
                setBusy(
                    false,
                    if (resources.isEmpty()) "这门课程暂时没有检测到资料" else "共 ${resources.size} 个资料$suffix · 点击可访问资料进行预览",
                )
            }
            .onFailure {
                materialsBody.removeAllViews()
                setBusy(false, "课程资料读取失败：${it.message ?: it.javaClass.simpleName}")
            }
    }

    private fun renderResources(resources: List<KetangpaiResource>) {
        materialsBody.removeAllViews()
        if (resources.isEmpty()) {
            materialsBody.addView(TextView(this).apply {
                text = "这门课程暂时没有检测到附件或课件。"
                textSize = 13f
                setPadding(dp(4), dp(20), dp(4), dp(20))
            })
            return
        }

        resources.forEachIndexed { index, resource ->
            val card = MaterialCardView(this).apply {
                radius = dp(22).toFloat()
                cardElevation = 0f
                isClickable = resource.canFetchBody
                isFocusable = resource.canFetchBody
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(15), dp(16), dp(15))
            }
            body.addView(TextView(this).apply {
                text = resource.name
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            body.addView(TextView(this).apply {
                text = buildString {
                    append(resource.extension.uppercase().ifBlank { resource.contentType.ifBlank { "资料" } })
                    if (resource.sourceTitle.isNotBlank() && resource.sourceTitle != resource.name) append(" · 来源：${resource.sourceTitle}")
                    if (resource.size.isNotBlank()) append(" · ${resource.size}")
                    when {
                        !resource.downloadAllowed -> append(" · 平台标记为禁止下载")
                        resource.url.isBlank() -> append(" · 当前没有可访问地址")
                        else -> append(" · 可预览")
                    }
                }
                textSize = 12f
                setPadding(0, dp(5), 0, dp(10))
            })
            val preview = MaterialButton(this).apply {
                text = when {
                    !resource.downloadAllowed -> "正文不可同步"
                    resource.url.isBlank() -> "仅元数据"
                    resource.extension == "pdf" -> "用课堂助手预览 PDF"
                    else -> "下载并打开"
                }
                isAllCaps = false
                minHeight = 0
                insetTop = 0
                insetBottom = 0
                isEnabled = resource.canFetchBody
                setOnClickListener { openResource(resource, this) }
            }
            body.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
            card.addView(body)
            card.setOnClickListener { if (preview.isEnabled) preview.performClick() }
            materialsBody.addView(
                card,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = if (index == 0) dp(10) else dp(8)
                },
            )
        }
    }

    private fun openResource(resource: KetangpaiResource, button: MaterialButton) {
        if (!resource.canFetchBody) return
        lifecycleScope.launch {
            button.isEnabled = false
            setBusy(true, "正在准备《${resource.name}》…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(filesDir, "ketangpai-view/${safeName(course.id)}").apply { mkdirs() }
                    val fileName = safeName(resource.name).ifBlank { safeName(resource.id) + extensionSuffix(resource) }
                    val file = File(dir, fileName)
                    if (!file.isFile || file.length() <= 0L) {
                        val part = File(dir, "$fileName.part")
                        part.delete()
                        check(client.downloadResource(resource, part, MAX_PREVIEW_BYTES)) { "资料下载失败或文件过大" }
                        file.delete()
                        if (!part.renameTo(file)) {
                            part.copyTo(file, overwrite = true)
                            part.delete()
                        }
                    }
                    check(file.isFile && file.length() > 0L) { "资料缓存失败" }
                    file
                }
            }.onSuccess { file ->
                val uri = FileProvider.getUriForFile(this@KetangpaiMaterialsActivity, "$packageName.fileprovider", file)
                val ext = resource.extension
                if (ext == "pdf") {
                    val intent = Intent(this@KetangpaiMaterialsActivity, ReaderActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    setBusy(false, "正在预览 PDF：${resource.name}")
                    startActivity(intent)
                } else {
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext).orEmpty().ifBlank { "application/octet-stream" }
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        startActivity(intent)
                        setBusy(false, "已交给系统应用打开：${resource.name}")
                    } catch (_: ActivityNotFoundException) {
                        setBusy(false, "文件已下载，但系统没有可打开 ${ext.uppercase().ifBlank { "该" }} 格式的应用")
                    }
                }
            }.onFailure {
                setBusy(false, "资料预览失败：${it.message ?: it.javaClass.simpleName}")
            }
            button.isEnabled = resource.canFetchBody
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = busy
        refreshButton.isEnabled = !busy
        statusText.text = message
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(16))
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(outlined("‹").apply {
            textSize = 24f
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(54), dp(50)))
        topBar.addView(TextView(this).apply {
            text = "课堂派课程资料"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(14), dp(2), dp(24))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(TextView(this).apply {
            text = course.name
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        body.addView(TextView(this).apply {
            text = "整门课的课件、资料与附件会平铺显示。PDF 直接进入课堂助手阅读器；其他可访问文件下载后交给系统应用打开。平台明确禁止下载的资料只显示名称和来源，不读取正文。"
            textSize = 13f
            setPadding(0, dp(7), 0, dp(12))
        })

        refreshButton = outlined("刷新全部资料")
        body.addView(refreshButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
        progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        body.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(12) })
        statusText = TextView(this).apply {
            text = "正在准备…"
            textSize = 13f
            setPadding(dp(2), dp(10), dp(2), dp(4))
        }
        body.addView(statusText)
        materialsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(materialsBody)
        return root
    }

    private fun outlined(label: String): MaterialButton = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = label
        isAllCaps = false
        minHeight = 0
        insetTop = 0
        insetBottom = 0
    }

    private fun safeName(raw: String): String = raw.replace(Regex("[^\\p{L}\\p{N}._() -]"), "_").take(120)

    private fun extensionSuffix(resource: KetangpaiResource): String = resource.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val EXTRA_COURSE_ID = "course_id"
        private const val EXTRA_COURSE_NAME = "course_name"
        private const val EXTRA_COURSE_TEACHER = "course_teacher"
        private const val MAX_PREVIEW_BYTES = 150L * 1024L * 1024L

        fun intentFor(context: Context, course: KetangpaiCourse): Intent = Intent(context, KetangpaiMaterialsActivity::class.java)
            .putExtra(EXTRA_COURSE_ID, course.id)
            .putExtra(EXTRA_COURSE_NAME, course.name)
            .putExtra(EXTRA_COURSE_TEACHER, course.teacher)

        private fun courseFromIntent(intent: Intent): KetangpaiCourse? {
            val id = intent.getStringExtra(EXTRA_COURSE_ID).orEmpty()
            val name = intent.getStringExtra(EXTRA_COURSE_NAME).orEmpty()
            if (id.isBlank() || name.isBlank()) return null
            return KetangpaiCourse(id, name, intent.getStringExtra(EXTRA_COURSE_TEACHER).orEmpty())
        }
    }
}
