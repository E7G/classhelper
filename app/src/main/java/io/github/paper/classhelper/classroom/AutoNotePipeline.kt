package io.github.paper.classhelper.classroom

import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.llm.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Low-priority batched note generation; question answering remains independent. */
class AutoNotePipeline(private val app: ClassHelperApp, private val scope: CoroutineScope) {
    private var finalsSinceNote = 0
    private var pending: Job? = null

    fun onFinalTranscript(sessionId: String?) {
        if (!app.graph.settings.autoNotes) return
        finalsSinceNote++
        if (finalsSinceNote >= 12) schedule(8_000, sessionId)
    }

    fun summarizeNow(sessionId: String? = app.graph.settings.activeSessionId, onDone: (Result<String>) -> Unit = {}) =
        schedule(0, sessionId, onDone)

    private fun schedule(delayMs: Long, sessionId: String?, onDone: (Result<String>) -> Unit = {}) {
        pending?.cancel()
        pending = scope.launch(Dispatchers.Default) {
            delay(delayMs)
            val transcript = app.graph.db.recentTranscripts(100, sessionId).joinToString("\n") { it.text }
            if (transcript.length < 80) { onDone(Result.failure(IllegalStateException("课堂内容还太少，暂时无需整理"))); return@launch }
            val result = runCatching {
                app.graph.llm.complete(listOf(
                    LlmClient.Message("system", "你负责把课堂原始转写整理成结构化复习笔记。严格区分课堂原话和推断，不虚构老师没说过的事实。"),
                    LlmClient.Message("user", "请按‘主题/核心概念/老师强调/课堂问题/例子/易错点/待复习’整理以下课堂内容。保留公式、数字、术语，去掉口头重复：\n\n$transcript")
                ))
            }
            result.onSuccess { note ->
                if (note.isNotBlank()) {
                    app.graph.db.addNote(note, sessionId)
                    ClassroomBus.update { it.copy(historyVersion = it.historyVersion + 1) }
                }
            }
            finalsSinceNote = 0
            onDone(result)
        }
    }
}
