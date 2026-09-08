package io.github.paper.classhelper.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
                        passwordLayout.helperText = "密码已使用 AndroidKeyStore 加密保存；Cookie 失效时会自动重新登录。"
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
                setBusy(true, "准备同步《${course.name}》…")
                val sync = ChaoxingResourceSync(this@ChaoxingActivity, app.graph.db, app.graph.settings)
                runCatching {
                    sync.sync(client, course) { message -> runOnUiThread { statusText.text = message } }
                }.onSuccess { result ->
                    app.graph.knowledge.invalidate(result.documentId)
                    val skipped = if (result.skippedLargeFiles > 0) " · ${result.skippedLargeFiles} 个超大/失败课件仅保留在线地址" else ""
                    setBusy(
                        false,
                        "同步完成 · ${result.chapters} 章 · ${result.resources} 个资源 · ${result.indexedChunks} 个知识片段 · 提取 ${result.extractedPdfPages} 页 PDF$skipped\n" +
                            "课堂问答会优先调用这门学习通课程资源。",
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
    }

    private suspend fun loadCoursesInternal() {
        runCatching { client.listCourses() }
            .onSuccess { loaded ->
                courses = loaded
                renderCourseSpinner()
                setBusy(false, "已读取 ${loaded.size} 门课程 · 选择当前这门课后点“同步并作为课程资源”")
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
            statusText.text = "当前课程资源：${s.chaoxingCourseName}" + if (syncTime.isNotBlank()) " · 上次同步 $syncTime" else ""
        }
    }

    private fun renderCourseSpinner() {
        val labels = if (courses.isEmpty()) listOf("暂无课程") else courses.map { course ->
            buildString {
                append(course.name)
                if (course.teacher.isNotBlank()) append(" · ").append(course.teacher)
                if (course.classroom.isNotBlank()) append(" · ").append(course.classroom)
            }
        }
        courseSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        syncButton.isEnabled = courses.isNotEmpty()
        refreshButton.isEnabled = app.graph.settings.chaoxingUsername.isNotBlank() || app.graph.settings.chaoxingCookie.isNotBlank()
        val selected = courses.indexOfFirst {
            it.courseId == app.graph.settings.chaoxingCourseId && it.classId == app.graph.settings.chaoxingClassId
        }
        if (selected >= 0) courseSpinner.setSelection(selected)
    }

    private fun setBusy(busy: Boolean, message: String) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = busy
        loginButton.isEnabled = !busy
        refreshButton.isEnabled = !busy && (app.graph.settings.chaoxingUsername.isNotBlank() || app.graph.settings.chaoxingCookie.isNotBlank())
        syncButton.isEnabled = !busy && courses.isNotEmpty()
        logoutButton.isEnabled = !busy
        statusText.text = message
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(MaterialButton(this@ChaoxingActivity).apply {
                text = "‹"
                textSize = 24f
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(56), dp(50)))
            addView(TextView(this@ChaoxingActivity).apply {
                text = "学习通课程资源"
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(24))
        }
        scroll.addView(content, ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        content.addView(TextView(this).apply {
            text = "直接使用你自己的学习通课程资料"
            textSize = 27f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "登录后选择当前课程。章节文本和可读取 PDF 课件会进入本地知识库，课堂问答可直接调用；视频/音频保留正常授权资源地址。不会刷任务、提交进度或绕过课程权限。"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(18))
        })

        val accountLayout = TextInputLayout(this).apply { hint = "学习通账号 / 手机号" }
        accountEdit = TextInputEditText(accountLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            singleLine = true
        }
        accountLayout.addView(accountEdit)
        content.addView(accountLayout, matchParams())

        passwordLayout = TextInputLayout(this).apply {
            hint = "学习通密码"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        passwordEdit = TextInputEditText(passwordLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            singleLine = true
        }
        passwordLayout.addView(passwordEdit)
        content.addView(passwordLayout, matchParams(top = 10))

        loginButton = MaterialButton(this).apply { text = "登录并记住密码" }
        content.addView(loginButton, matchParams(top = 14, height = 52))

        courseSpinner = Spinner(this)
        content.addView(TextView(this).apply {
            text = "当前课程"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, matchParams(top = 24))
        content.addView(courseSpinner, matchParams(top = 6, height = 52))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        refreshButton = MaterialButton(this).apply { text = "刷新课程" }
        syncButton = MaterialButton(this).apply { text = "同步并作为课程资源" }
        actions.addView(refreshButton, LinearLayout.LayoutParams(0, dp(52), 0.38f).apply { marginEnd = dp(6) })
        actions.addView(syncButton, LinearLayout.LayoutParams(0, dp(52), 0.62f).apply { marginStart = dp(6) })
        content.addView(actions, matchParams(top = 12))

        progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        content.addView(progress, matchParams(top = 16, height = 4))

        statusText = TextView(this).apply {
            text = "尚未登录"
            textSize = 13f
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(statusText, matchParams())

        logoutButton = MaterialButton(this).apply { text = "退出并清除保存的账号密码" }
        content.addView(logoutButton, matchParams(top = 18, height = 50))

        renderCourseSpinner()
        return root
    }

    private fun matchParams(top: Int = 0, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
