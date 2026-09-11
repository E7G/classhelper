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
    /** False only when the API payload explicitly marks the resource as not downloadable. */
    val downloadAllowed: Boolean = true,
    val restrictionReason: String = "",
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val canFetchBody: Boolean
        get() = downloadAllowed && url.isNotBlank()
}

data class KetangpaiSyncResult(
    val documentId: String,
    val resources: Int,
    val indexedChunks: Int,
    val extractedPdfPages: Int,
    val importedOfficeSections: Int,
    val failedFiles: Int,
    val skippedRestrictedFiles: Int = 0,
)
