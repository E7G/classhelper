package io.github.paper.classhelper.chaoxing

import android.util.Base64
import io.github.paper.classhelper.SettingsStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Read-only Chaoxing/Xuexitong client for resources the signed-in student can normally access.
 * Credentials are only sent to passport2. At rest, the password/cookie are stored by SecretStore.
 */
class ChaoxingClient(private val settings: SettingsStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun login(username: String, password: String, remember: Boolean = true): String? = withContext(Dispatchers.IO) {
        val user = username.trim()
        require(user.isNotBlank()) { "请输入学习通账号" }
        require(password.isNotBlank()) { "请输入学习通密码" }
        val result = loginInternal(user, password)
        if (remember) {
            settings.chaoxingUsername = user
            settings.chaoxingPassword = password
        }
        result
    }

    /** Validate the cookie and transparently re-login with the encrypted saved password if needed. */
    suspend fun ensureSession(): Boolean = withContext(Dispatchers.IO) {
        val cookie = settings.chaoxingCookie
        if (cookie.isNotBlank() && validateCookie(cookie)) return@withContext true
        val user = settings.chaoxingUsername
        val password = settings.chaoxingPassword
        if (user.isBlank() || password.isBlank()) return@withContext false
        runCatching { loginInternal(user, password) }.isSuccess
    }

    suspend fun accountName(): String? = withContext(Dispatchers.IO) {
        if (!ensureSession()) return@withContext null
        accountName(settings.chaoxingCookie)
    }

    suspend fun listCourses(): List<ChaoxingCourse> = withContext(Dispatchers.IO) {
        require(ensureSession()) { "学习通登录已失效，请重新登录" }
        val merged = LinkedHashMap<String, ChaoxingCourse>()
        val folders = linkedSetOf("0").apply { addAll(courseFolderIds()) }
        for (folder in folders) {
            val body = FormBody.Builder()
                .add("courseType", "1")
                .add("courseFolderId", folder)
                .add("query", "")
                .add("superstarClass", "0")
                .build()
            val request = authBuilder(COURSE_LIST_DATA)
                .header("Referer", COURSE_LIST_REFERER)
                .post(body)
                .build()
            val html = try { executeText(request) } catch (_: Throwable) { continue }
            parseCourses(html).forEach { merged["${it.courseId}:${it.classId}"] = it }
        }

        if (merged.isEmpty()) {
            val oldHtml = runCatching { getText(COURSE_LIST_OLD) }.getOrDefault("")
            parseCourses(oldHtml).forEach { merged["${it.courseId}:${it.classId}"] = it }
        }
        if (merged.isEmpty()) error("没有读取到课程；请确认课程对当前账号可见")
        merged.values.toList()
    }

    suspend fun listChapters(course: ChaoxingCourse): List<ChaoxingChapter> = withContext(Dispatchers.IO) {
        require(ensureSession()) { "学习通登录已失效，请重新登录" }
        val cpi = course.cpi.ifBlank { course.url.toHttpUrlOrNull()?.queryParameter("cpi").orEmpty() }
        val url = "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse".toHttpUrl().newBuilder()
            .addQueryParameter("courseid", course.courseId)
            .addQueryParameter("clazzid", course.classId)
            .addQueryParameter("cpi", cpi)
            .addQueryParameter("ut", "s")
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        val doc = Jsoup.parse(getText(url.toString()))
        val out = LinkedHashMap<String, ChaoxingChapter>()
        for (a in doc.select("a.clicktitle, a[chapterid]")) {
            val li = a.closestAncestor("li") ?: continue
            val knowledgeId = li.selectFirst("div.inputCheck input[value]")?.attr("value").orEmpty().trim()
                .ifBlank { a.attr("chapterid").trim() }
            if (knowledgeId.isBlank()) continue
            val title = a.text().trim().ifBlank { "章节 $knowledgeId" }
            val depth = a.parents().count { it.tagName() == "li" }.minus(1).coerceAtLeast(0)
            out[knowledgeId] = ChaoxingChapter(knowledgeId, title, depth)
        }
        out.values.toList()
    }

    suspend fun cardCount(course: ChaoxingCourse, chapter: ChaoxingChapter): Int = withContext(Dispatchers.IO) {
        require(ensureSession()) { "学习通登录已失效，请重新登录" }
        val url = "https://mooc1.chaoxing.com/mycourse/studentstudyAjax".toHttpUrl().newBuilder()
            .addQueryParameter("courseId", course.courseId)
            .addQueryParameter("clazzid", course.classId)
            .addQueryParameter("chapterId", chapter.knowledgeId)
            .addQueryParameter("cpi", course.cpi)
            .addQueryParameter("verificationcode", "")
            .addQueryParameter("mooc2", "1")
            .build()
        val doc = Jsoup.parse(getText(url.toString()))
        doc.selectFirst("input#cardcount")?.attr("value")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    suspend fun loadCard(course: ChaoxingCourse, chapter: ChaoxingChapter, page: Int): JSONObject? = withContext(Dispatchers.IO) {
        require(ensureSession()) { "学习通登录已失效，请重新登录" }
        val url = "https://mooc1.chaoxing.com/knowledge/cards".toHttpUrl().newBuilder()
            .addQueryParameter("clazzid", course.classId)
            .addQueryParameter("courseid", course.courseId)
            .addQueryParameter("knowledgeid", chapter.knowledgeId)
            .addQueryParameter("num", page.toString())
            .addQueryParameter("ut", "s")
            .addQueryParameter("cpi", course.cpi)
            .addQueryParameter("v", "20160407-1")
            .build()
        val html = getText(url.toString())
        val script = Jsoup.parse(html).select("script").asSequence().map { it.data() }
            .firstOrNull { it.contains("mArg") } ?: return@withContext null
        extractJsonObject(script, "mArg")?.let(::JSONObject)
    }

    suspend fun resolveResource(objectId: String): ChaoxingResource? = withContext(Dispatchers.IO) {
        if (objectId.isBlank()) return@withContext null
        require(ensureSession()) { "学习通登录已失效，请重新登录" }
        val url = "https://mooc1-2.chaoxing.com/ananas/status/$objectId".toHttpUrl().newBuilder()
            .addQueryParameter("_dc", System.currentTimeMillis().toString())
            .build()
        val request = authBuilder(url.toString())
            .header("Referer", "https://mooc1-2.chaoxing.com/ananas/modules/pdf/index.html")
            .header("X-Requested-With", "XMLHttpRequest")
            .get().build()
        val json = runCatching { JSONObject(executeText(request)) }.getOrNull() ?: return@withContext null
        val filename = json.optString("filename").ifBlank { objectId }
        val pdf = json.optString("pdf").takeIf { it.startsWith("http") }
        val source = sequenceOf(json.optString("httphd"), json.optString("http"), pdf.orEmpty())
            .firstOrNull { it.startsWith("http") }
        ChaoxingResource(
            type = if (pdf != null) "document" else "media",
            name = filename,
            objectId = objectId,
            pageCount = json.optInt("pagenum", -1).takeIf { it >= 0 },
            sourceUrl = source,
            pdfUrl = pdf,
        )
    }

    fun logout(clearCredentials: Boolean = true) {
        settings.chaoxingCookie = ""
        if (clearCredentials) {
            settings.chaoxingUsername = ""
            settings.chaoxingPassword = ""
        }
        settings.chaoxingCourseId = ""
        settings.chaoxingClassId = ""
        settings.chaoxingCpi = ""
        settings.chaoxingCourseName = ""
        settings.chaoxingCourseDocumentId = null
        settings.chaoxingLastSync = 0L
    }

    private fun loginInternal(user: String, password: String): String? {
        val attempts = listOf(
            FormBody.Builder()
                .add("uname", user)
                .add("password", encryptDes(password))
                .add("fid", "-1")
                .add("t", "true")
                .add("refer", "https://i.chaoxing.com")
                .add("forbidotherlogin", "0")
                .add("validate", "")
                .build(),
            FormBody.Builder()
                .add("uname", encryptAes(user))
                .add("password", encryptAes(password))
                .add("fid", "-1")
                .add("t", "true")
                .add("refer", "https://i.chaoxing.com")
                .add("forbidotherlogin", "0")
                .add("validate", "")
                .add("doubleFactorLogin", "0")
                .add("independentId", "0")
                .build(),
        )

        var lastMessage = "登录失败"
        for (body in attempts) {
            val request = Request.Builder()
                .url(API_LOGIN)
                .post(body)
                .pcHeaders()
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", "https://passport2.chaoxing.com")
                .header("Referer", "https://passport2.chaoxing.com/login")
                .build()
            val response = try { http.newCall(request).execute() } catch (t: Throwable) {
                lastMessage = t.message ?: "网络请求失败"
                continue
            }
            response.use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    lastMessage = "HTTP ${r.code}"
                    return@use
                }
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (json?.optBoolean("status", false) == true) {
                    val cookie = r.headers.values("Set-Cookie")
                        .map { it.substringBefore(';').trim() }
                        .filter { '=' in it }
                        .distinctBy { it.substringBefore('=') }
                        .joinToString("; ")
                    if (cookie.isBlank()) {
                        lastMessage = "登录成功但未取得会话 Cookie"
                        return@use
                    }
                    settings.chaoxingCookie = cookie
                    return accountName(cookie) ?: user
                }
                lastMessage = json?.optString("msg2").orEmpty()
                    .ifBlank { json?.optString("msg").orEmpty() }
                    .ifBlank { json?.optString("message").orEmpty() }
                    .ifBlank { lastMessage }
            }
        }
        error(lastMessage)
    }

    private fun courseFolderIds(): List<String> {
        val html = runCatching { getText(COURSE_INTERACTION) }.getOrDefault("")
        val out = LinkedHashSet<String>()
        listOf(
            Regex("(?i)fileid=[\"'](\\d+)[\"']"),
            Regex("(?i)data-fileid=[\"'](\\d+)[\"']"),
            Regex("(?i)courseFolderId[\"']?\\s*[:=]\\s*[\"']?(\\d+)")
        ).forEach { rx -> rx.findAll(html).forEach { out += it.groupValues[1] } }
        return out.toList()
    }

    private fun parseCourses(content: String): List<ChaoxingCourse> {
        if (content.isBlank()) return emptyList()
        val doc = Jsoup.parse(content)
        val out = LinkedHashMap<String, ChaoxingCourse>()
        // Current Chaoxing course-list HTML uses div.course with the real title in
        // span.course-name[title]. Parse that structure first so UI never has to expose numeric IDs.
        for (course in doc.select("div.course")) {
            if (course.selectFirst("a.not-open-tip, div.not-open-tip") != null) continue
            val courseId = course.selectFirst("input.courseId, input[name=courseId], input[name=courseid]")
                ?.attr("value").orEmpty().trim()
            val classId = course.selectFirst("input.clazzId, input[name=clazzId], input[name=classId], input[name=clazzid]")
                ?.attr("value").orEmpty().trim()
            if (courseId.isBlank() || classId.isBlank()) continue
            val link = course.selectFirst("a[href*=cpi], a[href]")
            val rawUrl = link?.absUrl("href").takeUnless { it.isNullOrBlank() } ?: link?.attr("href").orEmpty()
            val name = course.selectFirst("span.course-name[title], span.course-name, .course-name[title], .course-name")
                ?.let { it.attr("title").ifBlank { it.text() } }?.trim().orEmpty()
                .ifBlank { "未命名课程" }
            val cpi = rawUrl.toHttpUrlOrNull()?.queryParameter("cpi").orEmpty().ifBlank {
                course.selectFirst("input[name=cpi]")?.attr("value").orEmpty()
            }
            val teacher = course.selectFirst("p.color3[title], .teacher[title], .teacher")?.let {
                it.attr("title").ifBlank { it.text() }
            }.orEmpty().trim()
            val classroom = course.select("p,span").firstOrNull { it.text().contains("班级") }
                ?.text()?.substringAfter("班级：", "")?.substringAfter("班级:", "")?.trim().orEmpty()
            out["$courseId:$classId"] = ChaoxingCourse(courseId, classId, cpi, name, teacher, classroom, rawUrl)
        }
        val inputs = doc.select("input.courseId, input[name=courseId], input[name=courseid]")
        for (courseInput in inputs) {
            val courseId = courseInput.attr("value").trim()
            if (courseId.isBlank()) continue
            val container = courseInput.parents().firstOrNull { parent ->
                parent.selectFirst("input.clazzId, input[name=clazzId], input[name=classId], input[name=clazzid]") != null
            } ?: courseInput.parent() ?: continue
            val classId = container.selectFirst("input.clazzId, input[name=clazzId], input[name=classId], input[name=clazzid]")
                ?.attr("value").orEmpty().trim()
            if (classId.isBlank()) continue
            val link = container.select("a[href]").firstOrNull { a ->
                val href = a.attr("href")
                href.contains("course", true) || href.contains("clazz", true)
            } ?: container.selectFirst("a[href]")
            val rawUrl = link?.absUrl("href").takeUnless { it.isNullOrBlank() } ?: link?.attr("href").orEmpty()
            val name = container.selectFirst(".course-name[title], .course-name, h3 span[title], h3 a[title], h3, h4")
                ?.let { it.attr("title").ifBlank { it.text() } }?.trim().orEmpty()
                .ifBlank { link?.attr("title").orEmpty().ifBlank { link?.text().orEmpty() } }
                .ifBlank { "未命名课程" }
            val cpi = rawUrl.toHttpUrlOrNull()?.queryParameter("cpi").orEmpty().ifBlank {
                container.selectFirst("input[name=cpi]")?.attr("value").orEmpty()
            }
            val teacher = container.selectFirst("[data-teacher], .teacher, p.line2[title]")?.let {
                it.attr("data-teacher").ifBlank { it.attr("title") }.ifBlank { it.text() }
            }.orEmpty().trim()
            val classroom = container.select("p,span").firstOrNull { it.text().contains("班级") }
                ?.text()?.substringAfter("班级：", "")?.substringAfter("班级:", "")?.trim().orEmpty()
            out["$courseId:$classId"] = ChaoxingCourse(courseId, classId, cpi, name, teacher, classroom, rawUrl)
        }

        if (out.isEmpty()) {
            val pair = Regex("(?is)(?:courseId|courseid)[\"']?\\s*[:=]\\s*[\"']?(\\d+).{0,260}?(?:clazzId|classId|clazzid)[\"']?\\s*[:=]\\s*[\"']?(\\d+)")
            pair.findAll(content).forEach { m ->
                val courseId = m.groupValues[1]
                val classId = m.groupValues[2]
                val start = (m.range.first - 400).coerceAtLeast(0)
                val end = (m.range.last + 1200).coerceAtMost(content.lastIndex)
                val snippet = if (end > start) content.substring(start, end + 1) else m.value
                val sdoc = Jsoup.parse(snippet)
                val name = sdoc.selectFirst("[title],h3,h4")?.let { it.attr("title").ifBlank { it.text() } }
                    ?.trim().orEmpty().ifBlank { "未命名课程" }
                val href = sdoc.selectFirst("a[href]")?.attr("href").orEmpty()
                val cpi = href.toHttpUrlOrNull()?.queryParameter("cpi").orEmpty()
                out["$courseId:$classId"] = ChaoxingCourse(courseId, classId, cpi, name, "", "", href)
            }
        }
        return out.values.toList()
    }

    private fun validateCookie(cookie: String): Boolean {
        val body = FormBody.Builder()
            .add("courseType", "1")
            .add("courseFolderId", "0")
            .add("query", "")
            .add("superstarClass", "0")
            .build()
        val request = Request.Builder().url(COURSE_LIST_DATA).post(body).pcHeaders()
            .header("Referer", COURSE_LIST_REFERER)
            .header("Cookie", cookie).build()
        return runCatching {
            http.newBuilder().followRedirects(false).build().newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                response.code == 200 && !text.contains("passport2.chaoxing.com", true)
            }
        }.getOrDefault(false)
    }

    private fun accountName(cookie: String): String? {
        val request = Request.Builder().url(API_ACCOUNT).get().pcHeaders()
            .header("Referer", "https://i.chaoxing.com/")
            .header("Cookie", cookie).build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty()).optJSONObject("msg")?.optString("name")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun getText(url: String): String = executeText(authBuilder(url).get().build())

    private fun authBuilder(url: String): Request.Builder {
        val cookie = settings.chaoxingCookie
        require(cookie.isNotBlank()) { "尚未登录学习通" }
        return Request.Builder().url(url).pcHeaders().header("Cookie", cookie)
    }

    private fun executeText(request: Request): String = http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("学习通请求失败：HTTP ${response.code}")
        response.body?.string().orEmpty()
    }

    private fun Request.Builder.pcHeaders(): Request.Builder =
        header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")

    private fun Element.closestAncestor(tag: String): Element? {
        var node: Element? = this
        while (node != null) {
            if (node.tagName().equals(tag, true)) return node
            node = node.parent()
        }
        return null
    }

    private fun extractJsonObject(text: String, marker: String): String? {
        val markerIndex = text.indexOf(marker)
        if (markerIndex < 0) return null
        val start = text.indexOf('{', markerIndex)
        if (start < 0) return null
        var depth = 0
        var quote = '\u0000'
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (quote != '\u0000') {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == quote) quote = '\u0000'
                continue
            }
            if (ch == '\'' || ch == '"') { quote = ch; continue }
            if (ch == '{') depth++
            if (ch == '}') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun encryptDes(value: String): String {
        val key = "u2oh6Vu^".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun encryptAes(value: String): String {
        val key = "u2oh6Vu^HWe4_AES".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        return Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152.0 Mobile Safari/537.36 ClassHelper/1.0"
        private const val API_LOGIN = "https://passport2.chaoxing.com/fanyalogin"
        private const val API_ACCOUNT = "https://sso.chaoxing.com/apis/login/userLogin4Uname.do"
        private const val COURSE_LIST_DATA = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/courselistdata"
        private const val COURSE_LIST_OLD = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/courses/list"
        private const val COURSE_INTERACTION = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction"
        private const val COURSE_LIST_REFERER = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction?moocDomain=https://mooc1-1.chaoxing.com/mooc-ans"
    }
}
