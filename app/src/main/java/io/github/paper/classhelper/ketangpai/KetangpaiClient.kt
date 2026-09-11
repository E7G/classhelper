package io.github.paper.classhelper.ketangpai

import android.util.Base64
import io.github.paper.classhelper.SettingsStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Read-only KETANGPAI client for the current user's authorized course resources. */
class KetangpaiClient(private val settings: SettingsStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var token: String = settings.ketangpaiToken

    suspend fun login(account: String, password: String, remember: Boolean = true): String? = withContext(Dispatchers.IO) {
        require(account.isNotBlank()) { "请输入课堂派账号" }
        require(password.isNotBlank()) { "请输入课堂派密码" }

        val encryptedPayload = JSONObject()
            .put("email", account)
            .put("password", encryptPassword(password))
            .put("remember", if (remember) "1" else "0")
            .put("code", "")
            .put("mobile", "")
            .put("type", "login")
            .put("encryption", 1)
            .put("reqtimestamp", System.currentTimeMillis())

        var response = post("/UserApi/login", encryptedPayload, includeToken = false)
        if (!success(response)) {
            // Compatibility fallback for older accounts/endpoints that still accept plain password.
            val legacy = JSONObject()
                .put("email", account)
                .put("password", password)
                .put("remember", if (remember) "1" else "0")
                .put("code", "")
                .put("mobile", "")
                .put("type", "login")
                .put("reqtimestamp", System.currentTimeMillis())
            response = post("/UserApi/login", legacy, includeToken = false)
        }
        require(success(response)) { message(response).ifBlank { "课堂派登录失败" } }
        val data = response.optJSONObject("data") ?: JSONObject()
        val newToken = data.optString("token").ifBlank { response.optString("token") }
        require(newToken.isNotBlank()) { "登录成功但没有返回 Token" }
        token = newToken
        settings.ketangpaiAccount = account
        settings.ketangpaiPassword = password
        settings.ketangpaiToken = newToken
        data.optString("username").ifBlank { data.optString("name") }.takeIf { it.isNotBlank() }
    }

    suspend fun ensureSession(): Boolean = withContext(Dispatchers.IO) {
        if (checkSessionInternal()) return@withContext true
        val account = settings.ketangpaiAccount
        val password = settings.ketangpaiPassword
        if (account.isBlank() || password.isBlank()) return@withContext false
        runCatching { login(account, password, remember = true) }.isSuccess
    }

    suspend fun listCourses(): List<KetangpaiCourse> = withContext(Dispatchers.IO) {
        require(ensureSession()) { "课堂派登录已失效，请重新登录" }

        val primary = runCatching {
            post("/FutureV2/CourseMeans/getCourseList", JSONObject())
        }.getOrNull()
        var courses = primary?.takeIf(::success)?.let { parseCourses(it.opt("data")) }.orEmpty()

        if (courses.isEmpty()) {
            val fallback = post(
                "/CourseApi/semesterCourseList",
                JSONObject()
                    .put("isstudy", 1)
                    .put("search", "")
                    .put("reqtimestamp", System.currentTimeMillis()),
            )
            if (success(fallback)) courses = parseCourses(fallback.opt("data"))
        }
        courses.distinctBy { it.id }.filter { it.id.isNotBlank() }
    }

    suspend fun listResources(course: KetangpaiCourse): List<KetangpaiResource> = withContext(Dispatchers.IO) {
        require(ensureSession()) { "课堂派登录已失效，请重新登录" }
        val result = LinkedHashMap<String, KetangpaiResource>()

        fun addAll(items: List<KetangpaiResource>) {
            items.forEach { r ->
                val key = r.url.ifBlank { r.id.ifBlank { r.name + "|" + r.sourceTitle } }
                result.putIfAbsent(key, r)
            }
        }

        val contentTypes = linkedSetOf("1", "2", "8")
        runCatching {
            val setting = post("/Futurev2/CourseTemplate/getSetting", JSONObject().put("courseid", course.id))
            if (success(setting)) {
                val nav = setting.optJSONObject("data")?.optJSONArray("navigation") ?: JSONArray()
                for (i in 0 until nav.length()) {
                    val value = nav.optJSONObject(i)?.opt("contenttype") ?: continue
                    when (value) {
                        is JSONArray -> for (j in 0 until value.length()) value.opt(j)?.toString()?.takeIf { it.isNotBlank() && it != "0" }?.let(contentTypes::add)
                        else -> value.toString().takeIf { it.isNotBlank() && it != "0" }?.let(contentTypes::add)
                    }
                }
            }
        }

        contentTypes.forEach { type -> addAll(fetchCourseContent(course.id, type)) }
        addAll(fetchCourseContent(course.id, null))
        addAll(fetchCourseware(course.id))
        result.values.toList()
    }

    fun logout(clearCredentials: Boolean = true) {
        token = ""
        settings.ketangpaiToken = ""
        if (clearCredentials) {
            settings.ketangpaiAccount = ""
            settings.ketangpaiPassword = ""
        }
    }

    fun downloadResource(resource: KetangpaiResource, file: java.io.File, maxBytes: Long): Boolean {
        val url = normalizeUrl(resource.url)
        if (url.isBlank()) return false
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.ketangpai.com/")
            .apply { token.takeIf { it.isNotBlank() }?.let { header("Token", it) } }
            .get().build()
        return try {
            http.newCall(request).execute().use download@{ response ->
                if (!response.isSuccessful) return@download false
                val body = response.body ?: return@download false
                if (body.contentLength() > maxBytes) return@download false
                file.parentFile?.mkdirs()
                var total = 0L
                var tooLarge = false
                file.outputStream().buffered(128 * 1024).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) { tooLarge = true; break }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (tooLarge || file.length() <= 0L) {
                    file.delete(); false
                } else true
            }
        } catch (_: Throwable) {
            file.delete(); false
        }
    }

    private fun checkSessionInternal(): Boolean {
        if (token.isBlank()) return false
        return runCatching { success(post("/UserApi/getUserBasinInfo", JSONObject())) }.getOrDefault(false)
    }

    private fun fetchCourseContent(courseId: String, contentType: String?): List<KetangpaiResource> {
        val out = mutableListOf<KetangpaiResource>()
        for (page in 1..MAX_PAGES) {
            val payload = JSONObject()
                .put("courseid", courseId)
                .put("page", page)
                .put("limit", PAGE_SIZE)
                .put("courserole", 0)
                .put("desc", 3)
                .put("dirid", 0)
                .put("lessonlink", JSONArray())
                .put("sort", JSONArray())
                .put("vtr_type", "")
            if (contentType != null) payload.put("contenttype", contentType)
            val response = runCatching { post("/FutureV2/CourseMeans/getCourseContent", payload) }.getOrNull() ?: break
            if (!success(response)) break
            val data = response.optJSONObject("data") ?: break
            val list = data.optJSONArray("list") ?: break
            for (i in 0 until list.length()) collectResources(list.optJSONObject(i), contentType.orEmpty(), out)
            if (list.length() < PAGE_SIZE) break
        }
        return out
    }

    private fun fetchCourseware(courseId: String): List<KetangpaiResource> {
        val out = mutableListOf<KetangpaiResource>()
        for (page in 1..MAX_PAGES) {
            val response = runCatching {
                post(
                    "/CoursewareApi/getListsByFileType",
                    JSONObject().put("courseid", courseId).put("fileType", "").put("page", page).put("limit", PAGE_SIZE),
                )
            }.getOrNull() ?: break
            if (!success(response)) break
            val data = response.optJSONObject("data") ?: break
            val list = data.optJSONArray("list") ?: break
            for (i in 0 until list.length()) collectResources(list.optJSONObject(i), "courseware", out)
            if (list.length() < PAGE_SIZE) break
        }
        return out
    }

    private fun collectResources(item: JSONObject?, contentType: String, out: MutableList<KetangpaiResource>) {
        if (item == null) return
        val sourceTitle = firstString(item, "title", "name", "coursename")
        val attachments = item.optJSONArray("attachment") ?: item.optJSONArray("attachments")
        if (attachments != null) {
            for (i in 0 until attachments.length()) {
                val att = attachments.optJSONObject(i) ?: continue
                resourceFrom(att, sourceTitle, contentType)?.let(out::add)
            }
        } else {
            resourceFrom(item, sourceTitle, contentType)?.let(out::add)
        }
        val children = item.optJSONArray("children")
        if (children != null) for (i in 0 until children.length()) collectResources(children.optJSONObject(i), contentType, out)
    }

    private fun resourceFrom(obj: JSONObject, sourceTitle: String, contentType: String): KetangpaiResource? {
        val url = normalizeUrl(firstString(obj, "url", "fileurl", "fileUrl", "downloadurl", "downloadUrl", "ossurl", "ossUrl"))
        val name = firstString(obj, "name", "filename", "fileName", "title").ifBlank { sourceTitle }
        if (url.isBlank() && name.isBlank()) return null
        val id = firstString(obj, "id", "fileid", "fileId", "attachmentid", "attachmentId").ifBlank {
            sha256(url.ifBlank { "$name|$sourceTitle" }).take(24)
        }
        return KetangpaiResource(
            id = id,
            name = name.ifBlank { "课堂派资料" },
            url = url,
            size = firstString(obj, "size", "filesize", "fileSize"),
            contentType = contentType,
            sourceTitle = sourceTitle,
        )
    }

    private fun parseCourses(value: Any?): List<KetangpaiCourse> {
        val out = mutableListOf<KetangpaiCourse>()
        fun walk(v: Any?) {
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                is JSONObject -> {
                    val id = firstString(v, "id", "courseid", "courseId")
                    val name = firstString(v, "coursename", "courseName", "name", "title")
                    if (id.isNotBlank() && name.isNotBlank()) {
                        val teacher = firstString(v, "teacher", "teachername", "teacherName", "username")
                        out += KetangpaiCourse(id, name, teacher)
                        return
                    }
                    listOf("list", "courseList", "courses", "data").forEach { key -> v.opt(key)?.let(::walk) }
                }
            }
        }
        walk(value)
        return out
    }

    private fun post(path: String, payload: JSONObject, includeToken: Boolean = true): JSONObject {
        val request = Request.Builder()
            .url(BASE + path)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.ketangpai.com/")
            .header("Content-Type", "application/json")
            .apply { if (includeToken && token.isNotBlank()) header("Token", token) }
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("课堂派 HTTP ${response.code}")
            JSONObject(text)
        }
    }

    private fun success(json: JSONObject): Boolean = json.optInt("status", -1) == 1 || json.optInt("code", -1) == 10000
    private fun message(json: JSONObject): String = json.optString("message").ifBlank { json.optString("msg") }

    private fun firstString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.opt(key)
            if (value != null && value != JSONObject.NULL) {
                val text = value.toString().trim()
                if (text.isNotBlank() && text != "null") return text
            }
        }
        return ""
    }

    private fun normalizeUrl(raw: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> BASE + raw
        else -> raw.trim()
    }

    private fun encryptPassword(password: String): String {
        val key = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(AES_KEY.toByteArray(Charsets.UTF_8)))
        return Base64.encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val BASE = "https://openapiv5.ketangpai.com"
        private const val AES_KEY = "ktp4567890123456"
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES = 30
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152.0 Mobile Safari/537.36 ClassHelper/1.0"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
