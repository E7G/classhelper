package io.github.paper.classhelper.ocr

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Downloads the official RapidOCR PP-OCRv6 small ONNX detector/recognizer into app-private storage. */
class OcrModelManager(context: Context) {
    data class ModelFile(val name: String, val title: String, val url: String, val sha256: String, val approximateBytes: Long)
    sealed interface State {
        data object Missing : State
        data class Preparing(val message: String) : State
        data class Downloading(val fileName: String, val percent: Int, val downloaded: Long, val total: Long) : State
        data class Ready(val directory: File, val totalBytes: Long) : State
        data class Error(val message: String) : State
    }

    private val app = context.applicationContext
    private val root = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir, "ocr_models/$MODEL_ID")
    private val marker get() = File(root, ".verified-v1")
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val cancel = AtomicBoolean(false)
    @Volatile private var activeCall: Call? = null
    @Volatile private var state: State = State.Missing
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).followRedirects(true).followSslRedirects(true).build()

    val files = listOf(
        ModelFile(
            "PP-OCRv6_det_small.onnx", "PP-OCRv6 Small 文本检测",
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv6/det/PP-OCRv6_det_small.onnx",
            "090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f", 10L * MB
        ),
        ModelFile(
            "PP-OCRv6_rec_small.onnx", "PP-OCRv6 Small 中文识别",
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv6/rec/PP-OCRv6_rec_small.onnx",
            "6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884", 21L * MB
        )
    )

    init { root.mkdirs(); state = readyState() ?: if (hasPartial()) State.Error("上次 OCR 模型下载未完成，可继续下载") else State.Missing }

    fun currentState(): State = readyState() ?: state
    fun isReady(): Boolean = readyState() != null
    fun modelDirectory(): File? = readyState()?.directory
    fun modelBytes(): Long = files.sumOf { File(root, it.name).takeIf(File::isFile)?.length() ?: 0L }

    fun addListener(listener: (State) -> Unit) { listeners += listener; listener(currentState()) }
    fun removeListener(listener: (State) -> Unit) { listeners -= listener }
    fun refresh() { emit(readyState() ?: if (hasPartial()) State.Error("OCR 模型下载未完成，可继续下载") else State.Missing) }

    fun download() {
        readyState()?.let { emit(it); return }
        if (!running.compareAndSet(false, true)) return
        cancel.set(false)
        scope.launch {
            try {
                root.mkdirs()
                emit(State.Preparing("正在准备 PP-OCRv6 高精度 OCR 模型…"))
                files.forEachIndexed { index, spec ->
                    if (cancel.get()) throw Cancelled()
                    val final = File(root, spec.name)
                    if (verify(final, spec)) return@forEachIndexed
                    var last: Throwable? = null
                    for (url in sourceUrls(spec)) {
                        try { downloadOne(index, spec, url); last = null; break }
                        catch (t: Throwable) { if (t is Cancelled || cancel.get()) throw Cancelled(); last = t }
                    }
                    if (last != null) throw last
                    if (!verify(final, spec)) error("${spec.title} 校验失败")
                }
                marker.writeText(MODEL_ID)
                emit(readyState() ?: error("OCR 模型已下载，但完整性检查未通过"))
            } catch (_: Cancelled) {
                emit(if (hasPartial()) State.Error("OCR 模型下载已暂停，可继续下载") else State.Missing)
            } catch (t: Throwable) {
                emit(State.Error(t.message ?: "OCR 模型下载失败"))
            } finally { activeCall = null; running.set(false) }
        }
    }

    fun cancelDownload() { cancel.set(true); activeCall?.cancel() }
    fun delete(): Boolean {
        if (running.get()) return false
        activeCall?.cancel()
        root.listFiles()?.forEach { it.delete() }
        marker.delete()
        val ok = files.none { File(root, it.name).exists() || File(root, it.name + ".part").exists() }
        if (ok) emit(State.Missing)
        return ok
    }

    private fun downloadOne(index: Int, spec: ModelFile, url: String) {
        val final = File(root, spec.name)
        val part = File(root, spec.name + ".part")
        var existing = part.takeIf(File::isFile)?.length() ?: 0L
        val req = Request.Builder().url(url).header("User-Agent", "ClassHelperNative/1.7.0 Android")
            .header("Accept", "application/octet-stream,*/*")
            .apply { if (existing > 0L) header("Range", "bytes=$existing-") }.build()
        val call = http.newCall(req); activeCall = call
        call.execute().use { response ->
            if (response.code == 416) { part.delete(); existing = 0L; throw IllegalStateException("断点失效，已清理并准备重新下载") }
            if (!response.isSuccessful && response.code != 206) error("HTTP ${response.code}")
            val body = response.body ?: error("模型下载响应为空")
            if (body.contentType()?.toString()?.startsWith("text/html", true) == true) error("下载源返回网页而不是 ONNX 模型")
            val append = response.code == 206 && existing > 0
            if (!append) { part.delete(); existing = 0L }
            val content = body.contentLength().coerceAtLeast(0L)
            val fileTotal = if (content > 0) existing + content else spec.approximateBytes
            val doneBefore = files.take(index).sumOf { File(root, it.name).takeIf(File::isFile)?.length() ?: 0L }
            val totalAll = files.sumOf { it.approximateBytes }
            RandomAccessFile(part, "rw").use { out ->
                if (append) out.seek(existing) else out.setLength(0)
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024); var done = existing
                    while (true) {
                        if (cancel.get()) throw Cancelled()
                        val n = input.read(buffer); if (n < 0) break
                        out.write(buffer, 0, n); done += n
                        val overallDone = doneBefore + done
                        emit(State.Downloading(spec.title, ((overallDone * 100) / totalAll.coerceAtLeast(1)).toInt().coerceIn(0, 99), overallDone, totalAll))
                    }
                }
                out.fd.sync()
            }
        }
        if (!verify(part, spec)) { part.delete(); error("${spec.title} SHA-256 校验失败") }
        final.delete()
        if (!part.renameTo(final)) { part.copyTo(final, overwrite = true); part.delete() }
    }

    private fun sourceUrls(spec: ModelFile): List<String> = listOf(
        spec.url,
        spec.url.replace("https://www.modelscope.cn/", "https://modelscope.cn/")
    ).distinct()

    private fun readyState(): State.Ready? {
        // SHA-256 is intentionally paid only once after download/upgrade. Normal UI state checks
        // trust the app-private verification marker so opening Settings never hashes ~30 MB on main.
        val finals = files.map { File(root, it.name) }
        if (marker.isFile && finals.all { it.isFile && it.length() > 1_000_000L }) {
            return State.Ready(root, finals.sumOf { it.length() })
        }
        if (!files.all { verify(File(root, it.name), it) }) return null
        runCatching { marker.writeText(MODEL_ID) }
        return State.Ready(root, finals.sumOf { it.length() })
    }
    private fun hasPartial() = files.any { File(root, it.name + ".part").exists() }
    private fun verify(file: File, spec: ModelFile): Boolean = file.isFile && file.length() > 1_000_000L && runCatching { sha256(file).equals(spec.sha256, true) }.getOrDefault(false)
    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val b = ByteArray(256 * 1024); while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun emit(s: State) { state = s; listeners.forEach { runCatching { it(s) } } }
    private class Cancelled : RuntimeException()
    companion object { const val MODEL_ID = "rapidocr-ppocrv6-small-v3.9.2"; private const val MB = 1024L * 1024L }
}
