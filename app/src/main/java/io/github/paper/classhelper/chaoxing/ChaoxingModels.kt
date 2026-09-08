package io.github.paper.classhelper.chaoxing

data class ChaoxingQrSession(
    val uuid: String,
    val enc: String,
    val loginUrl: String,
)

sealed interface ChaoxingQrStatus {
    data class Pending(val message: String) : ChaoxingQrStatus
    data class Success(val displayName: String?) : ChaoxingQrStatus
}

data class ChaoxingCourse(
    val courseId: String,
    val classId: String,
    val cpi: String,
    val name: String,
    val teacher: String,
    val classroom: String,
    val url: String,
)

data class ChaoxingChapter(
    val knowledgeId: String,
    val title: String,
    val depth: Int,
)

data class ChaoxingResource(
    val type: String,
    val name: String,
    val objectId: String?,
    val pageCount: Int?,
    val sourceUrl: String?,
    val pdfUrl: String?,
)

data class ChaoxingSyncResult(
    val documentId: String,
    val chapters: Int,
    val resources: Int,
    val indexedChunks: Int,
    val extractedPdfPages: Int,
    val skippedLargeFiles: Int,
)
