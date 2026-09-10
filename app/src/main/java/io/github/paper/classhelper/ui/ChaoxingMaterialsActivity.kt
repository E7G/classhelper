package io.github.paper.classhelper.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import io.github.paper.classhelper.chaoxing.ChaoxingClient
import io.github.paper.classhelper.chaoxing.ChaoxingCourse
import io.github.paper.classhelper.chaoxing.ChaoxingMaterial
import io.github.paper.classhelper.chaoxing.ChaoxingMaterialRepository
import kotlinx.coroutines.launch

/** Browse all authorized Chaoxing course materials as one flat preview list. */
class ChaoxingMaterialsActivity : AppCompatActivity() {
    private lateinit var app: ClassHelperApp
    private lateinit var client: ChaoxingClient
    private lateinit var repository: ChaoxingMaterialRepository
    private lateinit var course: ChaoxingCourse
    private lateinit var materialsBody: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var refreshButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ClassHelperApp
        client = ChaoxingClient(app.graph.settings)
        repository = ChaoxingMaterialRepository(this, app.graph.settings)
        course = courseFromIntent(intent) ?: run {
            finish()
            return
        }
        setContentView(buildContent())
        refreshButton.setOnClickListener { lifecycleScope.launch { loadAllMaterials() } }
        lifecycleScope.launch { loadAllMaterials() }
    }

    private suspend fun loadAllMaterials() {
        setBusy(true, "正在汇总《${course.name}》全部资料…")
        materialsBody.removeAllViews()
        runCatching {
            repository.listCourseMaterials(client, course) { done, total, chapterTitle ->
                runOnUiThread {
                    statusText.text = if (total > 0) {
                        "正在汇总资料 $done/$total · $chapterTitle"
                    } else {
                        "正在读取课程资料…"
                    }
                }
            }
        }.onSuccess { materials ->
            renderMaterials(materials)
            setBusy(
                false,
                if (materials.isEmpty()) "这门课程暂时没有检测到可预览资料" else "共 ${materials.size} 个资料 · 点击卡片或“预览”打开",
            )
        }.onFailure {
            materialsBody.removeAllViews()
            setBusy(false, "课程资料读取失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun renderMaterials(materials: List<ChaoxingMaterial>) {
        materialsBody.removeAllViews()
        if (materials.isEmpty()) {
            materialsBody.addView(TextView(this).apply {
                text = "这门课程暂时没有检测到附件。"
                textSize = 13f
                setPadding(dp(4), dp(20), dp(4), dp(20))
            })
            return
        }

        materials.forEachIndexed { index, material ->
            val card = MaterialCardView(this).apply {
                radius = dp(22).toFloat()
                cardElevation = 0f
                isClickable = true
                isFocusable = true
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(15), dp(16), dp(15))
            }
            body.addView(TextView(this).apply {
                text = material.name
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            body.addView(TextView(this).apply {
                text = buildString {
                    append(material.type.ifBlank { "资料" })
                    if (material.chapterTitle.isNotBlank()) append(" · 来源：${material.chapterTitle}")
                    if (!material.objectId.isNullOrBlank()) append(" · 可解析")
                    else if (!material.directUrl.isNullOrBlank()) append(" · 在线资源")
                    else append(" · 暂无直达地址")
                }
                textSize = 12f
                setPadding(0, dp(5), 0, dp(10))
            })
            val preview = MaterialButton(this).apply {
                text = "预览"
                isAllCaps = false
                minHeight = 0
                insetTop = 0
                insetBottom = 0
                isEnabled = material.objectId != null || material.directUrl != null
                setOnClickListener { openMaterial(material, this) }
            }
            body.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
            card.addView(body)
            card.setOnClickListener {
                if (preview.isEnabled) preview.performClick()
            }
            materialsBody.addView(
                card,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = if (index == 0) dp(10) else dp(8)
                },
            )
        }
    }

    private fun openMaterial(material: ChaoxingMaterial, button: MaterialButton) {
        lifecycleScope.launch {
            button.isEnabled = false
            setBusy(true, "正在准备预览《${material.name}》…")
            runCatching { repository.resolve(client, material) }
                .onSuccess { resolved ->
                    val pdfUrl = resolved.pdfUrl
                    if (!pdfUrl.isNullOrBlank()) {
                        setBusy(true, "正在准备 PDF 预览：${material.name}")
                        runCatching {
                            repository.cachePdf(course, material, pdfUrl) { downloaded, total ->
                                runOnUiThread {
                                    val doneMb = downloaded / 1024f / 1024f
                                    statusText.text = if (total != null && total > 0) {
                                        val totalMb = total / 1024f / 1024f
                                        "正在下载预览 · %.1f / %.1f MB".format(doneMb, totalMb)
                                    } else {
                                        "正在下载预览 · %.1f MB".format(doneMb)
                                    }
                                }
                            }
                        }.onSuccess { file ->
                            val uri = FileProvider.getUriForFile(this@ChaoxingMaterialsActivity, "$packageName.fileprovider", file)
                            val intent = Intent(this@ChaoxingMaterialsActivity, ReaderActivity::class.java)
                                .setAction(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/pdf")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            setBusy(false, "正在预览 PDF：${material.name}")
                            startActivity(intent)
                        }.onFailure {
                            setBusy(false, "PDF 预览失败：${it.message ?: it.javaClass.simpleName}")
                        }
                    } else {
                        val url = resolved.sourceUrl ?: material.directUrl
                        if (url.isNullOrBlank()) {
                            setBusy(false, "这个资料没有可预览地址")
                        } else {
                            setBusy(false, "正在打开预览：${material.name}")
                            startActivity(ChaoxingWebActivity.intentFor(this@ChaoxingMaterialsActivity, url, material.name))
                        }
                    }
                }
                .onFailure { setBusy(false, "资料预览解析失败：${it.message ?: it.javaClass.simpleName}") }
            button.isEnabled = material.objectId != null || material.directUrl != null
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
            text = "课程资料"
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
            text = "整门课程的资料会汇总成一个列表，不再按章节筛选。直接点击资料卡片或“预览”；PDF 用课堂助手阅读器打开，其他在线资料交给系统浏览器/对应应用。"
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val EXTRA_COURSE_ID = "course_id"
        private const val EXTRA_CLASS_ID = "class_id"
        private const val EXTRA_CPI = "cpi"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TEACHER = "teacher"
        private const val EXTRA_CLASSROOM = "classroom"
        private const val EXTRA_URL = "url"

        fun intentFor(context: Context, course: ChaoxingCourse): Intent = Intent(context, ChaoxingMaterialsActivity::class.java)
            .putExtra(EXTRA_COURSE_ID, course.courseId)
            .putExtra(EXTRA_CLASS_ID, course.classId)
            .putExtra(EXTRA_CPI, course.cpi)
            .putExtra(EXTRA_NAME, course.name)
            .putExtra(EXTRA_TEACHER, course.teacher)
            .putExtra(EXTRA_CLASSROOM, course.classroom)
            .putExtra(EXTRA_URL, course.url)

        private fun courseFromIntent(intent: Intent): ChaoxingCourse? {
            val courseId = intent.getStringExtra(EXTRA_COURSE_ID).orEmpty()
            val classId = intent.getStringExtra(EXTRA_CLASS_ID).orEmpty()
            if (courseId.isBlank() || classId.isBlank()) return null
            return ChaoxingCourse(
                courseId = courseId,
                classId = classId,
                cpi = intent.getStringExtra(EXTRA_CPI).orEmpty(),
                name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "学习通课程" },
                teacher = intent.getStringExtra(EXTRA_TEACHER).orEmpty(),
                classroom = intent.getStringExtra(EXTRA_CLASSROOM).orEmpty(),
                url = intent.getStringExtra(EXTRA_URL).orEmpty(),
            )
        }
    }
}
