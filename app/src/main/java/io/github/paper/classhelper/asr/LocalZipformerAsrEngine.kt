package io.github.paper.classhelper.asr

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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * True streaming Chinese ASR based on sherpa-onnx Zipformer Transducer INT8.
 *
 * Audio capture -> fixed PCM ring -> dedicated high-priority decoder. Listener callbacks are
 * dispatched on a second low-priority executor, so app/UI/database/LLM code can never execute on
 * the native Zipformer decode thread. The ring remains bounded and never intentionally discards
 * older classroom audio just to keep subtitles visually current.
 */
class LocalZipformerAsrEngine(
    private val models: AsrModelManager,
    private val hotwordsProvider: () -> String = { "" },
) : StreamingAsrEngine {
    data class Health(
        val running: Boolean,
        val bufferedAudioMs: Int,
        val lastPcmAgeMs: Long,
        val lastDecodeAgeMs: Long,
        val decoderBusy: Boolean,
    )

    // ASR is the most latency-sensitive CPU work in the app. Keep it above normal business jobs.
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClassHelper-Zipformer-Streaming").apply {
            priority = (Thread.NORM_PRIORITY + 2).coerceAtMost(Thread.MAX_PRIORITY)
        }
    }
    // Every listener callback is isolated from the native decoder even if service code regresses.
    private val callbacks = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClassHelper-ASR-Callbacks").apply {
            priority = (Thread.NORM_PRIORITY - 2).coerceAtLeast(Thread.MIN_PRIORITY)
        }
    }
    private val running = AtomicBoolean(false)

    private val audioLock = Object()
    private val pcmRing = ByteArray(PCM_RING_CAPACITY_BYTES)
    private val decodePcm = ByteArray(DECODE_BATCH_BYTES)
    private val decodeSamples = FloatArray(DECODE_BATCH_BYTES / 2)
    private var pcmRead = 0
    private var pcmWrite = 0
    private var pcmSize = 0
    private var drainScheduled = false

    @Volatile private var listener: StreamingAsrEngine.Listener? = null
    @Volatile private var lastBacklogNoticeMs = 0L
    @Volatile private var lastPcmReceivedMs = 0L
    @Volatile private var lastDecodeActivityMs = 0L
    @Volatile private var backlogMsSnapshot = 0
    @Volatile private var decoderBusy = false
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var lastPartial = ""
    private var speechStatusShown = false

    fun health(): Health {
        val now = SystemClock.elapsedRealtime()
        val pcmAt = lastPcmReceivedMs
        val decodeAt = lastDecodeActivityMs
        return Health(
            running = running.get(),
            bufferedAudioMs = backlogMsSnapshot,
            lastPcmAgeMs = if (pcmAt <= 0L) Long.MAX_VALUE else (now - pcmAt).coerceAtLeast(0L),
            lastDecodeAgeMs = if (decodeAt <= 0L) Long.MAX_VALUE else (now - decodeAt).coerceAtLeast(0L),
            decoderBusy = decoderBusy,
        )
    }

    override fun start(listener: StreamingAsrEngine.Listener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        clearPcmRing()
        val now = SystemClock.elapsedRealtime()
        lastPcmReceivedMs = 0L
        lastDecodeActivityMs = now
        decoderBusy = false
        dispatchState("正在加载 Zipformer 流式中文模型…")

        val dir = models.modelDirectory()
        if (dir == null) {
            running.set(false)
            dispatchError("Zipformer 流式模型尚未下载")
            return
        }

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
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 2.0f, 0.0f),
                        rule2 = EndpointRule(true, 0.75f, 0.0f),
                        rule3 = EndpointRule(false, 0.0f, 18.0f),
                    ),
                    enableEndpoint = true,
                    decodingMethod = if (hotwords.isBlank()) "greedy_search" else "modified_beam_search",
                    maxActivePaths = if (hotwords.isBlank()) 1 else 2,
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
                        if (hotwords.isBlank()) "Zipformer 已就绪 · 实时中文识别"
                        else "Zipformer 已就绪 · 实时中文识别 · 手动热词已启用",
                    )
                    scheduleDrainIfNeeded()
                }
            } catch (t: Throwable) {
                running.set(false)
                clearPcmRing()
                dispatchError("Zipformer 初始化失败：${t.message ?: t.javaClass.simpleName}", t)
                releaseNative()
            }
        }
    }

    override fun sendPcm16(chunk: ByteArray) {
        if (!running.get() || chunk.size < 2) return
        lastPcmReceivedMs = SystemClock.elapsedRealtime()

        var shouldSchedule = false
        synchronized(audioLock) {
            while (running.get() && pcmRing.size - pcmSize < chunk.size) {
                try {
                    audioLock.wait(RING_FULL_WAIT_MS)
                } catch (_: InterruptedException) {
                    if (!running.get()) return
                }
            }
            if (!running.get()) return

            var src = 0
            var remaining = chunk.size
            while (remaining > 0) {
                val count = minOf(remaining, pcmRing.size - pcmWrite)
                System.arraycopy(chunk, src, pcmRing, pcmWrite, count)
                pcmWrite = (pcmWrite + count) % pcmRing.size
                pcmSize += count
                src += count
                remaining -= count
            }
            updateBacklogSnapshotLocked()
            if (!drainScheduled && pcmSize >= DECODE_BATCH_BYTES) {
                drainScheduled = true
                shouldSchedule = true
            }
        }

        if (shouldSchedule) {
            runCatching { worker.execute { drainPcmBatches() } }
                .onFailure {
                    synchronized(audioLock) {
                        drainScheduled = false
                        audioLock.notifyAll()
                    }
                }
        }
    }

    private fun scheduleDrainIfNeeded() {
        var shouldSchedule = false
        synchronized(audioLock) {
            if (running.get() && !drainScheduled && pcmSize >= DECODE_BATCH_BYTES) {
                drainScheduled = true
                shouldSchedule = true
            }
        }
        if (shouldSchedule) {
            runCatching { worker.execute { drainPcmBatches() } }
                .onFailure {
                    synchronized(audioLock) {
                        drainScheduled = false
                        audioLock.notifyAll()
                    }
                }
        }
    }

    /** Decode 4 capture frames (~240 ms) per JNI/recognizer feed to reduce per-frame overhead. */
    private fun drainPcmBatches() {
        while (running.get()) {
            val backlogMs = synchronized(audioLock) {
                if (pcmSize < DECODE_BATCH_BYTES) {
                    drainScheduled = false
                    updateBacklogSnapshotLocked()
                    -1
                } else {
                    readPcmLocked(decodePcm, DECODE_BATCH_BYTES)
                    audioLock.notifyAll()
                    updateBacklogSnapshotLocked()
                    backlogMsSnapshot
                }
            }
            if (backlogMs < 0) return
            maybeReportBacklog(backlogMs)
            convertPcm16ToFloat(decodePcm, decodeSamples, DECODE_BATCH_BYTES)
            acceptSamples(decodeSamples)
        }
        synchronized(audioLock) {
            drainScheduled = false
            updateBacklogSnapshotLocked()
            audioLock.notifyAll()
        }
    }

    private fun maybeReportBacklog(backlogMs: Int) {
        if (backlogMs < BACKLOG_WARN_MS) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBacklogNoticeMs < BACKLOG_NOTICE_INTERVAL_MS) return
        lastBacklogNoticeMs = now
        dispatchState(
            String.format(
                java.util.Locale.getDefault(),
                "识别负载较高 · 正在追赶约 %.1f 秒音频",
                backlogMs / 1000.0,
            ),
        )
    }

    private fun updateBacklogSnapshotLocked() {
        backlogMsSnapshot = ((pcmSize.toLong() * 1000L) / (SAMPLE_RATE * BYTES_PER_SAMPLE)).toInt()
    }

    private fun readPcmLocked(target: ByteArray, count: Int) {
        var dst = 0
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

    private fun convertPcm16ToFloat(source: ByteArray, target: FloatArray, byteCount: Int) {
        var src = 0
        var dst = 0
        while (src + 1 < byteCount && dst < target.size) {
            val value = ((source[src].toInt() and 0xff) or (source[src + 1].toInt() shl 8)).toShort()
            target[dst++] = value / 32768.0f
            src += 2
        }
    }

    private fun drainRemainingPcm() {
        while (true) {
            val count = synchronized(audioLock) {
                if (pcmSize <= 0) {
                    updateBacklogSnapshotLocked()
                    0
                } else {
                    val even = minOf(pcmSize, DECODE_BATCH_BYTES) and -2
                    if (even > 0) {
                        readPcmLocked(decodePcm, even)
                        updateBacklogSnapshotLocked()
                        audioLock.notifyAll()
                    }
                    even
                }
            }
            if (count <= 0) return
            val samples = if (count == DECODE_BATCH_BYTES) decodeSamples else FloatArray(count / 2)
            convertPcm16ToFloat(decodePcm, samples, count)
            acceptSamples(samples)
        }
    }

    private fun acceptSamples(samples: FloatArray) {
        val rec = recognizer ?: return
        val current = stream ?: return
        decoderBusy = true
        try {
            current.acceptWaveform(samples, SAMPLE_RATE)
            lastDecodeActivityMs = SystemClock.elapsedRealtime()
            decodeReady(rec, current)
            publishResult(rec, current)
            if (rec.isEndpoint(current)) finalizeEndpoint(rec, current)
            lastDecodeActivityMs = SystemClock.elapsedRealtime()
        } catch (t: Throwable) {
            dispatchError("Zipformer 流式识别失败：${t.message ?: t.javaClass.simpleName}", t)
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
            dispatchState("正在听课 · 实时识别中")
        }
    }

    private fun finalizeEndpoint(rec: OnlineRecognizer, current: OnlineStream) {
        val finalText = rec.getResult(current).text.trim().ifBlank { lastPartial }
        rec.reset(current)
        lastPartial = ""
        speechStatusShown = false
        if (finalText.isNotBlank()) dispatchFinal(finalText)
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
                    drainRemainingPcm()
                    val rec = recognizer
                    val current = stream
                    if (rec != null && current != null) {
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
            clearPcmRing()
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

    private fun clearPcmRing() {
        synchronized(audioLock) {
            pcmRead = 0
            pcmWrite = 0
            pcmSize = 0
            drainScheduled = false
            backlogMsSnapshot = 0
            audioLock.notifyAll()
        }
    }

    private fun releaseNative() {
        clearPcmRing()
        runCatching { stream?.release() }
        stream = null
        runCatching { recognizer?.release() }
        recognizer = null
        lastPartial = ""
        speechStatusShown = false
        decoderBusy = false
    }

    private fun dispatchState(state: String) {
        dispatchCallback { listener?.onState(state) }
    }

    private fun dispatchPartial(text: String) {
        // Keep this literal listener call in the engine so source regression validation can verify
        // streaming partials remain wired, while execution itself happens off the decoder thread.
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
        private const val MAX_HOTWORDS = 24
        private const val CAPTURE_FRAME_MS = 60
        private const val DECODE_BATCH_FRAMES = 4
        private const val DECODE_BATCH_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * CAPTURE_FRAME_MS * DECODE_BATCH_FRAMES / 1000
        private const val PCM_RING_SECONDS = 180
        private const val PCM_RING_CAPACITY_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * PCM_RING_SECONDS
        private const val RING_FULL_WAIT_MS = 20L
        private const val BACKLOG_WARN_MS = 1_500
        private const val BACKLOG_NOTICE_INTERVAL_MS = 3_000L
    }
}
