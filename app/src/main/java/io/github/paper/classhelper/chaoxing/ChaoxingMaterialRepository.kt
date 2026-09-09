package io.github.paper.classhelper.chaoxing

import android.content.Context
import io.github.paper.classhelper.SettingsStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class ChaoxingMaterial(
    val chapterTitle: String,
    val name: String,
    val type: String,
    val objectId: String?,
    val directUrl: String?,
)

/** Read-only material browser helper. It never submits progress or task state. */
class ChaoxingMaterialRepository(
    context: Context,
    private val settings: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun listMaterials(
        client: ChaoxingClient,
        course: ChaoxingCourse,
        chapter: ChaoxingChapter,
    ): List<ChaoxingMaterial> = withContext(Dispatchers.IO) {
        require(client.ensureSession()) { "学习通登录已失效，请重新登录" }
        val out = LinkedHashMap<String, ChaoxingMaterial>()
        val count = client.cardCount(course, chapter)
        for (page in 0 until count) {
            val card = runCatching { client.loadCard(course, chapter, page) }.getOrNull() ?: continue
            val attachments = card.optJSONArray("attachments") ?: JSONArray()
            for (i in 0 until attachments.length()) {
                val attachment = attachments.optJSONObject(i) ?: continue
                val property = attachment.optJSONObject("property") ?: JSONObject()
                val type = attachment.optString("type").trim().ifBlank {
                    if (property.optString("bookname").isNotBlank()) "book" else "resource"
                }
                val name = sequenceOf(
                    property.optString("name"),
                    property.optString("bookname"),
                    property.optString("title"),
                    attachment.optString("name"),
                ).map { it.trim() }.firstOrNull { it.isNotBlank() }
                    ?: "${type.ifBlank { "资料" }} ${i + 1}"
                val objectId = property.optString("objectid").trim().takeIf { it.isNotBlank() }
                val directUrl = sequenceOf(
                    property.optString("url"),
                    property.optString("downloadUrl"),
                    property.optString("href"),
                    attachment.optString("url"),
                    attachment.optString("downloadUrl"),
                ).map { it.trim() }.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                val key = objectId ?: "$name|${directUrl.orEmpty()}|$page|$i"
                out.putIfAbsent(
                    key,
                    ChaoxingMaterial(
                        chapterTitle = chapter.title,
                        name = name,
                        type = type,
                        objectId = objectId,
                        directUrl = directUrl,
                    ),
                )
            }
        }
        out.values.toList()
    }

    suspend fun resolve(
        client: ChaoxingClient,
        material: ChaoxingMaterial,
    ): ChaoxingResource = withContext(Dispatchers.IO) {
        material.objectId?.let { id ->
            runCatching { client.resolveResource(id) }.getOrNull()?.let { resolved ->
                return@withContext resolved.copy(
                    name = material.name.ifBlank { resolved.name },
                )
            }
        }
        ChaoxingResource(
            type = material.type,
            name = material.name,
            objectId = material.objectId,
            pageCount = null,
            sourceUrl = material.directUrl,
            pdfUrl = material.directUrl?.takeIf { it.substringBefore('?').endsWith(".pdf", ignoreCase = true) },
        )
    }

    suspend fun cachePdf(
        course: ChaoxingCourse,
        material: ChaoxingMaterial,
        url: String,
        maxBytes: Long = MAX_VIEW_PDF_BYTES,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        require(url.startsWith("http://") || url.startsWith("https://")) { "无效的 PDF 地址" }
        val folderKey = shortHash("${course.courseId}:${course.classId}")
        val identity = material.objectId ?: shortHash("${material.name}|$url")
        val displayBase = safeName(material.name.substringBeforeLast('.').ifBlank { "chaoxing" })
        val targetDir = File(appContext.filesDir, "chaoxing-view/$folderKey").apply { mkdirs() }
        val target = File(targetDir, "${displayBase.take(50)}-${safeName(identity).take(32)}.pdf")
        if (target.isFile && target.length() > MIN_VALID_PDF_BYTES) return@withContext target

        val temp = File(targetDir, target.name + ".part")
        temp.delete()
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", settings.chaoxingCookie)
            .header("Referer", "https://mooc1-2.chaoxing.com/")
            .get().build()

        try {
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
                val body = response.body ?: error("下载失败：响应为空")
                val total = body.contentLength().takeIf { it > 0 }
                if (total != null && total > maxBytes) error("课件超过 ${maxBytes / 1024 / 1024} MB，暂不缓存")
                var written = 0L
                temp.outputStream().buffered(128 * 1024).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            if (written > maxBytes) error("课件超过 ${maxBytes / 1024 / 1024} MB，已停止下载")
                            output.write(buffer, 0, n)
                            onProgress(written, total)
                        }
                    }
                }
                check(temp.length() > MIN_VALID_PDF_BYTES) { "下载到的文件不是有效 PDF" }
                if (target.exists()) target.delete()
                check(temp.renameTo(target) || runCatching {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                    true
                }.getOrDefault(false)) { "无法保存课件缓存" }
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
        target
    }

    private fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(20)

    private fun safeName(value: String): String = value
        .replace(Regex("[^0-9A-Za-z._\\-\\u4e00-\\u9fff]+"), "_")
        .trim('_')
        .ifBlank { "resource" }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152.0 Mobile Safari/537.36 ClassHelper/1.0"
        private const val MIN_VALID_PDF_BYTES = 1024L
        private const val MAX_VIEW_PDF_BYTES = 160L * 1024L * 1024L
    }
}
