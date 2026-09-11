package io.github.paper.classhelper.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.ketangpai.KetangpaiClient
import io.github.paper.classhelper.ketangpai.KetangpaiCourse
import io.github.paper.classhelper.ketangpai.KetangpaiResourceSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** Account login, resource browsing and read-only KETANGPAI course-resource synchronization. */
class KetangpaiActivity : AppCompatActivity() {
    private lateinit var app: ClassHelperApp
    private lateinit var client: KetangpaiClient
    private lateinit var accountEdit: TextInputEditText
    private lateinit var passwordEdit: TextInputEditText
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var loginButton: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private lateinit var browseButton: MaterialButton
    private lateinit var syncButton: MaterialButton
    private lateinit var logoutButton: MaterialButton
    private lateinit var courseSpinner: Spinner
    private lateinit var courseMetaText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: LinearProgressIndicator
    private var courses: List<KetangpaiCourse> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ClassHelperApp
        client = KetangpaiClient(app.graph.settings)
        setContentView(buildContent())
        renderSavedState()
        wireActions()

        val s = app.graph.settings
        if (s.ketangpaiToken.isNotBlank() || (s.ketangpaiAccount.isNotBlank() && s.ketangpaiPassword.isNotBlank())) {
            lifecycleScope.launch {
                setBusy(true, "正在恢复课堂派登录…")
                val ok = runCatching { client.ensureSession() }.getOrDefault(false)
                if (ok) loadCoursesInternal() else setBusy(false, "保存的登录状态已失效，请重新登录")
            }
        }
    }

    private fun wireActions() {
        loginButton.setOnClickListener {
            val s = app.graph.settings
            val account = accountEdit.text?.toString()?.trim().orEmpty().ifBlank { s.ketangpaiAccount }
            val password = passwordEdit.text?.toString().orEmpty().ifBlank { s.ketangpaiPassword }
            lifecycleScope.launch {
                setBusy(true, "正在登录课堂派…")
                runCatching { client.login(account, password, remember = true) }
                    .onSuccess { name ->
                        passwordEdit.setText("")
                        passwordLayout.helperText = "密码与 Token 已使用 AndroidKeyStore 加密保存；Token 失效时自动重新登录。"
                        statusText.text = "已登录${name?.let { " · $it" }.orEmpty()}，正在读取课程…"
                        loadCoursesInternal()
                    }
                    .onFailure { setBusy(false, "登录失败：${it.message ?: it.javaClass.simpleName}") }
            }
        }

        refreshButton.setOnClickListener {
            lifecycleScope.launch {
                setBusy(true, "正在刷新课堂派课程…")
                loadCoursesInternal()
            }
        }

        browseButton.setOnClickListener {
            val course = selectedCourse()
            if (course == null) {
                statusText.text = "请先登录并选择课程"
            } else {
                startActivity(KetangpaiMaterialsActivity.intentFor(this, course))
            }
        }

        syncButton.setOnClickListener {
            val course = selectedCourse()
            if (course == null) {
                statusText.text = "请先登录并选择课程"
                return@setOnClickListener
            }
            lifecycleScope.launch {
                setBusy(true, "准备同步《${course.name}》…")
                val sync = KetangpaiResourceSync(this@KetangpaiActivity, app.graph.db, app.graph.settings)
                runCatching {
                    sync.sync(client, course) { message -> runOnUiThread { statusText.text = message } }
                }.onSuccess { result ->
                    app.graph.knowledge.invalidate(result.documentId)
                    val details = buildString {
                        if (result.reusedCachedFiles > 0) append(" · 复用缓存 ${result.reusedCachedFiles}")
                        if (result.skippedRestrictedFiles > 0) append(" · 权限限制 ${result.skippedRestrictedFiles}")
                        if (result.failedFiles > 0) append(" · 提取失败 ${result.failedFiles}")
                    }
                    setBusy(
                        false,
                        "同步完成 · ${result.resources} 个资料 · ${result.indexedChunks} 个知识片段 · PDF ${result.extractedPdfPages} 页 · Office ${result.importedOfficeSections} 段$details\n课堂问答会优先使用当前 PDF，其次使用这门课堂派课程资料。未变化资料下次同步会复用本地缓存。",
                    )
                    renderSavedState()
                }.onFailure {
                    setBusy(false, "同步失败：${it.message ?: it.javaClass.simpleName}")
                }
            }
        }

        logoutButton.setOnClickListener {
            client.logout(clearCredentials = true)
            accountEdit.setText("")
            passwordEdit.setText("")
            passwordLayout.helperText = "登录成功后密码会加密保存，退出账号时清除。"
            courses = emptyList()
            renderCourseSpinner()
            setBusy(false, "已退出课堂派并清除保存的账号密码/Token")
        }

        courseSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderCourseMeta(courses.getOrNull(position))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = renderCourseMeta(null)
        }
    }

    private suspend fun loadCoursesInternal() {
        runCatching { client.listCourses() }
            .onSuccess { loaded ->
                courses = loaded
                renderCourseSpinner()
                setBusy(false, "已读取 ${loaded.size} 门课程 · 可先浏览资料，再同步到本地课堂知识库")
            }
            .onFailure { setBusy(false, "课程读取失败：${it.message ?: it.javaClass.simpleName}") }
    }

    private fun selectedCourse(): KetangpaiCourse? = courses.getOrNull(courseSpinner.selectedItemPosition)

    private fun renderSavedState() {
        val s = app.graph.settings
        if (accountEdit.text.isNullOrBlank() && s.ketangpaiAccount.isNotBlank()) accountEdit.setText(s.ketangpaiAccount)
        passwordLayout.helperText = if (s.ketangpaiPassword.isNotBlank()) {
            "已保存加密密码；密码框留空即可继续使用。Token 失效时自动重新登录。"
        } else {
            "登录成功后通过 AndroidKeyStore + AES-GCM 加密保存，不会明文落盘。"
        }
        if (s.ketangpaiCourseName.isNotBlank()) {
            val syncTime = s.ketangpaiLastSync.takeIf { it > 0 }?.let {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))
            }.orEmpty()
            statusText.text = "当前课堂派课程：${s.ketangpaiCourseName}" + if (syncTime.isNotBlank()) "\n上次同步 $syncTime" else ""
        }
    }

    private fun renderCourseSpinner() {
        val labels = if (courses.isEmpty()) listOf("暂无课程") else courses.map { it.name.ifBlank { "未命名课程" } }
        courseSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val enabled = courses.isNotEmpty()
        courseSpinner.isEnabled = enabled
        browseButton.isEnabled = enabled
        syncButton.isEnabled = enabled
        refreshButton.isEnabled = app.graph.settings.ketangpaiToken.isNotBlank() || app.graph.settings.ketangpaiAccount.isNotBlank()
        val selected = courses.indexOfFirst { it.id == app.graph.settings.ketangpaiCourseId }
        if (selected >= 0) courseSpinner.setSelection(selected) else renderCourseMeta(courses.firstOrNull())
    }

    private fun renderCourseMeta(course: KetangpaiCourse?) {
        courseMetaText.text = when {
            course == null -> "登录后会显示当前账号可访问的课堂派课程。"
            course.teacher.isNotBlank() -> "教师：${course.teacher} · 可以浏览课件/附件，或同步可访问正文到本地知识库。"
            else -> "可以先浏览整门课的课件/附件，再同步可访问正文到本地知识库。"
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = busy
        loginButton.isEnabled = !busy
        refreshButton.isEnabled = !busy && (app.graph.settings.ketangpaiToken.isNotBlank() || app.graph.settings.ketangpaiAccount.isNotBlank())
        browseButton.isEnabled = !busy && courses.isNotEmpty()
        syncButton.isEnabled = !busy && courses.isNotEmpty()
        logoutButton.isEnabled = !busy
        courseSpinner.isEnabled = !busy && courses.isNotEmpty()
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
        topBar.addView(outlinedButton("‹").apply {
            textSize = 24f
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(54), dp(50)))
        topBar.addView(TextView(this).apply {
            text = "课堂派课程资源"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(16), dp(2), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        content.addView(TextView(this).apply {
            text = "课堂派资料同步"
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "登录自己的课堂派账号后，可先浏览整门课程的资料，再把可访问的 PDF、DOCX、PPTX 和文本正文同步进课堂知识库。同步会缓存未变化文件，减少重复下载；当前打开的 PDF 仍保持最高检索优先级。"
            textSize = 13f
            setLineSpacing(0f, 1.18f)
            setPadding(0, dp(8), 0, dp(4))
        })

        val loginCard = sectionCard("账号登录", "账号会记住；密码和 Token 使用 AndroidKeyStore 加密保存。")
        content.addView(loginCard.first, matchParams(top = 16))
        val accountLayout = TextInputLayout(this).apply { hint = "课堂派手机号 / 邮箱" }
        accountEdit = TextInputEditText(accountLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        accountLayout.addView(accountEdit)
        loginCard.second.addView(accountLayout, matchParams(top = 14))

        passwordLayout = TextInputLayout(this).apply {
            hint = "课堂派密码"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        passwordEdit = TextInputEditText(passwordLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        passwordLayout.addView(passwordEdit)
        loginCard.second.addView(passwordLayout, matchParams(top = 10))
        loginButton = primaryButton("登录并记住密码")
        loginCard.second.addView(loginButton, matchParams(top = 14, height = 54))

        val courseCard = sectionCard("选择课程", "课程列表只显示课程名称；内部 ID 不展示。")
        content.addView(courseCard.first, matchParams(top = 14))
        courseSpinner = Spinner(this).apply {
            minimumHeight = dp(56)
            setPadding(dp(12), 0, dp(12), 0)
        }
        courseCard.second.addView(courseSpinner, matchParams(top = 14, height = 56))
        courseMetaText = TextView(this).apply {
            text = "登录后会显示当前账号可访问的课堂派课程。"
            textSize = 12f
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        courseCard.second.addView(courseMetaText)
        refreshButton = outlinedButton("刷新课程列表")
        courseCard.second.addView(refreshButton, matchParams(top = 14, height = 52))
        browseButton = outlinedButton("浏览这门课的全部资料")
        courseCard.second.addView(browseButton, matchParams(top = 10, height = 52))
        syncButton = primaryButton("增量同步课程资料")
        courseCard.second.addView(syncButton, matchParams(top = 10, height = 56))

        val statusCard = sectionCard("状态", "同步只读取资料，不签到、不刷课、不提交作业。平台明确禁止下载的资料仅保留元数据。")
        content.addView(statusCard.first, matchParams(top = 14))
        progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        statusCard.second.addView(progress, matchParams(top = 12, height = 4))
        statusText = TextView(this).apply {
            text = "尚未登录"
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(12), 0, 0)
        }
        statusCard.second.addView(statusText)

        logoutButton = outlinedButton("退出课堂派并清除已保存登录信息")
        content.addView(logoutButton, matchParams(top = 14, height = 52))
        renderCourseSpinner()
        return root
    }

    private fun sectionCard(title: String, subtitle: String): Pair<MaterialCardView, LinearLayout> {
        val card = MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = 0f
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        body.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        body.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(5), 0, 0)
        })
        card.addView(body)
        return card to body
    }

    private fun primaryButton(label: String): MaterialButton = MaterialButton(this).apply {
        text = label
        minHeight = 0
        insetTop = 0
        insetBottom = 0
        isAllCaps = false
    }

    private fun outlinedButton(label: String): MaterialButton = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = label
        minHeight = 0
        insetTop = 0
        insetBottom = 0
        isAllCaps = false
    }

    private fun matchParams(top: Int = 0, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
