package io.github.paper.classhelper.classroom

import android.content.Context
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.llm.LlmClient
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Isolated question lane. The caller supplies a dedicated dispatcher, so retrieval and prompt
 * preparation can never borrow the Zipformer decoder thread or the transcript event lane.
 */
class QuestionPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext as ClassHelperApp
    private val sequence = AtomicLong(0)

    fun answer(question: String, sessionId: String? = app.graph.settings.activeSessionId) {
        val seq = sequence.incrementAndGet()
        scope.launch {
            val settings = app.graph.settings
            val db = app.graph.db
            val preferred = if (
                settings.ketangpaiCourseDocumentId != null &&
                settings.ketangpaiLastSync >= settings.chaoxingLastSync
            ) settings.ketangpaiCourseDocumentId else settings.chaoxingCourseDocumentId
            val contextHits = app.graph.knowledge.retrieve(
                question = question,
                currentDocumentId = settings.currentDocumentId,
                currentPage = settings.currentPage,
                preferredDocumentId = preferred,
            )
            val recentLecture = db.recentTranscripts(16, sessionId).joinToString("\n") { it.text }.takeLast(6_000)
            val evidence = contextHits.joinToString("\n\n") { "[${it.label}]\n${it.text}" }
            val prompt = buildString {
                appendLine("老师刚刚提出的问题：$question")
                if (settings.ketangpaiCourseName.isNotBlank()) appendLine("当前绑定课堂派课程：${settings.ketangpaiCourseName}")
                if (settings.chaoxingCourseName.isNotBlank()) appendLine("当前绑定学习通课程：${settings.chaoxingCourseName}")
                if (recentLecture.isNotBlank()) {
                    appendLine("\n最近课堂上下文：")
                    appendLine(recentLecture)
                }
                if (evidence.isNotBlank()) {
                    appendLine("\n课程资料：")
                    appendLine(evidence)
                }
                appendLine("\n请给学生一个课堂快速参考答案。第一行先直接回答，随后最多用3个短要点解释。")
                appendLine("资料足以支撑时优先依据当前 PDF 与最近同步绑定的课堂派/学习通课程资料；资料不足时明确写‘根据一般知识补充’。不要编造页码或资料出处。")
            }
            if (seq == sequence.get()) {
                ClassroomBus.update { it.copy(lastQuestion = question, answer = "", answerStreaming = true) }
            }
            val own = StringBuilder()
            try {
                val answer = app.graph.llm.stream(
                    listOf(
                        LlmClient.Message("system", "你是课堂实时助理。回答要快、准、短，并把课程资料与一般知识清楚区分。"),
                        LlmClient.Message("user", prompt),
                    ),
                ) { delta ->
                    own.append(delta)
                    if (seq == sequence.get()) {
                        ClassroomBus.update { s -> s.copy(lastQuestion = question, answer = own.toString(), answerStreaming = true) }
                    }
                }
                db.addQuestion(question, answer, sessionId, contextHits.joinToString(" | ") { it.label })
                ClassroomBus.update { state ->
                    if (seq == sequence.get()) state.copy(
                        lastQuestion = question,
                        answer = answer,
                        answerStreaming = false,
                        historyVersion = state.historyVersion + 1,
                    ) else state.copy(historyVersion = state.historyVersion + 1)
                }
            } catch (t: Throwable) {
                val error = "回答失败：${t.message ?: "unknown"}"
                db.addQuestion(question, error, sessionId, contextHits.joinToString(" | ") { it.label })
                ClassroomBus.update { state ->
                    if (seq == sequence.get()) state.copy(
                        lastQuestion = question,
                        answer = error,
                        answerStreaming = false,
                        historyVersion = state.historyVersion + 1,
                    ) else state.copy(historyVersion = state.historyVersion + 1)
                }
            }
        }
    }
}
