package io.github.paper.classhelper.chaoxing

import android.content.Context
import io.github.paper.classhelper.SettingsStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /**
     * Scan all chapters and return one flat, de-duplicated material list in course order.
     * Chapter information is kept only as lightweight source metadata; callers do not need to group by it.
     */
    suspend fun listCourseMaterials(
        client: ChaoxingClient,
        course: ChaoxingCourse,
        onProgress: (done: Int, total: Int, chapterTitle: String) -> Unit = { _, _, _ -> },
    ): List<ChaoxingMaterial> = withContext(Dispatchers.IO) {
        require(client.ensureSession()) { "学习通登录已失效，请重新登录" }
        val chapters = client.listChapters(course)
        val merged = LinkedHashMap<String, ChaoxingMaterial>()
        chapters.forEachIndexed { index, chapter ->
            onProgress(index + 1, chapters.size, chapter.title)
            readChapterMaterials(client, course, chapter).forEach { material ->
                merged.putIfAbsent(materialIdentity(material), material)
            }
        }
        merged.values.toList()
    }

    suspend fun listMaterials(
        client: ChaoxingClient,
        course: ChaoxingCourse,
        chapter: ChaoxingChapter,
    ): List<ChaoxingMaterial> = withContext(Dispatchers.IO) {
        require(client.ensureSession()) { "学习通登录已失效，请重新登录" }
        readChapterMaterials(client, course, chapter)
    }

    private suspend fun readChapterMaterials(
        client: ChaoxingClient,
        course: ChaoxingCourse,
        chapter: ChaoxingChapter,
    ): List<ChaoxingMaterial> {
        val out = LinkedHashMap<String, ChaoxingMaterial>()
        val count = runCatching { client.cardCount(course, chapter) }.getOrDefault(0)

        suspend fun scanPages(pages: Iterable<Int>) {
            for (page in pages) {
                val card = runCatching { client.loadCard(course, chapter, page) }.getOrNull() ?: continue
                val attachments = collectAttachments(card)
                for (i in attachments.indices) {
                    val attachment = attachments[i]
                    parseMaterial(chapter, attachment, page, i)?.let { material ->
                        out.putIfAbsent(materialIdentity(material), material)
                    }
                }
            }
        }

        // The common response uses zero-based num values.
        if (count > 0) scanPages(0 until count) else scanPages(listOf(0))

        // Some Chaoxing deployments expose cardcount but expect num to be one-based (or omit
        // cardcount altogether). Only probe the alternate numbering when the normal scan found
        // nothing, keeping ordinary courses fast while fixing false "no material" results.
        if (out.isEmpty()) {
            if (count > 0) scanPages(1..count) else scanPages(listOf(1))
        }
        return out.values.toList()
    }

    /**
     * mArg has changed shape across Chaoxing deployments. Attachments may be directly under the
     * root, inside card/data objects, or nested one level deeper. Walk the JSON tree and collect
     * every array named "attachments" instead of assuming one fixed response layout.
     */
    private fun collectAttachments(root: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()

        fun add(array: JSONArray) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val signature = obj.toString()
                if (seen.add(signature)) out += obj
            }
        }

        fun walk(value: Any?, depth: Int) {
            if (value == null || depth > 7) return
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (key.equals("attachments", ignoreCase = true) && child is JSONArray) add(child)
                        else if (child is JSONObject || child is JSONArray) walk(child, depth + 1)
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), depth + 1)
            }
        }

        walk(root, 0)
        return out
    }

    private fun parseMaterial(
        chapter: ChaoxingChapter,
        attachment: JSONObject,
        page: Int,
        index: Int,
    ): ChaoxingMaterial? {
        val property = attachment.optJSONObject("property") ?: JSONObject()
        val data = attachment.optJSONObject("data") ?: JSONObject()
        val propertyData = property.optJSONObject("data") ?: JSONObject()
        val candidates = listOf(property, attachment, data, propertyData)

        val type = firstText(candidates, TYPE_KEYS).ifBlank {
            if (firstText(candidates, listOf("bookname")).isNotBlank()) "book" else "resource"
        }
        val name = firstText(candidates, NAME_KEYS).ifBlank { "${type.ifBlank { "资料" }} ${index + 1}" }
        val objectId = firstText(candidates, OBJECT_ID_KEYS).takeIf { it.isNotBlank() }
        val directUrl = firstHttpUrl(candidates)
            ?: objectIdFromUrl(firstText(candidates, URL_KEYS))?.let { null }
        val urlObjectId = objectId ?: directUrl?.let(::objectIdFromUrl)

        // Keep named attachment entries even when no address is currently available. This makes
        // the UI accurately show that the file exists, while preview remains enabled whenever an
        // object id or URL can be resolved.
        return ChaoxingMaterial(
            chapterTitle = chapter.title,
            name = name,
            type = type,
            objectId = urlObjectId,
            directUrl = directUrl,
        )
    }

    private fun firstText(objects: List<JSONObject>, keys: List<String>): String {
        for (obj in objects) {
            for (key in keys) {
                val raw = obj.opt(key) ?: continue
                val value = when (raw) {
                    is String -> raw
                    is Number -> raw.toString()
                    else -> continue
                }.trim()
                if (value.isNotBlank() && !value.equals("null", ignoreCase = true)) return value
            }
        }
        return ""
    }

    private fun firstHttpUrl(objects: List<JSONObject>): String? {
        for (obj in objects) {
            for (key in URL_KEYS) {
                val value = obj.optString(key).trim()
                if (value.startsWith("http://") || value.startsWith("https://")) return value
            }
        }
        return null
    }

    private fun objectIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val parsed = url.toHttpUrlOrNull()
        val queryId = sequenceOf("objectid", "objectId", "objectID", "oid")
            .mapNotNull { parsed?.queryParameter(it)?.trim() }
            .firstOrNull { it.isNotBlank() }
        if (!queryId.isNullOrBlank()) return queryId
        return Regex("(?i)/ananas/(?:status|modules/[^/]+)/(?:[^/?#]*/)?([0-9a-f]{16,})")
            .find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun materialIdentity(material: ChaoxingMaterial): String =
        material.objectId?.let { "id:$it" }
            ?: material.directUrl?.let { "url:${material.name.trim().lowercase()}|$it" }
            ?: "fallback:${material.chapterTitle}|${material.name}|${material.type}"

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
        private val OBJECT_ID_KEYS = listOf("objectid", "objectId", "objectID", "object_id", "oid")
        private val NAME_KEYS = listOf("name", "filename", "fileName", "title", "bookname")
        private val TYPE_KEYS = listOf("type", "module", "fileType", "suffix")
        private val URL_KEYS = listOf(
            "url", "downloadUrl", "downloadurl", "downloadURL", "download_url", "href",
            "fileUrl", "fileURL", "fileurl", "pdf", "http", "httphd", "previewUrl", "previewURL",
        )
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152.0 Mobile Safari/537.36 ClassHelper/1.0"
        private const val MIN_VALID_PDF_BYTES = 1024L
        private const val MAX_VIEW_PDF_BYTES = 160L * 1024L * 1024L
    }
}
