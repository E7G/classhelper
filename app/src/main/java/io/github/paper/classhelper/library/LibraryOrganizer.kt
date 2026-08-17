package io.github.paper.classhelper.library

import io.github.paper.classhelper.SettingsStore
import io.github.paper.classhelper.data.CourseDb
import io.github.paper.classhelper.data.DocumentRow
import io.github.paper.classhelper.data.LibraryMetaRow
import io.github.paper.classhelper.data.SessionRow
import io.github.paper.classhelper.llm.LlmClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight metadata organizer for the local library.
 *
 * AI never moves or deletes files. It only writes explicit metadata into library_meta.
 * Manual edits always remain possible and can overwrite an AI proposal.
 */
class LibraryOrganizer(
    private val db: CourseDb,
    private val settings: SettingsStore,
    private val llm: LlmClient
) {
    companion object Taxonomy {
        val PRIMARY = listOf("课程资料", "参考资料", "课堂记录", "作业与复习", "研究资料", "个人整理", "其他")
        val SUB = listOf(
            "课堂PDF", "课件PPT", "讲义", "教材", "论文", "报告", "政策规范", "标准指南", "拓展阅读",
            "课堂会话", "实时转写", "问题与回答", "AI笔记", "课堂总结",
            "作业", "习题", "答案", "错题", "考试复习", "知识清单",
            "研究设计", "研究方法", "数据说明", "结果材料", "草稿", "手工笔记", "收藏", "待处理", "其他"
        )
        val STATUS = listOf("收件箱", "学习中", "待复习", "已完成", "已归档")
    }

    suspend fun organizeDocument(documentId: String): Result<LibraryMetaRow> = runCatching {
        val doc = requireNotNull(db.getDocument(documentId)) { "资料不存在" }
        val existing = db.getLibraryMeta("document", documentId)
        val proposal = if (aiAvailable()) organizeDocumentWithAi(doc) else localDocumentProposal(doc)
        proposal.copy(starred = existing?.starred ?: false).also(db::upsertLibraryMeta)
    }

    suspend fun organizeSession(sessionId: String): Result<LibraryMetaRow> = runCatching {
        val session = requireNotNull(db.getSession(sessionId)) { "课堂记录不存在" }
        val existing = db.getLibraryMeta("session", sessionId)
        val proposal = if (aiAvailable()) organizeSessionWithAi(session) else localSessionProposal(session)
        proposal.copy(starred = existing?.starred ?: false).also(db::upsertLibraryMeta)
    }

    fun aiAvailable(): Boolean {
        val base = settings.llmBaseUrl.trim()
        val model = settings.llmModel.trim()
        if (base.isBlank() || model.isBlank()) return false
        // The default OpenAI endpoint needs a key; self-hosted OpenAI-compatible endpoints may not.
        return settings.llmApiKey.isNotBlank() || !base.contains("api.openai.com", ignoreCase = true)
    }

    private suspend fun organizeDocumentWithAi(doc: DocumentRow): LibraryMetaRow {
        val chunks = db.allChunks(doc.id, 18)
        val sample = chunks.joinToString("\n\n") { "[${it.page + 1}] ${it.title}\n${it.text.take(800)}" }.take(9_000)
        val prompt = """
            你正在整理一个本地课堂学习资料库。只做元数据分类，不改动、不删除文件。

            一级分类只能从：${PRIMARY.joinToString("、")}
            二级分类优先从：${SUB.joinToString("、")}
            状态只能从：${STATUS.joinToString("、")}

            资料信息：
            标题：${doc.title}
            格式：${doc.kind}
            来源类型：${if (doc.id.startsWith("ref-")) "导入参考资料" else "主阅读文档"}
            内容摘样：
            $sample

            请严格返回一个 JSON 对象，不要 Markdown，不要解释。字段：
            primary_category: 一级分类
            sub_category: 二级分类
            course: 课程/学科名称；无法确定则空字符串
            topic: 具体主题，尽量简短准确
            tags: 2-6 个关键词数组
            note: 80字以内说明“这是什么、适合何时使用”
            status: 默认用“收件箱”；只有内容明显是已完成成果才用“已完成”
            confidence: 0-100 整数
            reason: 60字以内说明分类依据，明确是依据标题、格式还是正文
        """.trimIndent()
        val raw = llm.complete(listOf(
            LlmClient.Message("system", "你是资料库分类器。输出必须是单个合法 JSON 对象。不要虚构无法从输入判断的信息。"),
            LlmClient.Message("user", prompt)
        ))
        return parseAiMeta("document", doc.id, raw)
    }

    private suspend fun organizeSessionWithAi(session: SessionRow): LibraryMetaRow {
        val transcripts = db.recentTranscripts(40, session.id).joinToString("\n") { it.text }.take(6_000)
        val questions = db.recentQuestions(12, session.id).joinToString("\n") { "Q: ${it.question}\nA: ${it.answer}" }.take(3_000)
        val notes = db.recentNotes(12, session.id).joinToString("\n") { it.text }.take(3_000)
        val linkedTitle = session.primaryDocumentId?.let { db.getDocument(it)?.title }.orEmpty()
        val prompt = """
            你正在整理一节课的本地课堂记录。只写元数据，不删除任何记录。

            一级分类固定为“课堂记录”。
            二级分类优先从：课堂会话、课堂总结、问题与回答、AI笔记。
            状态只能从：${STATUS.joinToString("、")}

            会话标题：${session.title}
            关联资料：$linkedTitle
            转写摘样：$transcripts
            问答摘样：$questions
            笔记摘样：$notes

            严格返回 JSON：
            primary_category: 必须是“课堂记录”
            sub_category: 二级分类
            course: 课程/学科名称，无法确定留空
            topic: 本节课核心主题
            tags: 2-6 个关键词数组
            note: 80字以内说明本节课记录内容
            status: 收件箱/学习中/待复习/已完成/已归档之一
            confidence: 0-100 整数
            reason: 60字以内说明分类依据
        """.trimIndent()
        val raw = llm.complete(listOf(
            LlmClient.Message("system", "你是课堂资料库分类器。输出必须是单个合法 JSON 对象。不要虚构。"),
            LlmClient.Message("user", prompt)
        ))
        return parseAiMeta("session", session.id, raw).copy(primaryCategory = "课堂记录")
    }

    private fun parseAiMeta(itemType: String, itemId: String, raw: String): LibraryMetaRow {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        require(start >= 0 && end > start) { "AI 返回的分类格式无效" }
        val o = JSONObject(clean.substring(start, end + 1))
        val primary = o.optString("primary_category").takeIf { it in PRIMARY } ?: "其他"
        val status = o.optString("status").takeIf { it in STATUS } ?: "收件箱"
        val tagsValue = when (val v = o.opt("tags")) {
            is JSONArray -> buildList { for (i in 0 until v.length()) add(v.optString(i)) }.filter { it.isNotBlank() }.take(6).joinToString("、")
            else -> o.optString("tags").replace(',', '、').replace('，', '、')
        }
        return LibraryMetaRow(
            itemType = itemType,
            itemId = itemId,
            primaryCategory = primary,
            subCategory = o.optString("sub_category").ifBlank { "其他" },
            course = o.optString("course"),
            topic = o.optString("topic"),
            tags = tagsValue,
            note = o.optString("note").take(160),
            status = status,
            managedBy = "ai",
            confidence = o.optInt("confidence", 70).coerceIn(0, 100),
            reason = o.optString("reason").take(120),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun localDocumentProposal(doc: DocumentRow): LibraryMetaRow {
        val title = doc.title.lowercase()
        val isReference = doc.id.startsWith("ref-")
        val primary = when {
            title.contains("作业") || title.contains("习题") || title.contains("试卷") || title.contains("答案") -> "作业与复习"
            title.contains("研究") || title.contains("数据") || title.contains("方法") -> "研究资料"
            isReference -> "参考资料"
            else -> "课程资料"
        }
        val sub = when {
            title.contains("论文") || title.endsWith("paper.pdf") -> "论文"
            title.contains("报告") -> "报告"
            title.contains("政策") || title.contains("条例") || title.contains("办法") -> "政策规范"
            title.contains("习题") || title.contains("练习") -> "习题"
            title.contains("答案") -> "答案"
            title.contains("教材") -> "教材"
            doc.kind == "pptx" -> "课件PPT"
            doc.kind == "pdf" -> if (isReference) "拓展阅读" else "课堂PDF"
            else -> if (isReference) "拓展阅读" else "讲义"
        }
        return LibraryMetaRow(
            itemType = "document", itemId = doc.id, primaryCategory = primary, subCategory = sub,
            tags = doc.kind.uppercase(), status = "收件箱", managedBy = "rule", confidence = 45,
            reason = "未配置可用 AI；仅依据文件名、格式和导入来源做本地初分。",
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun localSessionProposal(session: SessionRow) = LibraryMetaRow(
        itemType = "session", itemId = session.id, primaryCategory = "课堂记录", subCategory = "课堂会话",
        status = if (session.endedAt == null) "学习中" else "待复习", managedBy = "rule", confidence = 55,
        reason = "未配置可用 AI；依据课堂会话类型和结束状态做本地初分。",
        updatedAt = System.currentTimeMillis()
    )
}
