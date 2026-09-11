package io.github.paper.classhelper.asr

import android.os.Process
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loss-resistant streaming Chinese ASR based on sherpa-onnx Zipformer Transducer INT8.
 *
 * Design goals:
 * 1. The AudioRecord thread never waits for neural decoding. PCM first enters a fixed memory FIFO;
 *    if decoding ever falls far behind, overflow is appended in-order to a tiny raw-PCM spool file.
 * 2. Zipformer is fed in 240 ms batches (480 ms only while catching up). This restores the proven
 *    v1.9 throughput profile and avoids the 4x JNI/decode scheduling overhead of 60 ms feeding.
 * 3. Native decoding and app callbacks use separate executors, so database/LLM/PDF work cannot
 *    block the recognizer.
 * 4. A batch removed from the FIFO is kept as a pending batch until native processing succeeds;
 *    transient recognizer failures therefore do not silently discard the just-dequeued PCM.
 */
class LocalZipformerAsrEngine(
    private val models: AsrModelManager,
    private val spoolDirectory: File? = null,
    private val hotwordsProvider: () -> String = { "" },
) : StreamingAsrEngine {
    data class Health(
        val running: Boolean,
        val bufferedAudioMs: Int,
        val spilledAudioMs: Int,
        val lastPcmAgeMs: Long,
        val lastDecodeAgeMs: Long,
        val decoderBusy: Boolean,
    )

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE) }
            task.run()
        }, "ClassHelper-Zipformer-Streaming").apply {
            priority = (Thread.NORM_PRIORITY + 2).coerceAtMost(Thread.MAX_PRIORITY)
        }
    }
    private val callbacks = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ClassHelper-ASR-Callbacks").apply { priority = Thread.NORM_PRIORITY }
    }
    private val running = AtomicBoolean(false)

    private val audioLock = Object()
    private val pcmRing = ByteArray(PCM_RING_CAPACITY_BYTES)
    private val decodePcm = ByteArray(CATCHUP_DECODE_BATCH_BYTES)
    private val decodeSamples240 = FloatArray(NORMAL_DECODE_BATCH_BYTES / BYTES_PER_SAMPLE)
    private val decodeSamples480 = FloatArray(CATCHUP_DECODE_BATCH_BYTES / BYTES_PER_SAMPLE)
    private var pcmRead = 0
    private var pcmWrite = 0
    private var pcmSize = 0
    private var drainScheduled = false

    // Once spill mode starts, all newly captured PCM is appended to this file until it is fully
    // drained. That preserves strict FIFO order between the in-memory prefix and the disk suffix.
    private var spillFile: File? = null
    private var spillAccess: RandomAccessFile? = null
    private var spillReadOffset = 0L
    private var spillWriteOffset = 0L

    // The current batch has already been removed from the FIFO, but we keep it intact until native
    // accept/decode succeeds. On a transient exception the next drain retries the same PCM first.
    private var pendingDecodeBytes = 0

    @Volatile private var listener: StreamingAsrEngine.Listener? = null
    @Volatile private var lastBacklogNoticeMs = 0L
    @Volatile private var lastPcmReceivedMs = 0L
    @Volatile private var lastDecodeActivityMs = 0L
    @Volatile private var backlogMsSnapshot = 0
    @Volatile private var spillMsSnapshot = 0
    @Volatile private var decoderBusy = false
    @Volatile private var spoolFailureReported = false

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var activeModelDir: File? = null
    private var lastPartial = ""
    private var speechStatusShown = false

    fun health(): Health {
        val now = SystemClock.elapsedRealtime()
        val pcmAt = lastPcmReceivedMs
        val decodeAt = lastDecodeActivityMs
        return Health(
            running = running.get(),
            bufferedAudioMs = backlogMsSnapshot,
            spilledAudioMs = spillMsSnapshot,
            lastPcmAgeMs = if (pcmAt <= 0L) Long.MAX_VALUE else (now - pcmAt).coerceAtLeast(0L),
            lastDecodeAgeMs = if (decodeAt <= 0L) Long.MAX_VALUE else (now - decodeAt).coerceAtLeast(0L),
            decoderBusy = decoderBusy,
        )
    }

    override fun start(listener: StreamingAsrEngine.Listener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        clearAudioBuffer()
        val now = SystemClock.elapsedRealtime()
        lastPcmReceivedMs = 0L
        lastDecodeActivityMs = now
        decoderBusy = false
        spoolFailureReported = false
        dispatchState("正在加载 Zipformer 流式中文模型…")

        val dir = models.modelDirectory()
        if (dir == null) {
            running.set(false)
            dispatchError("Zipformer 流式模型尚未下载")
            return
        }
        activeModelDir = dir
        cleanupStaleSpoolFiles()

        worker.execute {
            try {
                val hotwords = normalizeHotwords(runCatching(hotwordsProvider).getOrDefault(""))
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.onnx").absolutePath,
                        joiner = File(dir, "joiner.int8.onnx").absolutePath,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = recommendedThreads(),
                    provider = "cpu",
                    modelType = "zipformer2",
                    modelingUnit = "cjkchar",
                    debug = false,
                )
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0.0f),
                    modelConfig = modelConfig,
                    // Keep the recognition semantics at the proven v1.9.x profile. This redesign
                    // intentionally changes transport/scheduling only, so quality regressions can be
                    // attributed and tested without confounding endpoint changes.
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 2.0f, 0.0f),
                        rule2 = EndpointRule(true, 0.75f, 0.0f),
                        rule3 = EndpointRule(false, 0.0f, 18.0f),
                    ),
                    enableEndpoint = true,
                    decodingMethod = if (hotwords.isBlank()) "greedy_search" else "modified_beam_search",
                    maxActivePaths = if (hotwords.isBlank()) 1 else 4,
                    hotwordsScore = 2.0f,
                )
                val createdRecognizer = OnlineRecognizer(assetManager = null, config = config)
                val createdStream = createdRecognizer.createStream(hotwords)
                recognizer = createdRecognizer
                stream = createdStream
                lastPartial = ""
                speechStatusShown = false
                lastBacklogNoticeMs = 0L
                lastDecodeActivityMs = SystemClock.elapsedRealtime()
                if (running.get()) {
                    dispatchState(
                        if (hotwords.isBlank()) "Zipformer 已就绪 · 连续中文识别"
                        else "Zipformer 已就绪 · 连续中文识别 · 课程热词已启用",
                    )
                    scheduleDrainIfNeeded()
                }
            } catch (t: Throwable) {
                running.set(false)
                clearAudioBuffer()
                dispatchError("Zipformer 初始化失败：${t.message ?: t.javaClass.simpleName}", t)
                releaseNative()
            }
        }
    }

    /**
     * Capture ingress must return quickly. It never waits for decoder space. When the 30-second
     * memory FIFO fills, newer PCM is appended to a raw spool file at ~32 KB/s.
     */
    override fun sendPcm16(chunk: ByteArray) {
        if (!running.get() || chunk.size < 2) return
        val evenBytes = chunk.size and -2
        if (evenBytes <= 0) return
        lastPcmReceivedMs = SystemClock.elapsedRealtime()

        var shouldSchedule = false
        var spoolFailure: Throwable? = null
        synchronized(audioLock) {
            if (spillAccess != null || pcmRing.size - pcmSize < evenBytes) {
                spoolFailure = runCatching { appendSpillLocked(chunk, evenBytes) }.exceptionOrNull()
                if (spoolFailure != null) {
                    // Storage failure is exceptional. Preserve old lossless behavior as a fallback:
                    // wait for existing spill/memory to drain rather than silently dropping speech.
                    while (
                        running.get() &&
                        (spillAccess != null || pcmRing.size - pcmSize < evenBytes)
                    ) {
                        try {
                            audioLock.wait(RING_FULL_FALLBACK_WAIT_MS)
                        } catch (_: InterruptedException) {
                            if (!running.get()) return
                        }
                    }
                    if (running.get()) writeMemoryLocked(chunk, evenBytes)
                }
            } else {
                writeMemoryLocked(chunk, evenBytes)
            }
            updateBufferSnapshotsLocked()
            if (
                !drainScheduled &&
                (pendingDecodeBytes > 0 || availableDecodeBytesLocked() >= NORMAL_DECODE_BATCH_BYTES)
            ) {
                drainScheduled = true
                shouldSchedule = true
            }
        }

        if (spoolFailure != null && !spoolFailureReported) {
            spoolFailureReported = true
            dispatchError(
                "无损音频缓存写盘失败，已退回内存等待模式：${spoolFailure?.message ?: spoolFailure?.javaClass?.simpleName}",
                spoolFailure,
            )
        }
        if (shouldSchedule) submitDrain()
    }

    private fun submitDrain() {
        runCatching { worker.execute { drainPcmBatches() } }
            .onFailure {
                synchronized(audioLock) {
                    drainScheduled = false
                    audioLock.notifyAll()
                }
            }
    }

    private fun scheduleDrainIfNeeded() {
        var shouldSchedule = false
        synchronized(audioLock) {
            if (
                running.get() &&
                !drainScheduled &&
                (pendingDecodeBytes > 0 || availableDecodeBytesLocked() >= NORMAL_DECODE_BATCH_BYTES)
            ) {
                drainScheduled = true
                shouldSchedule = true
            }
        }
        if (shouldSchedule) submitDrain()
    }

    /**
     * Normal recognition uses the proven 240 ms batch. If backlog exceeds 1.5 seconds, 480 ms
     * feeding reduces Java/JNI scheduling overhead while preserving every sample in order.
     */
    private fun drainPcmBatches() {
        while (running.get()) {
            val batch = synchronized(audioLock) {
                if (pendingDecodeBytes > 0) {
                    pendingDecodeBytes to backlogMsSnapshot
                } else {
                    val available = availableDecodeBytesLocked()
                    if (available < NORMAL_DECODE_BATCH_BYTES) {
                        drainScheduled = false
                        updateBufferSnapshotsLocked()
                        return
                    }
                    val requested = if (available >= CATCHUP_TRIGGER_BYTES) {
                        minOf(CATCHUP_DECODE_BATCH_BYTES.toLong(), available).toInt() and -2
                    } else {
                        NORMAL_DECODE_BATCH_BYTES
                    }
                    val read = readBufferedLocked(decodePcm, requested)
                    if (read != requested) {
                        drainScheduled = false
                        updateBufferSnapshotsLocked()
                        dispatchError("音频缓冲读取异常：期望 $requested 字节，实际 $read 字节")
                        return
                    }
                    pendingDecodeBytes = requested
                    updateBufferSnapshotsLocked()
                    audioLock.notifyAll()
                    requested to backlogMsSnapshot
                }
            }

            val byteCount = batch.first
            val backlogMs = batch.second
            maybeReportBacklog(backlogMs)
            val samples = reusableSamples(byteCount)
            convertPcm16ToFloat(decodePcm, samples, byteCount)
            if (!acceptSamples(samples)) {
                // Keep pendingDecodeBytes and the unchanged decodePcm so this exact batch can be
                // retried later. Do not dequeue newer audio past a failed native batch.
                synchronized(audioLock) {
                    drainScheduled = false
                    updateBufferSnapshotsLocked()
                }
                return
            }
            synchronized(audioLock) {
                pendingDecodeBytes = 0
                updateBufferSnapshotsLocked()
            }
        }
        synchronized(audioLock) {
            drainScheduled = false
            updateBufferSnapshotsLocked()
            audioLock.notifyAll()
        }
    }

    private fun reusableSamples(byteCount: Int): FloatArray = when (byteCount) {
        NORMAL_DECODE_BATCH_BYTES -> decodeSamples240
        CATCHUP_DECODE_BATCH_BYTES -> decodeSamples480
        else -> FloatArray(byteCount / BYTES_PER_SAMPLE)
    }

    private fun maybeReportBacklog(backlogMs: Int) {
        if (backlogMs < BACKLOG_WARN_MS) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBacklogNoticeMs < BACKLOG_NOTICE_INTERVAL_MS) return
        lastBacklogNoticeMs = now
        val spillMs = spillMsSnapshot
        dispatchState(
            if (spillMs > 0) {
                String.format(
                    java.util.Locale.getDefault(),
                    "识别正在追赶 · 已无损缓存 %.1f 秒（磁盘 %.1f 秒）",
                    backlogMs / 1000.0,
                    spillMs / 1000.0,
                )
            } else {
                String.format(
                    java.util.Locale.getDefault(),
                    "识别正在追赶 · 已缓存 %.1f 秒音频",
                    backlogMs / 1000.0,
                )
            },
        )
    }

    private fun writeMemoryLocked(source: ByteArray, byteCount: Int) {
        var src = 0
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(remaining, pcmRing.size - pcmWrite)
            System.arraycopy(source, src, pcmRing, pcmWrite, count)
            pcmWrite = (pcmWrite + count) % pcmRing.size
            pcmSize += count
            src += count
            remaining -= count
        }
    }

    private fun appendSpillLocked(source: ByteArray, byteCount: Int) {
        val raf = spillAccess ?: createSpillLocked()
        raf.seek(spillWriteOffset)
        raf.write(source, 0, byteCount)
        spillWriteOffset += byteCount
    }

    private fun createSpillLocked(): RandomAccessFile {
        val dir = resolvedSpoolDirectory() ?: error("ASR spool directory is unavailable")
        check(dir.exists() || dir.mkdirs()) { "无法创建 ASR 音频缓存目录" }
        val file = File(dir, "asr-${System.currentTimeMillis()}-${System.nanoTime()}.pcm")
        val raf = RandomAccessFile(file, "rw")
        spillFile = file
        spillAccess = raf
        spillReadOffset = 0L
        spillWriteOffset = 0L
        return raf
    }

    private fun availableDecodeBytesLocked(): Long =
        pcmSize.toLong() + (spillWriteOffset - spillReadOffset).coerceAtLeast(0L)

    private fun readBufferedLocked(target: ByteArray, count: Int): Int {
        var dst = 0
        var remaining = count

        if (remaining > 0 && pcmSize > 0) {
            val fromMemory = minOf(remaining, pcmSize)
            readMemoryLocked(target, dst, fromMemory)
            dst += fromMemory
            remaining -= fromMemory
        }

        if (remaining > 0) {
            val raf = spillAccess ?: return dst
            val availableSpill = (spillWriteOffset - spillReadOffset).coerceAtLeast(0L)
            var need = minOf(remaining.toLong(), availableSpill).toInt()
            raf.seek(spillReadOffset)
            while (need > 0) {
                val n = raf.read(target, dst, need)
                if (n <= 0) break
                spillReadOffset += n
                dst += n
                remaining -= n
                need -= n
            }
            if (spillReadOffset >= spillWriteOffset) closeSpillLocked(delete = true)
        }
        return dst
    }

    private fun readMemoryLocked(target: ByteArray, offset: Int, count: Int) {
        var dst = offset
        var remaining = count
        while (remaining > 0) {
            val copy = minOf(remaining, pcmRing.size - pcmRead)
            System.arraycopy(pcmRing, pcmRead, target, dst, copy)
            pcmRead = (pcmRead + copy) % pcmRing.size
            pcmSize -= copy
            dst += copy
            remaining -= copy
        }
    }

    private fun updateBufferSnapshotsLocked() {
        val queued = availableDecodeBytesLocked() + pendingDecodeBytes
        val spilled = (spillWriteOffset - spillReadOffset).coerceAtLeast(0L)
        backlogMsSnapshot = bytesToMs(queued)
        spillMsSnapshot = bytesToMs(spilled)
    }

    private fun bytesToMs(bytes: Long): Int =
        ((bytes * 1000L) / (SAMPLE_RATE * BYTES_PER_SAMPLE)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun convertPcm16ToFloat(source: ByteArray, target: FloatArray, byteCount: Int) {
        var src = 0
        var dst = 0
        while (src + 1 < byteCount && dst < target.size) {
            val value = ((source[src].toInt() and 0xff) or (source[src + 1].toInt() shl 8)).toShort()
            target[dst++] = value / 32768.0f
            src += 2
        }
    }

    /** Flush the final short batch after AudioCapture has stopped. */
    private fun drainRemainingPcm(): Boolean {
        if (pendingDecodeBytes > 0) {
            val samples = reusableSamples(pendingDecodeBytes)
            convertPcm16ToFloat(decodePcm, samples, pendingDecodeBytes)
            if (!acceptSamples(samples)) return false
            synchronized(audioLock) { pendingDecodeBytes = 0 }
        }

        while (true) {
            val count = synchronized(audioLock) {
                val available = availableDecodeBytesLocked()
                if (available <= 0L) {
                    updateBufferSnapshotsLocked()
                    0
                } else {
                    val even = minOf(available, CATCHUP_DECODE_BATCH_BYTES.toLong()).toInt() and -2
                    val read = if (even > 0) readBufferedLocked(decodePcm, even) else 0
                    pendingDecodeBytes = read
                    updateBufferSnapshotsLocked()
                    audioLock.notifyAll()
                    read
                }
            }
            if (count <= 0) return true
            val samples = reusableSamples(count)
            convertPcm16ToFloat(decodePcm, samples, count)
            if (!acceptSamples(samples)) return false
            synchronized(audioLock) {
                pendingDecodeBytes = 0
                updateBufferSnapshotsLocked()
            }
        }
    }

    private fun acceptSamples(samples: FloatArray): Boolean {
        val rec = recognizer ?: return false
        val current = stream ?: return false
        decoderBusy = true
        return try {
            current.acceptWaveform(samples, SAMPLE_RATE)
            decodeReady(rec, current)
            publishResult(rec, current)
            if (rec.isEndpoint(current)) finalizeEndpoint(rec, current)
            lastDecodeActivityMs = SystemClock.elapsedRealtime()
            true
        } catch (t: Throwable) {
            dispatchError("Zipformer 流式识别失败，当前音频批次已保留：${t.message ?: t.javaClass.simpleName}", t)
            false
        } finally {
            decoderBusy = false
        }
    }

    private fun decodeReady(rec: OnlineRecognizer, current: OnlineStream) {
        while (running.get() && rec.isReady(current)) {
            rec.decode(current)
            lastDecodeActivityMs = SystemClock.elapsedRealtime()
        }
    }

    private fun publishResult(rec: OnlineRecognizer, current: OnlineStream) {
        val text = rec.getResult(current).text.trim()
        if (text.isBlank() || text == lastPartial) return
        lastPartial = text
        dispatchPartial(text)
        if (!speechStatusShown) {
            speechStatusShown = true
            dispatchState("正在听课 · 连续识别中")
        }
    }

    private fun finalizeEndpoint(rec: OnlineRecognizer, current: OnlineStream) {
        val finalText = rec.getResult(current).text.trim().ifBlank { lastPartial }
        if (finalText.isNotBlank()) dispatchFinal(finalText)
        rec.reset(current)
        lastPartial = ""
        speechStatusShown = false
        dispatchState("Zipformer 已就绪 · 等待讲话")
    }

    override fun finish(onFinished: () -> Unit) {
        if (!running.get()) {
            onFinished()
            return
        }
        runCatching {
            worker.execute {
                try {
                    val drained = drainRemainingPcm()
                    val rec = recognizer
                    val current = stream
                    if (drained && rec != null && current != null) {
                        decoderBusy = true
                        current.inputFinished()
                        while (rec.isReady(current)) {
                            rec.decode(current)
                            lastDecodeActivityMs = SystemClock.elapsedRealtime()
                        }
                        val finalText = rec.getResult(current).text.trim().ifBlank { lastPartial }
                        lastPartial = ""
                        if (finalText.isNotBlank()) {
                            dispatchFinal(finalText, onFinished)
                            return@execute
                        }
                    }
                } catch (t: Throwable) {
                    dispatchError("Zipformer 收尾失败：${t.message ?: t.javaClass.simpleName}", t)
                } finally {
                    decoderBusy = false
                }
                onFinished()
            }
        }.onFailure { onFinished() }
    }

    override fun stop() {
        if (!running.getAndSet(false)) {
            clearAudioBuffer()
            runCatching { worker.execute { releaseNative() } }
            runCatching { worker.shutdown() }
            runCatching { callbacks.shutdown() }
            return
        }
        synchronized(audioLock) { audioLock.notifyAll() }
        runCatching { worker.execute { releaseNative() } }
        runCatching { worker.shutdown() }
        runCatching { callbacks.shutdown() }
    }

    private fun clearAudioBuffer() {
        synchronized(audioLock) {
            pcmRead = 0
            pcmWrite = 0
            pcmSize = 0
            pendingDecodeBytes = 0
            drainScheduled = false
            closeSpillLocked(delete = true)
            backlogMsSnapshot = 0
            spillMsSnapshot = 0
            audioLock.notifyAll()
        }
    }

    private fun closeSpillLocked(delete: Boolean) {
        runCatching { spillAccess?.close() }
        spillAccess = null
        val file = spillFile
        spillFile = null
        spillReadOffset = 0L
        spillWriteOffset = 0L
        if (delete && file != null) runCatching { file.delete() }
    }

    private fun resolvedSpoolDirectory(): File? {
        spoolDirectory?.let { return it }
        val modelDir = activeModelDir ?: models.modelDirectory() ?: return null
        return File(modelDir.parentFile ?: modelDir, "asr-pcm-spool")
    }

    private fun cleanupStaleSpoolFiles() {
        val dir = resolvedSpoolDirectory() ?: return
        runCatching {
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.startsWith("asr-") && file.name.endsWith(".pcm")) {
                        file.delete()
                    }
                }
            }
        }
    }

    private fun releaseNative() {
        clearAudioBuffer()
        runCatching { stream?.release() }
        stream = null
        runCatching { recognizer?.release() }
        recognizer = null
        lastPartial = ""
        speechStatusShown = false
        decoderBusy = false
        activeModelDir = null
    }

    private fun dispatchState(state: String) {
        dispatchCallback { listener?.onState(state) }
    }

    private fun dispatchPartial(text: String) {
        dispatchCallback { listener?.onPartial(text) }
    }

    private fun dispatchFinal(text: String, after: (() -> Unit)? = null) {
        dispatchCallback(after) { listener?.onFinal(text) }
    }

    private fun dispatchError(message: String, cause: Throwable? = null) {
        dispatchCallback { listener?.onError(message, cause) }
    }

    private fun dispatchCallback(after: (() -> Unit)? = null, block: () -> Unit) {
        val task = Runnable {
            try {
                block()
            } catch (_: Throwable) {
                // A consumer callback must never terminate the callback lane or native decoder.
            } finally {
                after?.invoke()
            }
        }
        runCatching { callbacks.execute(task) }
            .onFailure { after?.invoke() }
    }

    private fun normalizeHotwords(raw: String): String = raw
        .split(Regex("[\\r\\n/]+"))
        .asSequence()
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.length in 2..24 }
        .distinct()
        .take(MAX_HOTWORDS)
        .joinToString("/")

    private fun recommendedThreads(): Int = when {
        Runtime.getRuntime().availableProcessors() >= 8 -> 3
        Runtime.getRuntime().availableProcessors() >= 6 -> 2
        else -> 1
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_HOTWORDS = 64

        private const val NORMAL_DECODE_BATCH_MS = 240
        private const val CATCHUP_DECODE_BATCH_MS = 480
        private const val NORMAL_DECODE_BATCH_BYTES =
            SAMPLE_RATE * BYTES_PER_SAMPLE * NORMAL_DECODE_BATCH_MS / 1000
        private const val CATCHUP_DECODE_BATCH_BYTES =
            SAMPLE_RATE * BYTES_PER_SAMPLE * CATCHUP_DECODE_BATCH_MS / 1000

        private const val CATCHUP_TRIGGER_MS = 1_500
        private const val CATCHUP_TRIGGER_BYTES =
            SAMPLE_RATE.toLong() * BYTES_PER_SAMPLE * CATCHUP_TRIGGER_MS / 1000L

        // Thirty seconds stays in RAM (~0.92 MiB). Only unusually large decoder lag spills to disk.
        private const val PCM_RING_SECONDS = 30
        private const val PCM_RING_CAPACITY_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * PCM_RING_SECONDS
        private const val RING_FULL_FALLBACK_WAIT_MS = 10L

        private const val BACKLOG_WARN_MS = 1_500
        private const val BACKLOG_NOTICE_INTERVAL_MS = 3_000L
    }
}
