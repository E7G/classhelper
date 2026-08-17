package io.github.paper.classhelper.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.ChipGroup
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.R
import io.github.paper.classhelper.asr.AsrModelManager
import io.github.paper.classhelper.data.DocumentRow
import io.github.paper.classhelper.data.LibraryMetaRow
import io.github.paper.classhelper.data.SessionRow
import io.github.paper.classhelper.library.LibraryOrganizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LibraryActivity : AppCompatActivity() {
    private lateinit var app: ClassHelperApp
    private lateinit var organizer: LibraryOrganizer
    private lateinit var contentContainer: LinearLayout
    private lateinit var systemContainer: LinearLayout
    private lateinit var aiAllButton: Button

    private var filter = Filter.ALL
    private var query = ""
    private var busy = false

    private enum class Filter { ALL, DOCUMENT, SESSION, STARRED, ARCHIVED }

    private data class Entry(
        val itemType: String,
        val itemId: String,
        val title: String,
        val document: DocumentRow? = null,
        val session: SessionRow? = null,
        val meta: LibraryMetaRow? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        app = application as ClassHelperApp
        organizer = LibraryOrganizer(app.graph.db, app.graph.settings, app.graph.llm)
        contentContainer = findViewById(R.id.libraryContentContainer)
        systemContainer = findViewById(R.id.systemStorageContainer)
        aiAllButton = findViewById(R.id.libraryAiAllButton)

        findViewById<View>(R.id.libraryBackButton).setOnClickListener { finish() }
        aiAllButton.setOnClickListener { confirmBatchOrganize() }

        findViewById<EditText>(R.id.librarySearchEdit).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty().trim()
                renderContent()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        findViewById<ChipGroup>(R.id.libraryFilterGroup).setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when (checkedIds.firstOrNull()) {
                R.id.filterDocumentChip -> Filter.DOCUMENT
                R.id.filterSessionChip -> Filter.SESSION
                R.id.filterStarredChip -> Filter.STARRED
                R.id.filterArchivedChip -> Filter.ARCHIVED
                else -> Filter.ALL
            }
            renderContent()
        }
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (::app.isInitialized) refreshAll()
    }

    private fun refreshAll() {
        renderSummary()
        renderContent()
        renderSystemStorage()
    }

    private fun entries(): List<Entry> {
        val metaByKey = app.graph.db.libraryMetaRows().associateBy { "${it.itemType}:${it.itemId}" }
        val docs = app.graph.db.listDocuments(300).map { doc ->
            Entry("document", doc.id, doc.title, document = doc, meta = metaByKey["document:${doc.id}"])
        }
        val sessions = app.graph.db.recentSessions(200).map { session ->
            Entry("session", session.id, session.title, session = session, meta = metaByKey["session:${session.id}"])
        }
        return (docs + sessions).sortedWith(
            compareByDescending<Entry> { it.meta?.starred == true }
                .thenByDescending { it.meta?.updatedAt ?: entryTime(it) }
        )
    }

    private fun entryTime(entry: Entry): Long = entry.session?.startedAt ?: entry.document?.indexedAt ?: 0L

    private fun renderSummary() {
        val counts = app.graph.db.storageContentCounts()
        val all = entries()
        val unsorted = all.count { it.meta == null || it.meta.primaryCategory.isBlank() }
        val meta = app.graph.db.libraryMetaRows()
        val categoryCounts = meta.filter { it.primaryCategory.isNotBlank() }.groupingBy { it.primaryCategory }.eachCount()
            .entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value}" }
        val workingBytes = app.graph.db.listDocuments(300).sumOf { it.workingPath.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)?.length() ?: 0L }
        val dbBytes = databaseBytes()
        val cacheBytes = directorySize(cacheDir)
        val modelBytes = (app.graph.asrModels.currentState() as? AsrModelManager.State.Ready)?.totalBytes ?: 0L
        val ocrModelBytes = app.graph.ocrModels.modelBytes()
        val total = workingBytes + dbBytes + cacheBytes + modelBytes + ocrModelBytes

        findViewById<TextView>(R.id.librarySummaryText).text =
            "应用管理约 ${formatBytes(total)} · ${counts.documents} 份文档 · ${counts.sessions} 节课堂"
        findViewById<TextView>(R.id.libraryBreakdownText).text = buildString {
            append("PDF工作副本 ${formatBytes(workingBytes)} · 语音模型 ${formatBytes(modelBytes)} · OCR模型 ${formatBytes(ocrModelBytes)} · 数据库/索引 ${formatBytes(dbBytes)} · 临时缓存 ${formatBytes(cacheBytes)}")
            append("\n转写 ${counts.transcripts} 条 · 问答 ${counts.questions} 条 · AI/手工笔记 ${counts.notes} 条 · 批注 ${counts.annotations} 条 · 书签 ${counts.bookmarks} 个")
            if (categoryCounts.isNotBlank()) append("\n分类：$categoryCounts")
        }
        findViewById<TextView>(R.id.libraryUnsortedText).text = if (unsorted > 0) {
            "还有 $unsorted 项未分类 · 可逐项手动编辑，也可批量智能整理"
        } else "全部内容都已有分类标签"
        aiAllButton.isEnabled = !busy && unsorted > 0
    }

    private fun renderContent() {
        if (!::contentContainer.isInitialized) return
        contentContainer.removeAllViews()
        val visible = entries().filter(::matchesFilter).filter(::matchesQuery)
        if (visible.isEmpty()) {
            TextView(this).also { t ->
                t.text = if (query.isBlank()) "这个分类里暂时没有内容" else "没有匹配“$query”的内容"
                t.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                t.textSize = 12f
                t.setPadding(dp(8), dp(24), dp(8), dp(24))
                contentContainer.addView(t)
            }
            return
        }
        visible.forEach { contentContainer.addView(createEntryView(it)) }
    }

    private fun createEntryView(entry: Entry): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_library_entry, contentContainer, false)
        val meta = entry.meta
        view.findViewById<TextView>(R.id.libraryItemTitle).text = entry.title
        view.findViewById<TextView>(R.id.libraryItemBadge).text = when {
            meta == null || meta.primaryCategory.isBlank() -> "未整理"
            meta.starred -> "★ ${meta.status}"
            else -> meta.status
        }
        view.findViewById<TextView>(R.id.libraryItemType).text = entryTypeLine(entry)
        view.findViewById<TextView>(R.id.libraryItemMeta).text = metadataLine(meta)
        view.findViewById<TextView>(R.id.libraryItemDetails).text = detailsLine(entry, meta)
        view.findViewById<TextView>(R.id.libraryItemNote).apply {
            val value = meta?.note.orEmpty()
            visibility = if (value.isBlank()) View.GONE else View.VISIBLE
            text = if (value.isBlank()) "" else "备注：$value"
        }
        view.findViewById<Button>(R.id.libraryItemEditButton).setOnClickListener { editMetadata(entry) }
        view.findViewById<Button>(R.id.libraryItemAiButton).apply {
            text = if (organizer.aiAvailable()) "AI整理" else "本地初分"
            isEnabled = !busy
            setOnClickListener { organizeOne(entry, this) }
        }
        view.findViewById<Button>(R.id.libraryItemStarButton).apply {
            text = if (meta?.starred == true) "取消收藏" else "收藏"
            setOnClickListener {
                app.graph.db.toggleLibraryStar(entry.itemType, entry.itemId, meta ?: defaultMeta(entry))
                refreshAll()
            }
        }
        view.findViewById<Button>(R.id.libraryItemArchiveButton).apply {
            text = if (meta?.status == "已归档") "恢复" else "归档"
            setOnClickListener { toggleArchive(entry) }
        }
        view.findViewById<Button>(R.id.libraryItemDeleteButton).apply {
            text = if (entry.itemType == "document") "移出资料库" else "删除记录"
            setOnClickListener { confirmDelete(entry) }
        }
        return view
    }

    private fun entryTypeLine(entry: Entry): String = if (entry.document != null) {
        val doc = entry.document
        val source = if (doc.id.startsWith("ref-")) "导入参考资料" else "主阅读文档"
        val size = doc.workingPath.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)?.length()?.let(::formatBytes)
        buildString {
            append(doc.kind.uppercase(Locale.getDefault())).append(" · ").append(source)
            if (size != null) append(" · ").append(size)
            append(" · ").append(if (doc.dirty) "有未同步修改" else "已同步/只读参考")
        }
    } else {
        val s = requireNotNull(entry.session)
        val end = s.endedAt ?: System.currentTimeMillis()
        val durationMin = ((end - s.startedAt).coerceAtLeast(0L) / 60_000L).coerceAtLeast(1L)
        "课堂记录 · ${formatDate(s.startedAt)} · ${durationMin}分钟 · ${if (s.endedAt == null) "进行中" else "已结束"}"
    }

    private fun metadataLine(meta: LibraryMetaRow?): String = if (meta == null || meta.primaryCategory.isBlank()) {
        "分类：未整理\n课程：— · 主题：—\n标签：—"
    } else buildString {
        append("分类：").append(meta.primaryCategory)
        if (meta.subCategory.isNotBlank()) append("  ›  ").append(meta.subCategory)
        append("\n课程：").append(meta.course.ifBlank { "—" }).append(" · 主题：").append(meta.topic.ifBlank { "—" })
        append("\n标签：").append(meta.tags.ifBlank { "—" })
    }

    private fun detailsLine(entry: Entry, meta: LibraryMetaRow?): String {
        val content = if (entry.document != null) {
            val st = app.graph.db.documentContentStats(entry.itemId)
            "索引 ${st.chunks} 段 · 批注 ${st.annotations} · 书签 ${st.bookmarks} · PDF便签 ${st.pdfNotes}"
        } else {
            val s = requireNotNull(entry.session)
            val st = app.graph.db.sessionContentStats(s.id)
            val linked = s.primaryDocumentId?.let { app.graph.db.getDocument(it)?.title }
            buildString {
                append("转写 ${st.transcripts} · 问答 ${st.questions} · 笔记 ${st.notes}")
                if (!linked.isNullOrBlank()) append(" · 关联：").append(linked)
            }
        }
        if (meta == null) return "$content\n整理：未整理"
        val owner = when (meta.managedBy) { "ai" -> "AI"; "rule" -> "本地规则"; else -> "手动" }
        return buildString {
            append(content).append("\n整理：").append(owner)
            if (meta.managedBy == "ai") append(" · 置信度 ").append(meta.confidence).append('%')
            append(" · 更新 ").append(formatDate(meta.updatedAt))
            if (meta.reason.isNotBlank()) append("\n依据：").append(meta.reason)
        }
    }

    private fun matchesFilter(entry: Entry): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.DOCUMENT -> entry.itemType == "document"
        Filter.SESSION -> entry.itemType == "session"
        Filter.STARRED -> entry.meta?.starred == true
        Filter.ARCHIVED -> entry.meta?.status == "已归档"
    }

    private fun matchesQuery(entry: Entry): Boolean {
        if (query.isBlank()) return true
        val m = entry.meta
        val hay = listOf(entry.title, entry.document?.kind.orEmpty(), m?.primaryCategory.orEmpty(), m?.subCategory.orEmpty(), m?.course.orEmpty(), m?.topic.orEmpty(), m?.tags.orEmpty(), m?.note.orEmpty()).joinToString(" ")
        return hay.contains(query, ignoreCase = true)
    }

    private fun editMetadata(entry: Entry) {
        val current = entry.meta ?: defaultMeta(entry)
        val view = layoutInflater.inflate(R.layout.dialog_library_meta, null)
        view.findViewById<TextView>(R.id.metaItemInfoText).text = buildString {
            append(if (entry.itemType == "document") "资料" else "课堂").append("：").append(entry.title)
            append("\n分类只写入 ClassHelper 元数据，不会移动或改名原文件。")
        }
        val primary = view.findViewById<AutoCompleteTextView>(R.id.metaPrimaryInput)
        val sub = view.findViewById<AutoCompleteTextView>(R.id.metaSubInput)
        val status = view.findViewById<AutoCompleteTextView>(R.id.metaStatusInput)
        primary.setAdapter(ArrayAdapter(this, R.layout.library_dropdown_item, LibraryOrganizer.PRIMARY))
        sub.setAdapter(ArrayAdapter(this, R.layout.library_dropdown_item, LibraryOrganizer.SUB))
        status.setAdapter(ArrayAdapter(this, R.layout.library_dropdown_item, LibraryOrganizer.STATUS))
        primary.setText(current.primaryCategory, false)
        sub.setText(current.subCategory, false)
        status.setText(current.status.ifBlank { "收件箱" }, false)
        view.findViewById<EditText>(R.id.metaCourseInput).setText(current.course)
        view.findViewById<EditText>(R.id.metaTopicInput).setText(current.topic)
        view.findViewById<EditText>(R.id.metaTagsInput).setText(current.tags)
        view.findViewById<EditText>(R.id.metaNoteInput).setText(current.note)
        view.findViewById<CheckBox>(R.id.metaStarCheck).isChecked = current.starred
        view.findViewById<TextView>(R.id.metaManagementText).text = buildString {
            val owner = when (current.managedBy) { "ai" -> "AI"; "rule" -> "本地规则"; else -> "手动" }
            append("当前整理来源：").append(owner)
            if (current.managedBy == "ai") append(" · AI置信度 ").append(current.confidence).append('%')
            if (current.reason.isNotBlank()) append("\n当前依据：").append(current.reason)
        }

        Md3eDialogUi.showContent(
            context = this,
            title = "编辑资料标注",
            content = view,
            positiveLabel = "保存",
        ) {
            val selectedStatus = status.text.toString().takeIf { it in LibraryOrganizer.STATUS } ?: "收件箱"
            app.graph.db.upsertLibraryMeta(current.copy(
                primaryCategory = primary.text.toString().trim(),
                subCategory = sub.text.toString().trim(),
                course = view.findViewById<EditText>(R.id.metaCourseInput).text.toString().trim(),
                topic = view.findViewById<EditText>(R.id.metaTopicInput).text.toString().trim(),
                tags = normalizeTags(view.findViewById<EditText>(R.id.metaTagsInput).text.toString()),
                note = view.findViewById<EditText>(R.id.metaNoteInput).text.toString().trim(),
                status = selectedStatus,
                starred = view.findViewById<CheckBox>(R.id.metaStarCheck).isChecked,
                managedBy = "manual",
                confidence = 0,
                reason = "用户手动编辑",
                updatedAt = System.currentTimeMillis()
            ))
            refreshAll()
        }
    }

    private fun organizeOne(entry: Entry, button: Button) {
        if (busy) return
        busy = true
        val old = button.text
        button.isEnabled = false
        button.text = "整理中…"
        lifecycleScope.launch {
            val result = if (entry.itemType == "document") organizer.organizeDocument(entry.itemId) else organizer.organizeSession(entry.itemId)
            busy = false
            button.text = old
            button.isEnabled = true
            result.onSuccess { meta ->
                val source = if (meta.managedBy == "ai") "AI" else "本地规则"
                toast("$source 已整理：${meta.primaryCategory} › ${meta.subCategory}")
            }.onFailure { toast("整理失败：${it.message ?: "未知错误"}") }
            refreshAll()
        }
    }

    private fun confirmBatchOrganize() {
        val pending = entries().filter { it.meta == null || it.meta.primaryCategory.isBlank() }.take(20)
        if (pending.isEmpty()) { toast("没有未分类内容"); return }
        val ai = organizer.aiAvailable()
        val message = if (ai) {
            "将整理 ${pending.size} 项未分类内容。只会把标题和有限正文/课堂记录摘样发送到你在设置中配置的 AI 接口；AI 只能写分类元数据，不会移动、改名或删除文件。一次最多处理 20 项。"
        } else {
            "当前没有可用的 AI 接口，将用本地规则对 ${pending.size} 项内容做初步分类，不联网。之后仍可逐项手动修改。"
        }
        Md3eDialogUi.showConfirm(
            context = this,
            title = if (ai) "AI 整理未分类内容" else "本地规则初分",
            message = message,
            positiveLabel = "开始整理",
        ) { batchOrganize(pending) }
    }

    private fun batchOrganize(pending: List<Entry>) {
        if (busy) return
        busy = true
        aiAllButton.isEnabled = false
        lifecycleScope.launch {
            var ok = 0
            pending.forEachIndexed { index, entry ->
                aiAllButton.text = "整理 ${index + 1}/${pending.size}"
                val result = if (entry.itemType == "document") organizer.organizeDocument(entry.itemId) else organizer.organizeSession(entry.itemId)
                if (result.isSuccess) ok++
            }
            busy = false
            aiAllButton.text = "AI整理未分类"
            toast("已整理 $ok/${pending.size} 项")
            refreshAll()
        }
    }

    private fun toggleArchive(entry: Entry) {
        val current = entry.meta ?: defaultMeta(entry)
        val next = if (current.status == "已归档") "收件箱" else "已归档"
        app.graph.db.upsertLibraryMeta(current.copy(status = next, managedBy = "manual", reason = "用户手动${if (next == "已归档") "归档" else "恢复"}", updatedAt = System.currentTimeMillis()))
        refreshAll()
    }

    private fun confirmDelete(entry: Entry) {
        if (entry.itemType == "session" && app.graph.settings.activeSessionId == entry.itemId && entry.session?.endedAt == null) {
            toast("这节课仍在进行中，请先结束听课再删除记录")
            return
        }
        val (title, message) = if (entry.document != null) {
            "移出资料库？" to "将删除 ClassHelper 的索引、批注记录和应用私有 PDF 工作副本；不会删除你通过系统文件选择器打开的原文件。参考资料的原文件也不会删除。"
        } else {
            val st = app.graph.db.sessionContentStats(entry.itemId)
            "删除课堂记录？" to "将删除这节课在 ClassHelper 中的 ${st.transcripts} 条转写、${st.questions} 条问答和 ${st.notes} 条笔记。此操作不可撤销。"
        }
        Md3eDialogUi.showConfirm(
            context = this,
            title = title,
            message = message,
            positiveLabel = "确认删除",
            danger = true,
        ) {
            if (entry.document != null) removeDocument(entry.document) else app.graph.db.deleteSession(entry.itemId)
            refreshAll()
        }
    }

    private fun removeDocument(doc: DocumentRow) {
        if (doc.workingPath.isNotBlank()) {
            val file = File(doc.workingPath)
            val workspaceRoot = File(filesDir, "pdf-workspaces")
            if (file.isFile && runCatching { file.canonicalPath.startsWith(workspaceRoot.canonicalPath + File.separator) }.getOrDefault(false)) {
                runCatching { file.delete() }
            }
        }
        app.graph.db.deleteDocument(doc.id)
        if (app.graph.settings.currentDocumentId == doc.id) {
            app.graph.settings.currentDocumentId = null
            app.graph.settings.currentPage = 0
        }
        app.graph.knowledge.invalidate(doc.id)
        toast("已移出资料库；原文件未删除")
    }

    private fun defaultMeta(entry: Entry): LibraryMetaRow = LibraryMetaRow(
        itemType = entry.itemType,
        itemId = entry.itemId,
        status = if (entry.session?.endedAt == null && entry.session != null) "学习中" else "收件箱",
        managedBy = "manual",
        reason = "尚未分类",
        updatedAt = System.currentTimeMillis()
    )

    private fun renderSystemStorage() {
        systemContainer.removeAllViews()
        val docs = app.graph.db.listDocuments(300)
        val working = docs.sumOf { it.workingPath.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)?.length() ?: 0L }
        addSystemCard(
            "PDF 工作副本",
            "${formatBytes(working)} · ${docs.count { it.kind == "pdf" }} 份 PDF\n用于批注和安全写回。移出某份资料时只删除应用私有副本，不碰原 PDF。"
        )

        val modelState = app.graph.asrModels.currentState()
        when (modelState) {
            is AsrModelManager.State.Ready -> addSystemCard(
                "SenseVoice 本地语音模型",
                "${formatBytes(modelState.totalBytes)} · 已安装\n只用于本地课堂语音识别，与资料分类分开管理。",
                "删除模型"
            ) {
                Md3eDialogUi.showConfirm(
                    context = this,
                    title = "删除本地语音模型？",
                    message = "释放约 ${formatBytes(modelState.totalBytes)}。不会删除课堂记录；下次听课前需要重新下载。",
                    positiveLabel = "删除",
                    danger = true,
                ) {
                    if (app.graph.asrModels.delete()) toast("语音模型已删除") else toast("模型删除失败")
                    refreshAll()
                }
            }
            else -> addSystemCard("SenseVoice 本地语音模型", "未安装或正在准备 · 可在设置中管理下载")
        }

        when (val ocrState = app.graph.ocrModels.currentState()) {
            is io.github.paper.classhelper.ocr.OcrModelManager.State.Ready -> addSystemCard(
                "PP-OCRv6 高精度 OCR 模型",
                "${formatBytes(ocrState.totalBytes)} · 已安装\n扫描页使用文本检测 + 逐行识别；普通 PDF 仍优先直接读取文本层。",
                "删除模型"
            ) {
                Md3eDialogUi.showConfirm(
                    context = this, title = "删除高精度 OCR 模型？",
                    message = "释放约 ${formatBytes(ocrState.totalBytes)}。不会删除 OCR 后的全文索引；之后扫描页自动回退 ML Kit。",
                    positiveLabel = "删除", danger = true
                ) {
                    if (app.graph.ocrModels.delete()) toast("OCR 模型已删除") else toast("OCR 模型删除失败")
                    refreshAll()
                }
            }
            else -> addSystemCard("PP-OCRv6 高精度 OCR 模型", "未安装 · 扫描页当前使用 ML Kit；可在设置中下载约 30 MB 高精度模型")
        }

        val counts = app.graph.db.storageContentCounts()
        addSystemCard(
            "数据库与全文索引",
            "${formatBytes(databaseBytes())}\n保存分类元数据、全文索引、课堂转写、问答、笔记、书签和批注历史。当前：索引文档 ${counts.documents}、转写 ${counts.transcripts}、批注 ${counts.annotations}。"
        )

        val cache = directorySize(cacheDir)
        addSystemCard(
            "临时缓存",
            "${formatBytes(cache)} · 可安全清理\n只清理 Android cacheDir，不删除 PDF 工作副本、模型、课堂记录或资料分类。",
            "清理缓存"
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { cacheDir.listFiles()?.forEach(::deleteRecursivelySafe) }
                toast("临时缓存已清理")
                refreshAll()
            }
        }
    }

    private fun addSystemCard(title: String, details: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val view = layoutInflater.inflate(R.layout.item_system_storage, systemContainer, false)
        view.findViewById<TextView>(R.id.systemStorageTitle).text = title
        view.findViewById<TextView>(R.id.systemStorageDetails).text = details
        view.findViewById<Button>(R.id.systemStorageAction).apply {
            visibility = if (action == null) View.GONE else View.VISIBLE
            text = action.orEmpty()
            setOnClickListener { onAction?.invoke() }
        }
        systemContainer.addView(view)
    }

    private fun databaseBytes(): Long {
        val main = getDatabasePath("classhelper.db")
        return listOf(main, File(main.path + "-wal"), File(main.path + "-shm")).sumOf { if (it.isFile) it.length() else 0L }
    }

    private fun directorySize(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> file.listFiles()?.sumOf(::directorySize) ?: 0L
    }

    private fun deleteRecursivelySafe(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursivelySafe)
        runCatching { file.delete() }
    }

    private fun normalizeTags(raw: String): String = raw.replace(',', '、').replace('，', '、')
        .split('、', ' ', '\n', '\t').map(String::trim).filter(String::isNotBlank).distinct().take(12).joinToString("、")

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatDate(ts: Long): String = if (ts <= 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    private fun resolveColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        return try { a.getColor(0, 0xff666666.toInt()) } finally { a.recycle() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
