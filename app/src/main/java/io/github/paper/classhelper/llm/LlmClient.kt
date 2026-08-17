package io.github.paper.classhelper.llm

import io.github.paper.classhelper.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import android.os.SystemClock

class LlmClient(private val settings: SettingsStore) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class Message(val role: String, val content: String)
    data class ConnectionTestResult(val latencyMs: Long, val model: String, val preview: String)

    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) { "LLM Base URL 无效" }
        return if (base.endsWith("/chat/completions", true)) base else "$base/chat/completions"
    }

    suspend fun stream(messages: List<Message>, onDelta: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val apiKey = settings.llmApiKey
        val url = endpoint(settings.llmBaseUrl)
        require(settings.llmModel.isNotBlank()) { "请先设置 LLM 模型名" }
        val body = JSONObject().apply {
            put("model", settings.llmModel)
            put("stream", true)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                messages.forEach { m -> put(JSONObject().put("role", m.role).put("content", m.content)) }
            })
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(body)
        if (apiKey.isNotBlank()) reqBuilder.header("Authorization", "Bearer $apiKey")
        val req = reqBuilder.build()

        val out = StringBuilder()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("LLM HTTP ${response.code}: ${response.body?.string()?.take(500)}")
            val responseBody = response.body ?: throw IOException("LLM 返回为空")
            val type = responseBody.contentType()?.toString().orEmpty()
            if (!type.contains("text/event-stream", ignoreCase = true)) {
                val raw = responseBody.string()
                val text = runCatching {
                    JSONObject(raw).getJSONArray("choices").optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content").orEmpty()
                }.getOrDefault("")
                if (text.isBlank()) throw IOException("LLM 返回格式不兼容：${raw.take(300)}")
                out.append(text); onDelta(text)
            } else {
                val source = responseBody.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    runCatching {
                        val deltaObj = JSONObject(payload).getJSONArray("choices").optJSONObject(0)?.optJSONObject("delta")
                        val delta = deltaObj?.optString("content").orEmpty()
                        if (delta.isNotEmpty()) { out.append(delta); onDelta(delta) }
                    }
                }
            }
        }
        out.toString().trim()
    }

    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        require(model.isNotBlank()) { "模型名不能为空" }
        val url = endpoint(baseUrl)
        val body = JSONObject().apply {
            put("model", model.trim())
            put("stream", false)
            put("temperature", 0)
            put("max_tokens", 16)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK only.")))
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val rb = Request.Builder().url(url).header("Accept", "application/json").post(body)
        if (apiKey.isNotBlank()) rb.header("Authorization", "Bearer ${apiKey.trim()}")
        val start = SystemClock.elapsedRealtime()
        client.newCall(rb.build()).execute().use { response ->
            val latency = SystemClock.elapsedRealtime() - start
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = when (response.code) {
                    401 -> "鉴权失败（401）：请检查 API Key"
                    403 -> "无访问权限（403）：请检查 API Key/模型权限"
                    404 -> "接口不存在（404）：请检查 Base URL，通常应到 /v1"
                    429 -> "请求受限（429）：额度不足或触发限流"
                    in 500..599 -> "服务端错误（${response.code}）"
                    else -> "HTTP ${response.code}"
                }
                throw IOException("$reason${raw.take(300).takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}")
            }
            val obj = runCatching { JSONObject(raw) }.getOrElse { throw IOException("返回不是 JSON：${raw.take(240)}") }
            val choices = obj.optJSONArray("choices") ?: throw IOException("返回格式不兼容：缺少 choices")
            val first = choices.optJSONObject(0) ?: throw IOException("返回格式不兼容：choices 为空")
            val text = first.optJSONObject("message")?.optString("content").orEmpty().trim()
            if (text.isBlank()) throw IOException("接口可连接，但没有返回可用文本")
            ConnectionTestResult(latency, obj.optString("model", model).ifBlank { model }, text.take(120))
        }
    }

    suspend fun complete(messages: List<Message>): String {
        val sb = StringBuilder()
        return stream(messages) { sb.append(it) }.ifBlank { sb.toString() }
    }
}
