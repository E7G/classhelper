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
import io.github.paper.classhelper.chaoxing.ChaoxingClient
import io.github.paper.classhelper.chaoxing.ChaoxingCourse
import io.github.paper.classhelper.chaoxing.ChaoxingResourceSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** Account/password login and read-only course-resource synchronization. */
class ChaoxingActivity : AppCompatActivity() {
    private lateinit var app: ClassHelperApp
    private lateinit var client: ChaoxingClient
    private lateinit var accountEdit: TextInputEditText
    private lateinit var passwordEdit: TextInputEditText
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var loginButton: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private lateinit var syncButton: MaterialButton
    private lateinit var logoutButton: MaterialButton
    private lateinit var courseSpinner: Spinner
    private lateinit var courseMetaText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: LinearProgressIndicator
    private var courses: List<ChaoxingCourse> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ClassHelperApp
        client = ChaoxingClient(app.graph.settings)
        setContentView(buildContent())
        renderSavedState()
        wireActions()

        val s = app.graph.settings
        if (s.chaoxingCookie.isNotBlank() || (s.chaoxingUsername.isNotBlank() && s.chaoxingPassword.isNotBlank())) {
            lifecycleScope.launch {
                setBusy(true, "正在恢复学习通登录…")
                val ok = runCatching { client.ensureSession() }.getOrDefault(false)
                if (ok) loadCoursesInternal() else setBusy(false, "保存的登录状态已失效，请重新登录")
            }
        }
    }

    private fun wireActions() {
        loginButton.setOnClickListener {
            val s = app.graph.settings
            val account = accountEdit.text?.toString()?.trim().orEmpty().ifBlank { s.chaoxingUsername }
            val enteredPassword = passwordEdit.text?.toString().orEmpty()
            val password = enteredPassword.ifBlank { s.chaoxingPassword }
            lifecycleScope.launch {
                setBusy(true, "正在登录学习通…")
                runCatching { client.login(account, password, remember = true) }
                    .onSuccess { name ->
                        passwordEdit.setText("")
                        passwordLayout.helperText = "密码已加密保存；Cookie 失效时会自动重新登录。"
                        statusText.text = "已登录${name?.let { " · $it" }.orEmpty()}，正在读取课程…"
                        loadCoursesInternal()
                    }
                    .onFailure { setBusy(false, "登录失败：${it.message ?: it.javaClass.simpleName}") }
            }
        }

        refreshButton.setOnClickListener {
            lifecycleScope.launch {
                setBusy(true, "正在刷新课程列表…")
                loadCoursesInternal()
            }
        }

        syncButton.setOnClickListener {
            val course = courses.getOrNull(courseSpinner.selectedItemPosition)
            if (course == null) {
                statusText.text = "请先登录并选择课程"
                return@setOnClickListener
            }
            lifecycleScope.launch {
                setBusy(true, "准备同步《${displayCourseName(course)}》…")
                val sync = ChaoxingResourceSync(this@ChaoxingActivity, app.graph.db, app.graph.settings)
                runCatching {
                    sync.sync(client, course) { message -> runOnUiThread { statusText.text = message } }
                }.onSuccess { result ->
                    app.graph.knowledge.invalidate(result.documentId)
                    val skipped = if (result.skippedLargeFiles > 0) " · ${result.skippedLargeFiles} 个超大/失败课件仅保留在线地址" else ""
                    setBusy(
                        false,
                        "同步完成 · ${result.chapters} 章 · ${result.resources} 个资源 · ${result.indexedChunks} 个知识片段 · 提取 ${result.extractedPdfPages} 页 PDF$skipped\n课堂问答会优先调用这门课程资源。",
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
            setBusy(false, "已退出学习通并清除保存的账号密码/Cookie")
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
                setBusy(false, "已读取 ${loaded.size} 门课程 · 选择课程后同步到本地知识库")
            }
            .onFailure { setBusy(false, "课程读取失败：${it.message ?: it.javaClass.simpleName}") }
    }

    private fun renderSavedState() {
        val s = app.graph.settings
        if (accountEdit.text.isNullOrBlank() && s.chaoxingUsername.isNotBlank()) accountEdit.setText(s.chaoxingUsername)
        passwordLayout.helperText = if (s.chaoxingPassword.isNotBlank()) {
            "已保存加密密码；密码框留空即可继续使用。Cookie 失效时自动重新登录。"
        } else {
            "登录成功后使用 AndroidKeyStore + AES-GCM 加密保存，不会明文落盘。"
        }
        if (s.chaoxingCourseName.isNotBlank()) {
            val syncTime = s.chaoxingLastSync.takeIf { it > 0 }?.let {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))
            }.orEmpty()
            val savedName = sanitizeCourseName(s.chaoxingCourseName)
            statusText.text = "当前课程资源：$savedName" + if (syncTime.isNotBlank()) "\n上次同步 $syncTime" else ""
        }
    }

    private fun renderCourseSpinner() {
        val labels = if (courses.isEmpty()) listOf("暂无课程") else courses.map(::displayCourseName)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        courseSpinner.adapter = adapter
        syncButton.isEnabled = courses.isNotEmpty()
        refreshButton.isEnabled = app.graph.settings.chaoxingUsername.isNotBlank() || app.graph.settings.chaoxingCookie.isNotBlank()
        val selected = courses.indexOfFirst {
            it.courseId == app.graph.settings.chaoxingCourseId && it.classId == app.graph.settings.chaoxingClassId
        }
        if (selected >= 0) courseSpinner.setSelection(selected) else renderCourseMeta(courses.firstOrNull())
    }

    private fun displayCourseName(course: ChaoxingCourse): String = sanitizeCourseName(course.name)

    private fun sanitizeCourseName(raw: String): String {
        val name = raw.trim()
        if (name.isBlank()) return "未命名课程"
        if (name.matches(Regex("^课程\\s*\\d+$"))) return "未命名课程"
        if (name.matches(Regex("^\\d+$"))) return "未命名课程"
        return name
    }

    private fun renderCourseMeta(course: ChaoxingCourse?) {
        courseMetaText.text = when {
            course == null -> "登录后会显示你当前账号可访问的课程。"
            course.teacher.isNotBlank() -> "教师：${course.teacher}"
            else -> "选择后可同步章节、课件和可读取的 PDF 文本。"
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = busy
        loginButton.isEnabled = !busy
        refreshButton.isEnabled = !busy && (app.graph.settings.chaoxingUsername.isNotBlank() || app.graph.settings.chaoxingCookie.isNotBlank())
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
            text = "学习通课程资源"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar, matchParams())

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(16), dp(2), dp(28))
        }
        scroll.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        content.addView(TextView(this).apply {
            text = "把学习通课程直接接进课堂助手"
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "登录自己的账号，选择课程后同步章节和课件。只读取当前账号正常可访问的资源，不刷任务、不提交学习进度。"
            textSize = 13f
            setLineSpacing(0f, 1.18f)
            setPadding(0, dp(8), 0, dp(4))
        })

        val loginCardContent = sectionCard(
            title = "账号登录",
            subtitle = "账号会记住；密码和 Cookie 使用 AndroidKeyStore 加密保存。",
        )
        content.addView(loginCardContent.first, matchParams(top = 16))
        val loginBody = loginCardContent.second

        val accountLayout = TextInputLayout(this).apply {
            hint = "学习通账号 / 手机号"
        }
        accountEdit = TextInputEditText(accountLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        accountLayout.addView(accountEdit)
        loginBody.addView(accountLayout, matchParams(top = 14))

        passwordLayout = TextInputLayout(this).apply {
            hint = "学习通密码"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        passwordEdit = TextInputEditText(passwordLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        passwordLayout.addView(passwordEdit)
        loginBody.addView(passwordLayout, matchParams(top = 10))

        loginButton = primaryButton("登录并记住密码")
        loginBody.addView(loginButton, matchParams(top = 14, height = 54))

        val courseCard = sectionCard(
            title = "选择课程",
            subtitle = "这里只显示课程名称；课程 ID 和班级 ID 仅在内部用于访问资源。",
        )
        content.addView(courseCard.first, matchParams(top = 14))
        val courseBody = courseCard.second

        courseSpinner = Spinner(this).apply {
            minimumHeight = dp(56)
            setPadding(dp(12), 0, dp(12), 0)
        }
        courseBody.addView(courseSpinner, matchParams(top = 14, height = 56))

        courseMetaText = TextView(this).apply {
            text = "登录后会显示你当前账号可访问的课程。"
            textSize = 12f
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        courseBody.addView(courseMetaText, matchParams())

        refreshButton = outlinedButton("刷新课程列表")
        courseBody.addView(refreshButton, matchParams(top = 14, height = 52))

        syncButton = primaryButton("同步到课堂知识库")
        courseBody.addView(syncButton, matchParams(top = 10, height = 56))

        val statusCard = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
        }
        val statusBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        statusCard.addView(statusBody)
        content.addView(statusCard, matchParams(top = 14))

        statusBody.addView(TextView(this).apply {
            text = "状态"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        statusBody.addView(progress, matchParams(top = 12, height = 4))
        statusText = TextView(this).apply {
            text = "尚未登录"
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(12), 0, 0)
        }
        statusBody.addView(statusText, matchParams())

        logoutButton = outlinedButton("退出学习通并清除已保存登录信息")
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
        }, matchParams())
        body.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(5), 0, 0)
        }, matchParams())
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

    private fun outlinedButton(label: String): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            minHeight = 0
            insetTop = 0
            insetBottom = 0
            isAllCaps = false
        }

    /** All positive custom heights are dp, never raw px. This fixes flattened MaterialButtons on high-DPI tablets. */
    private fun matchParams(top: Int = 0, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams {
        val resolvedHeight = when (height) {
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT -> height
            else -> dp(height)
        }
        return LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resolvedHeight).apply {
            topMargin = dp(top)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
