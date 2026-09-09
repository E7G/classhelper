package io.github.paper.classhelper.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.course.CourseResourceRow
import io.github.paper.classhelper.course.CourseRow
import io.github.paper.classhelper.data.DocumentRow
import io.github.paper.classhelper.knowledge.PdfTextIndexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Course-first launcher. A course may exist without a PDF and may contain many PDF resources.
 * ReaderActivity remains a document reader; this screen owns course selection and resource entry.
 */
class CourseHubActivity : AppCompatActivity() {
    private lateinit var app: ClassHelperApp
    private lateinit var courseSpinner: Spinner
    private lateinit var resourceSpinner: Spinner
    private lateinit var courseMeta: TextView
    private lateinit var resourceMeta: TextView
    private lateinit var status: TextView
    private lateinit var enterCourseButton: MaterialButton
    private lateinit var openResourceButton: MaterialButton
    private lateinit var addPdfButton: MaterialButton

    private var courses: List<CourseRow> = emptyList()
    private var courseResources: List<CourseResourceRow> = emptyList()

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val course = selectedCourse() ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        lifecycleScope.launch { importLocalPdf(course, uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ClassHelperApp
        setContentView(buildContent())
        loadCourses()
    }

    override fun onResume() {
        super.onResume()
        if (::courseSpinner.isInitialized) loadCourses(keepSelection = true)
    }

    private fun loadCourses(keepSelection: Boolean = false) {
        val oldId = if (keepSelection) selectedCourse()?.id else app.graph.settings.currentCourseId
        courses = app.graph.courseCatalog.listCourses()
        val labels = if (courses.isEmpty()) listOf("暂无课程") else courses.map { it.name }
        courseSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val selected = courses.indexOfFirst { it.id == oldId }.takeIf { it >= 0 } ?: 0
        if (courses.isNotEmpty()) courseSpinner.setSelection(selected)
        renderCourse(courses.getOrNull(selected))
        status.text = if (courses.isEmpty()) {
            "还没有课程。可以新建本地课程，或者从学习通同步课程。"
        } else {
            "课程和 PDF 已解耦：先进入课程，需要时再打开其中的课件。"
        }
    }

    private fun renderCourse(course: CourseRow?) {
        courseResources = course?.let { app.graph.courseCatalog.listResources(it.id) }.orEmpty()
        courseMeta.text = when (course?.source) {
            "chaoxing" -> "学习通课程 · ${courseResources.size} 个已发现资源"
            "local" -> "本地自定义课程 · ${courseResources.size} 份资料"
            else -> "课程可以没有 PDF，也可以挂多份资料。"
        }
        val labels = if (courseResources.isEmpty()) listOf("暂无 PDF 资料") else courseResources.map {
            val type = if (it.documentId != null && it.localPath.isNotBlank()) "PDF" else it.kind.uppercase()
            "$type · ${it.title}"
        }
        resourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        resourceSpinner.isEnabled = courseResources.isNotEmpty()
        openResourceButton.isEnabled = courseResources.any { it.documentId != null && it.localPath.isNotBlank() }
        enterCourseButton.isEnabled = course != null
        addPdfButton.isEnabled = course?.source == "local"
        renderResource(courseResources.firstOrNull())
    }

    private fun renderResource(resource: CourseResourceRow?) {
        resourceMeta.text = when {
            resource == null -> "本课程暂时没有 PDF。仍然可以直接进入课程开始听课。"
            resource.documentId != null && resource.localPath.isNotBlank() -> "已缓存到本地，可直接用内置 PDF 阅读器打开。"
            resource.remoteUrl.isNotBlank() -> "在线资源已记录；当前没有可离线打开的 PDF 副本。"
            else -> "课程资源"
        }
    }

    private fun selectedCourse(): CourseRow? = courses.getOrNull(courseSpinner.selectedItemPosition)
    private fun selectedResource(): CourseResourceRow? = courseResources.getOrNull(resourceSpinner.selectedItemPosition)

    private fun activateCourse(course: CourseRow, clearDocument: Boolean) {
        val s = app.graph.settings
        s.currentCourseId = course.id
        s.currentCourseName = course.name
        s.currentCourseKnowledgeDocumentId = course.knowledgeDocumentId
        if (course.source == "chaoxing") {
            s.chaoxingCourseName = course.name
            s.chaoxingCourseDocumentId = course.knowledgeDocumentId
        }
        if (clearDocument) {
            s.currentDocumentId = null
            s.currentPage = 0
        }
    }

    private fun enterCourse() {
        val course = selectedCourse() ?: return
        activateCourse(course, clearDocument = true)
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(EXTRA_COURSE_ONLY, true))
    }

    private fun openSelectedResource() {
        val course = selectedCourse() ?: return
        val resource = selectedResource() ?: return
        val documentId = resource.documentId
        if (documentId.isNullOrBlank() || resource.localPath.isBlank()) {
            status.text = "这个资源目前没有可直接打开的 PDF 副本。重新同步学习通后再试。"
            return
        }
        activateCourse(course, clearDocument = false)
        app.graph.settings.currentDocumentId = documentId
        app.graph.settings.currentPage = 0
        startActivity(Intent(this, ReaderActivity::class.java))
    }

    private fun promptCreateCourse() {
        val edit = TextInputEditText(this).apply {
            hint = "例如：操作系统原理"
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("新建本地课程")
            .setMessage("课程不需要绑定教材。创建后可以直接听课，也可以再添加多份 PDF。")
            .setView(edit)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val name = edit.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@setPositiveButton
                val row = app.graph.courseCatalog.createLocal(name)
                ensureLocalKnowledgeDocument(row)
                app.graph.settings.currentCourseId = row.id
                app.graph.settings.currentCourseName = row.name
                app.graph.settings.currentCourseKnowledgeDocumentId = row.knowledgeDocumentId
                loadCourses()
                val index = courses.indexOfFirst { it.id == row.id }
                if (index >= 0) courseSpinner.setSelection(index)
                status.text = "已创建《${row.name}》。现在可以直接进入课程，或者添加 PDF。"
            }
            .show()
    }

    private fun ensureLocalKnowledgeDocument(course: CourseRow) {
        if (app.graph.db.getDocument(course.knowledgeDocumentId) != null) return
        app.graph.db.upsertDocument(
            DocumentRow(
                id = course.knowledgeDocumentId,
                sourceUri = "course://${course.id}",
                workingPath = "",
                title = "课程 · ${course.name}",
                dirty = false,
                kind = "course",
                indexedAt = 0L,
            ),
        )
    }

    private suspend fun importLocalPdf(course: CourseRow, uri: Uri) {
        status.text = "正在导入 PDF…"
        addPdfButton.isEnabled = false
        val result = withContext(Dispatchers.IO) {
            runCatching {
                ensureLocalKnowledgeDocument(course)
                val ws = app.graph.workspace.open(uri)
                val indexer = PdfTextIndexer(
                    this@CourseHubActivity,
                    app.graph.db,
                    app.graph.settings,
                    app.graph.ocrModels,
                )
                indexer.index(ws, force = false)
                app.graph.courseCatalog.upsertResource(
                    courseId = course.id,
                    title = ws.title,
                    kind = "pdf",
                    documentId = ws.id,
                    localPath = ws.workingFile.absolutePath,
                    remoteUrl = uri.toString(),
                    sourceKey = ws.id,
                )
                rebuildLocalCourseKnowledge(course)
                ws.title
            }
        }
        addPdfButton.isEnabled = true
        result.onSuccess { title ->
            app.graph.knowledge.invalidateAll()
            renderCourse(course)
            status.text = "已把《$title》加入课程《${course.name}》。"
        }.onFailure {
            status.text = "导入 PDF 失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    /** Rebuild one local course aggregate entirely inside SQLite; no full PDF corpus is loaded into Java heap. */
    private fun rebuildLocalCourseKnowledge(course: CourseRow) {
        val database = app.graph.db.writableDatabase
        val docs = app.graph.courseCatalog.listResources(course.id).mapNotNull { it.documentId }.distinct()
        database.beginTransaction()
        try {
            database.delete("document_chunks", "document_id=?", arrayOf(course.knowledgeDocumentId))
            docs.forEachIndexed { index, documentId ->
                database.execSQL(
                    "INSERT INTO document_chunks(document_id,page,title,text) " +
                        "SELECT ?,page + ?,title,text FROM document_chunks WHERE document_id=? ORDER BY page",
                    arrayOf(course.knowledgeDocumentId, index * COURSE_PAGE_STRIDE, documentId),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        app.graph.db.setIndexed(course.knowledgeDocumentId)
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "我的课程"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "课程是课堂助手的主容器；PDF 只是课程中的一种资料。"
            textSize = 13f
            setPadding(0, dp(6), 0, dp(12))
        })

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val courseCard = card("选择课程", "可以打开学习通课程，也可以创建完全本地的课程。")
        body.addView(courseCard.first, match(top = 6))
        courseSpinner = Spinner(this).apply { minimumHeight = dp(56) }
        courseCard.second.addView(courseSpinner, match(top = 12, height = 56))
        courseMeta = TextView(this).apply { textSize = 12f; setPadding(0, dp(8), 0, 0) }
        courseCard.second.addView(courseMeta)

        enterCourseButton = primary("进入课程（不打开 PDF）")
        courseCard.second.addView(enterCourseButton, match(top = 14, height = 54))

        val courseActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val addCourse = outlined("新建本地课程")
        val chaoxing = outlined("学习通课程")
        courseActions.addView(addCourse, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
        courseActions.addView(chaoxing, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        courseCard.second.addView(courseActions, match(top = 10))

        val resourceCard = card("课程资料", "超星可转换为 PDF 的课件会保存在这里；本地课程也可以加入多份 PDF。")
        body.addView(resourceCard.first, match(top = 14))
        resourceSpinner = Spinner(this).apply { minimumHeight = dp(56) }
        resourceCard.second.addView(resourceSpinner, match(top = 12, height = 56))
        resourceMeta = TextView(this).apply { textSize = 12f; setPadding(0, dp(8), 0, 0) }
        resourceCard.second.addView(resourceMeta)
        openResourceButton = primary("用 PDF 阅读器打开")
        resourceCard.second.addView(openResourceButton, match(top = 14, height = 54))
        addPdfButton = outlined("给本地课程添加 PDF")
        resourceCard.second.addView(addPdfButton, match(top = 10, height = 52))

        val toolsCard = card("其他", "资料库和设置仍然可以独立使用。")
        body.addView(toolsCard.first, match(top = 14))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val library = outlined("资料库")
        val settings = outlined("设置")
        tools.addView(library, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
        tools.addView(settings, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        toolsCard.second.addView(tools, match(top = 10))

        status = TextView(this).apply { textSize = 13f; setPadding(dp(4), dp(16), dp(4), dp(8)) }
        body.addView(status)

        courseSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderCourse(courses.getOrNull(position))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = renderCourse(null)
        }
        resourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderResource(courseResources.getOrNull(position))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = renderResource(null)
        }
        enterCourseButton.setOnClickListener { enterCourse() }
        openResourceButton.setOnClickListener { openSelectedResource() }
        addPdfButton.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        addCourse.setOnClickListener { promptCreateCourse() }
        chaoxing.setOnClickListener { startActivity(Intent(this, ChaoxingActivity::class.java)) }
        library.setOnClickListener { startActivity(Intent(this, LibraryActivity::class.java)) }
        settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        return root
    }

    private fun card(title: String, subtitle: String): Pair<MaterialCardView, LinearLayout> {
        val card = MaterialCardView(this).apply { radius = dp(24).toFloat(); cardElevation = 0f }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        body.addView(TextView(this).apply { text = title; textSize = 18f; setTypeface(typeface, Typeface.BOLD) })
        body.addView(TextView(this).apply { text = subtitle; textSize = 12f; setPadding(0, dp(5), 0, 0) })
        card.addView(body)
        return card to body
    }

    private fun primary(textValue: String) = MaterialButton(this).apply {
        text = textValue; isAllCaps = false; minHeight = 0; insetTop = 0; insetBottom = 0
    }

    private fun outlined(textValue: String) = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = textValue; isAllCaps = false; minHeight = 0; insetTop = 0; insetBottom = 0
    }

    private fun match(top: Int = 0, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (height > 0) dp(height) else height).apply {
            topMargin = dp(top)
        }

    private fun dp(value: Int): Int = (value * getResources().displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_COURSE_ONLY = "course_only"
        private const val COURSE_PAGE_STRIDE = 10_000
    }
}
