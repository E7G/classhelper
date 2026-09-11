package io.github.paper.classhelper.ketangpai

data class KetangpaiCourse(
    val id: String,
    val name: String,
    val teacher: String = "",
)

data class KetangpaiResource(
    val id: String,
    val name: String,
    val url: String,
    val size: String = "",
    val contentType: String = "",
    val sourceTitle: String = "",
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()
}

data class KetangpaiSyncResult(
    val documentId: String,
    val resources: Int,
    val indexedChunks: Int,
    val extractedPdfPages: Int,
    val importedOfficeSections: Int,
    val failedFiles: Int,
)
