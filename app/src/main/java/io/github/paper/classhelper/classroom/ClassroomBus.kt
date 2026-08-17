package io.github.paper.classhelper.classroom

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ClassroomUiState(
    val listening: Boolean = false,
    val stopping: Boolean = false,
    val status: String = "未开始听课",
    val partial: String = "",
    val lastQuestion: String = "",
    val answer: String = "",
    val answerStreaming: Boolean = false,
    val historyVersion: Long = 0,
    val matchedPage: Int? = null,
    val matchedLabel: String = "",
    val sessionId: String? = null
)

object ClassroomBus {
    private val _state = MutableStateFlow(ClassroomUiState())
    val state = _state.asStateFlow()
    fun update(block: (ClassroomUiState) -> ClassroomUiState) { _state.value = block(_state.value) }
}
