package io.github.paper.classhelper.classroom

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.R
import io.github.paper.classhelper.llm.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * High-priority question lane. Questions are not discarded when the teacher asks
 * several in a row. The newest answer owns the preview; older jobs can still
 * finish quietly and are persisted to history.
 */
class QuestionPipeline(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val app = context.applicationContext as ClassHelperApp
    private val sequence = AtomicLong(0)

    fun answer(question: String, sessionId: String? = app.graph.settings.activeSessionId) {
        val seq = sequence.incrementAndGet()
        scope.launch(Dispatchers.Default) {
            val settings = app.graph.settings
            val db = app.graph.db
            val contextHits = app.graph.knowledge.retrieve(question, settings.currentDocumentId, settings.currentPage)
            val recentLecture = db.recentTranscripts(16, sessionId).joinToString("\n") { it.text }.takeLast(6_000)
            val evidence = contextHits.joinToString("\n\n") { "[${it.label}]\n${it.text}" }
            val prompt = buildString {
                appendLine("老师刚刚提出的问题：$question")
                if (recentLecture.isNotBlank()) {
                    appendLine("\n最近课堂上下文：")
                    appendLine(recentLecture)
                }
                if (evidence.isNotBlank()) {
                    appendLine("\n课程资料：")
                    appendLine(evidence)
                }
                appendLine("\n请给学生一个课堂快速参考答案。第一行先直接回答，随后最多用3个短要点解释。")
                appendLine("课程资料足以支撑时优先依据资料；资料不足时明确写‘根据一般知识补充’。不要编造页码或资料出处。")
            }
            if (seq == sequence.get()) {
                ClassroomBus.update { it.copy(lastQuestion = question, answer = "", answerStreaming = true) }
            }
            val own = StringBuilder()
            try {
                val answer = app.graph.llm.stream(
                    listOf(
                        LlmClient.Message("system", "你是课堂实时助理。回答要快、准、短，并把课程资料与一般知识清楚区分。"),
                        LlmClient.Message("user", prompt)
                    )
                ) { delta ->
                    own.append(delta)
                    if (seq == sequence.get()) {
                        ClassroomBus.update { s -> s.copy(lastQuestion = question, answer = own.toString(), answerStreaming = true) }
                    }
                }
                db.addQuestion(question, answer, sessionId, contextHits.joinToString(" | ") { it.label })
                ClassroomBus.update { state ->
                    if (seq == sequence.get()) state.copy(
                        lastQuestion = question, answer = answer, answerStreaming = false,
                        historyVersion = state.historyVersion + 1
                    ) else state.copy(historyVersion = state.historyVersion + 1)
                }
                if (settings.showAnswerNotification && answer.isNotBlank()) showAnswerNotification(question, answer, seq)
            } catch (t: Throwable) {
                val error = "回答失败：${t.message ?: "unknown"}"
                db.addQuestion(question, error, sessionId, contextHits.joinToString(" | ") { it.label })
                ClassroomBus.update { state ->
                    if (seq == sequence.get()) state.copy(
                        lastQuestion = question, answer = error, answerStreaming = false,
                        historyVersion = state.historyVersion + 1
                    ) else state.copy(historyVersion = state.historyVersion + 1)
                }
            }
        }
    }

    private fun showAnswerNotification(question: String, answer: String, seq: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ANSWER_CHANNEL, "课堂答案", NotificationManager.IMPORTANCE_HIGH))
        manager.notify(
            200 + (seq % 50).toInt(),
            NotificationCompat.Builder(context, ANSWER_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_class)
                .setContentTitle(question.take(80))
                .setContentText(answer.replace('\n', ' ').take(160))
                .setStyle(NotificationCompat.BigTextStyle().bigText(answer.take(1500)))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    companion object { const val ANSWER_CHANNEL = "classhelper_answers" }
}
