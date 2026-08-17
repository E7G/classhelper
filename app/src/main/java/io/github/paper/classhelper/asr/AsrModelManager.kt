package io.github.paper.classhelper.asr

import android.app.DownloadManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import io.github.paper.classhelper.classroom.AsrModelDownloadService
import io.github.paper.classhelper.classroom.AsrModelDownloadJobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-owned SenseVoiceSmall INT8 model manager.
 *
 * v1.6.2 keeps the local ASR payload as SenseVoiceSmall INT8 (2024-07-17) + Silero VAD.
 * Android 14+ keeps the user-initiated data-transfer job path; Android 13 and below keep the
 * sequential dataSync foreground-service fallback. Bytes are transferred one file at a time with
 * OkHttp + HTTP Range resume into app-private storage.
 */
class AsrModelManager(context: Context) {
    data class Source(val name: String, val url: String)

    data class ModelFileSpec(
        val fileName: String,
        val displayName: String,
        val approximateBytes: Long,
        val minimumBytes: Long,
        val sources: List<Source>,
        val expectedBytes: Long? = null,
        val expectedSha256: String? = null
    )

    data class ModelSpec(
        val id: String,
        val displayName: String,
        val description: String,
        val files: List<ModelFileSpec>
    ) {
        val approximateBytes: Long get() = files.sumOf { it.approximateBytes }
    }

    sealed interface State {
        data object Missing : State
        data class Preparing(val message: String) : State
        data class Downloading(
            val fileIndex: Int,
            val fileCount: Int,
            val fileName: String,
            val source: String,
            val filePercent: Int,
            val overallPercent: Int,
            val downloaded: Long,
            val total: Long
        ) : State
        data class Ready(val directory: File, val totalBytes: Long) : State
        data class Error(val message: String) : State
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val running = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var activeCall: Call? = null
    @Volatile private var state: State = State.Missing

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val senseVoiceHfBase =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
    private val senseVoiceMirrorBase =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"

    private fun senseVoiceSources(fileName: String): List<Source> = listOf(
        Source("Hugging Face", "$senseVoiceHfBase/$fileName?download=true"),
        Source("HF Mirror", "$senseVoiceMirrorBase/$fileName")
    )

    val model = ModelSpec(
        id = "sensevoice-small-int8-2024-07-17",
        displayName = "SenseVoiceSmall INT8 · 中英日韩粤",
        description = "本地多语言识别 · ITN/标点 · 下载一次后无需 ASR 配置",
        files = listOf(
            ModelFileSpec(
                "model.int8.onnx", "SenseVoiceSmall INT8", 239_233_841L, 220_000_000L,
                senseVoiceSources("model.int8.onnx"),
                expectedBytes = 239_233_841L
            ),
            ModelFileSpec(
                "tokens.txt", "SenseVoice 词表", 315_894L, 250_000L,
                senseVoiceSources("tokens.txt"),
                expectedBytes = 315_894L,
                expectedSha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
            ),
            ModelFileSpec(
                "silero_vad.onnx", "Silero VAD", 644_000L, 500_000L,
                listOf(
                    Source("sherpa-onnx 官方 VAD", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"),
                    Source("ModelScope VAD", "https://modelscope.cn/models/xnnehang/k2-fsa-silero-vad/resolve/master/silero_vad.onnx")
                )
            )
        )
    )


    // Keep all downloaded ASR payloads under one app-private root; model.id isolates model generations.
    private val externalDownloads = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    private val externalRootDir = externalDownloads?.let { File(it, "asr_models") }
    private val fallbackRootDir = File(appContext.filesDir, "asr_models")
    private val modelDir = File(externalRootDir ?: fallbackRootDir, model.id)
    private val verificationMarker get() = File(modelDir, ".verified-v8-sensevoice-token-fix")

    init {
        modelDir.mkdirs()
        migrateLegacySystemDownloads()
        cleanupLegacyModels()
        state = readyState() ?: if (hasPartialFiles()) {
            State.Error("上次模型下载未完成，可点击继续下载")
        } else State.Missing
    }

    fun isReady(): Boolean = readyState() != null
    fun isDownloading(): Boolean = running.get()
    fun currentState(): State = readyState() ?: state
    fun modelDirectory(): File? = readyState()?.directory

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        listener(currentState())
        refresh()
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun refresh() {
        scope.launch {
            readyState()?.let { emit(it); return@launch }
            if (!running.get()) {
                emit(if (hasPartialFiles()) State.Error("模型下载未完成，可点击继续下载") else State.Missing)
            }
        }
    }

    /**
     * Schedules the platform-recommended user-initiated transfer on Android 14+, falling back to the
     * legacy foreground downloader only on older releases. No network/file IO runs on the UI thread.
     */
    fun download() {
        readyState()?.let { emit(it); return }
        val usable = (externalRootDir ?: fallbackRootDir).usableSpace
        val required = 340L * 1024L * 1024L
        if (usable in 1 until required) {
            emit(State.Error("存储空间不足：模型约 230 MB，建议至少保留 340 MB；当前可用约 ${usable / 1024 / 1024} MB"))
            return
        }
        emit(State.Preparing(if (hasPartialFiles()) "正在从断点继续模型下载…" else "正在启动后台模型下载…"))
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val result = runCatching { AsrModelDownloadJobService.schedule(appContext, model.approximateBytes) }
            result.onFailure { emit(State.Error("无法启动系统后台下载任务：${it.message ?: it.javaClass.simpleName}")) }
            if (result.getOrDefault(JobScheduler.RESULT_FAILURE) != JobScheduler.RESULT_SUCCESS) {
                emit(State.Error("系统拒绝启动后台下载任务，请保持应用在前台后重试"))
            }
        } else {
            val intent = Intent(appContext, AsrModelDownloadService::class.java).setAction(AsrModelDownloadService.ACTION_START)
            runCatching { ContextCompat.startForegroundService(appContext, intent) }
                .onFailure { emit(State.Error("无法启动兼容下载服务：${it.message ?: it.javaClass.simpleName}")) }
        }
    }

    fun cancelDownload() {
        cancelRequested.set(true)
        activeCall?.cancel()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            runCatching { appContext.getSystemService(JobScheduler::class.java)?.cancel(AsrModelDownloadJobService.JOB_ID) }
        } else {
            runCatching { appContext.stopService(Intent(appContext, AsrModelDownloadService::class.java)) }
        }
        if (!running.get()) emit(readyState() ?: if (hasPartialFiles()) State.Error("下载已暂停，点击可继续") else State.Missing)
    }

    internal fun cancelFromService() {
        cancelRequested.set(true)
        activeCall?.cancel()
    }

    fun delete(): Boolean {
        if (running.get()) return false
        cancelRequested.set(true)
        activeCall?.cancel()
        model.files.forEach { spec ->
            finalFile(spec).delete()
            partFile(spec).delete()
            legacyDownloadFile(spec).delete()
        }
        verificationMarker.delete()
        val ok = model.files.none { finalFile(it).exists() || partFile(it).exists() }
        if (ok) emit(State.Missing)
        return ok
    }

    /** Called only from [AsrModelDownloadService]'s single worker thread. */
    internal fun performDownload(onState: (State) -> Unit): State {
        readyState()?.let { emit(it); onState(it); return it }
        if (!running.compareAndSet(false, true)) return currentState()
        cancelRequested.set(false)
        try {
            modelDir.mkdirs()
            model.files.forEachIndexed { index, spec ->
                if (cancelRequested.get()) throw DownloadCancelled()
                val final = finalFile(spec)
                if (validateFile(final, spec)) return@forEachIndexed
                final.parentFile?.mkdirs()

                var lastError: Throwable? = null
                val errors = mutableListOf<String>()
                for (source in spec.sources) {
                    if (cancelRequested.get()) throw DownloadCancelled()
                    val prep = State.Preparing("正在连接 ${source.name} · ${spec.displayName}")
                    emit(prep); onState(prep)
                    try {
                        downloadOne(index, spec, source) { progress -> emit(progress); onState(progress) }
                        val part = partFile(spec)
                        if (!validateFile(part, spec)) error("${spec.displayName} 文件校验失败")
                        if (final.exists()) final.delete()
                        final.parentFile?.mkdirs()
                        if (!part.renameTo(final)) {
                            part.copyTo(final, overwrite = true)
                            part.delete()
                        }
                        if (!validateFile(final, spec)) error("${spec.displayName} 落盘校验失败")
                        lastError = null
                        break
                    } catch (t: Throwable) {
                        if (t is DownloadCancelled || cancelRequested.get()) throw DownloadCancelled()
                        lastError = t
                        errors += "${source.name}: ${t.message ?: t.javaClass.simpleName}"
                        activeCall = null
                    }
                }
                lastError?.let { error("${spec.displayName} 下载失败；${errors.joinToString("；")}") }
            }

            if (!model.files.all { validateFile(finalFile(it), it) }) error("模型下载完成但完整性检查未通过")
            verificationMarker.parentFile?.mkdirs()
            verificationMarker.writeText(model.id)
            val ready = readyState() ?: error("模型文件已完成，但启用失败")
            emit(ready); onState(ready)
            return ready
        } catch (_: DownloadCancelled) {
            val paused = readyState() ?: State.Error("下载已暂停，点击可继续")
            emit(paused); onState(paused)
            return paused
        } catch (t: Throwable) {
            val err = State.Error(t.message ?: "模型下载失败")
            emit(err); onState(err)
            return err
        } finally {
            activeCall = null
            running.set(false)
        }
    }

    private fun downloadOne(
        index: Int,
        spec: ModelFileSpec,
        source: Source,
        onProgress: (State.Downloading) -> Unit
    ) {
        val part = partFile(spec)
        part.parentFile?.mkdirs()
        var existing = part.length().coerceAtLeast(0L)
        // A corrupt HTML/error response can be larger than small tokenizer files. Let validation at
        // completion reject it; only discard clearly impossible oversized partials here.
        val maxReasonable = maxOf(spec.approximateBytes * 2, spec.minimumBytes * 3)
        if (existing > maxReasonable) {
            part.delete(); existing = 0L
        }

        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "ClassHelperNative/1.6.2 Android")
            .header("Accept", "application/octet-stream,*/*")
            .apply { if (existing > 0L) header("Range", "bytes=$existing-") }
            .build()
        val call = client.newCall(request)
        activeCall = call
        call.execute().use { response ->
            if (response.code == 416) {
                if (validateFile(part, spec)) return
                // The remote object and our resume offset disagree. Do not carry a bad partial
                // into the next mirror; restart cleanly there.
                part.delete()
                error("断点已失效，已自动清理并切换下载源")
            }
            if (!response.isSuccessful && response.code != 206) error("HTTP ${response.code}")
            val body = response.body ?: error("下载响应为空")
            val type = body.contentType()?.toString().orEmpty()
            if (type.startsWith("text/html", ignoreCase = true)) error("服务器返回网页而不是模型文件")

            val append = existing > 0L && response.code == 206
            val base = if (append) existing else 0L
            if (!append && part.exists()) part.delete()
            val contentLength = body.contentLength().coerceAtLeast(0L)
            val fileTotal = if (contentLength > 0L) base + contentLength else spec.approximateBytes
            val completedBefore = model.files.take(index).sumOf { previous ->
                finalFile(previous).takeIf { validateFile(it, previous) }?.length() ?: 0L
            }
            val wholeTotal = model.approximateBytes

            RandomAccessFile(part, "rw").use { out ->
                if (append) out.seek(base) else out.setLength(0L)
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var done = base
                    var lastOverall = -1
                    while (true) {
                        if (cancelRequested.get() || Thread.currentThread().isInterrupted) throw DownloadCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        val filePercent = ((done * 100L) / fileTotal.coerceAtLeast(1L)).toInt().coerceIn(0, 99)
                        val wholeDone = (completedBefore + done).coerceAtMost(wholeTotal)
                        val overall = ((wholeDone * 100L) / wholeTotal.coerceAtLeast(1L)).toInt().coerceIn(0, 99)
                        if (overall != lastOverall) {
                            lastOverall = overall
                            onProgress(
                                State.Downloading(
                                    index + 1,
                                    model.files.size,
                                    spec.displayName,
                                    source.name,
                                    filePercent,
                                    overall,
                                    wholeDone,
                                    wholeTotal
                                )
                            )
                        }
                    }
                }
                out.fd.sync()
            }
        }
    }

    private fun readyState(): State.Ready? {
        if (!model.files.all { validateFile(finalFile(it), it) }) return null
        if (!verificationMarker.isFile) runCatching { verificationMarker.writeText(model.id) }
        return State.Ready(modelDir, model.files.sumOf { finalFile(it).length() })
    }

    private fun validateFile(file: File, spec: ModelFileSpec): Boolean {
        if (!file.isFile || file.length() < spec.minimumBytes) return false
        spec.expectedBytes?.let { if (file.length() != it) return false }

        val contentLooksValid = runCatching {
            file.inputStream().buffered().use { input ->
                val head = ByteArray(256)
                val n = input.read(head)
                if (n <= 0) return@use false
                val prefix = head.copyOf(n).toString(Charsets.UTF_8).trimStart().lowercase()
                if (prefix.startsWith("<html") || prefix.startsWith("<!doctype")) return@use false
                when {
                    spec.fileName.endsWith(".json") -> prefix.startsWith("{") || prefix.startsWith("[")
                    // sherpa-onnx token tables legitimately contain special tokens such as
                    // <blk>, <unk>, <s> ... . Rejecting every TXT file that begins with '<'
                    // made a correctly downloaded SenseVoice tokens.txt fail validation forever.
                    spec.fileName.endsWith(".txt") -> !prefix.startsWith("{") && !prefix.startsWith("[")
                    else -> !prefix.startsWith("{") && !prefix.startsWith("[") && !prefix.startsWith("<")
                }
            }
        }.getOrDefault(false)
        if (!contentLooksValid) return false

        // Hash only the tiny tokenizer file. Hashing the 239 MB ONNX file from every UI refresh
        // would waste CPU/battery; the pinned ONNX byte length still catches truncated/error files.
        spec.expectedSha256?.let { expected ->
            val actual = runCatching { sha256(file) }.getOrNull() ?: return false
            if (!actual.equals(expected, ignoreCase = true)) return false
        }
        return true
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun finalFile(spec: ModelFileSpec) = File(modelDir, spec.fileName)
    private fun partFile(spec: ModelFileSpec) = File(modelDir, spec.fileName + ".part")
    private fun legacyDownloadFile(spec: ModelFileSpec) = File(modelDir, spec.fileName + ".download")
    private fun hasPartialFiles() = model.files.any { partFile(it).isFile && partFile(it).length() > 0L }

    /**
     * Older builds used DownloadManager. Best-effort cleanup is retained so stale system tasks cannot
     * keep downloading obsolete ASR payloads after an upgrade.
     */
    private fun migrateLegacySystemDownloads() {
        runCatching {
            val oldPrefs = appContext.getSharedPreferences("asr_model_downloads_v5_qwen3", Context.MODE_PRIVATE)
            val dm = appContext.getSystemService(DownloadManager::class.java)
            // Cancel every persisted Qwen DownloadManager task, not only filenames that happen to
            // overlap with the new SenseVoice payload. This prevents an obsolete ~1 GB model from
            // continuing to download after an upgrade.
            oldPrefs.all.values.filterIsInstance<Long>().filter { it >= 0L }.forEach { id ->
                runCatching { dm?.remove(id) }
            }
            model.files.forEach { spec ->
                val key = "id_" + spec.fileName.replace('.', '_').replace('/', '_')
                val id = oldPrefs.getLong(key, -1L)
                if (id >= 0L) runCatching { dm?.remove(id) }

                val old = legacyDownloadFile(spec)
                if (!old.isFile) return@forEach
                val final = finalFile(spec)
                if (validateFile(old, spec)) {
                    final.parentFile?.mkdirs()
                    if (final.exists()) final.delete()
                    if (!old.renameTo(final)) { old.copyTo(final, overwrite = true); old.delete() }
                } else {
                    val part = partFile(spec)
                    part.parentFile?.mkdirs()
                    if (!part.exists() || old.length() > part.length()) {
                        if (part.exists()) part.delete()
                        if (!old.renameTo(part)) { old.copyTo(part, overwrite = true); old.delete() }
                    } else old.delete()
                }
            }
            oldPrefs.edit().clear().apply()
        }
    }

    private fun cleanupLegacyModels() {
        val root = modelDir.parentFile ?: return
        listOf(
            File(root, "streaming-paraformer-bilingual-zh-en-int8"),
            File(root, "qwen3-asr-0.6b-int8-2026-03-25"),
            File(appContext.filesDir, "asr_models/streaming-paraformer-bilingual-zh-en-int8"),
            File(appContext.filesDir, "asr_models/qwen3-asr-0.6b-int8-2026-03-25")
        ).forEach { old ->
            if (old.absolutePath != modelDir.absolutePath) runCatching { old.deleteRecursively() }
        }
        listOf("ggml-base-q5_1.bin", "ggml-base-q5_1.bin.part").forEach { File(root, it).delete() }
    }

    internal fun reportState(newState: State) = emit(newState)

    private fun emit(newState: State) {
        state = newState
        listeners.forEach { listener -> runCatching { listener(newState) } }
    }

    private class DownloadCancelled : RuntimeException()
}
