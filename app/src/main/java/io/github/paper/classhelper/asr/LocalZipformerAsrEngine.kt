package io.github.paper.classhelper.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * True streaming Chinese ASR based on sherpa-onnx Zipformer Transducer INT8.
 *
 * Audio capture is intentionally decoupled from decode through a tiny bounded queue. This avoids an
 * unbounded Executor backlog when a device temporarily decodes slower than real time: stale audio is
 * discarded instead of consuming the Java heap and making subtitles progressively later.
 */
class LocalZipformerAsrEngine(
    private val models: AsrModelManager,
    private val hotwordsProvider: () -> String = { "" },
) : StreamingAsrEngine {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClassHelper-Zipformer-Streaming").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val running = AtomicBoolean(false)

    private val audioLock = Any()
    private val pendingAudio = ArrayDeque<FloatArray>(MAX_PENDING_AUDIO_FRAMES)
    private val samplePool = ArrayDeque<FloatArray>(MAX_SAMPLE_POOL_FRAMES)
    private var drainScheduled = false
    private var droppedAudioFrames = 0L

    @Volatile private var listener: StreamingAsrEngine.Listener? = null
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var lastPartial = ""
    private var speechStatusShown = false

    override fun start(listener: StreamingAsrEngine.Listener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        listener.onState("正在加载 Zipformer 流式中文模型…")

        val dir = models.modelDirectory()
        if (dir == null) {
            running.set(false)
            listener.onError("Zipformer 流式模型尚未下载")
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
                    maxActivePaths = 4,
                    hotwordsScore = 2.0f,
                )
                val createdRecognizer = OnlineRecognizer(assetManager = null, config = config)
                val createdStream = createdRecognizer.createStream(hotwords)
                recognizer = createdRecognizer
                stream = createdStream
                lastPartial = ""
                speechStatusShown = false
                synchronized(audioLock) {
                    droppedAudioFrames = 0L
                }
                if (running.get()) {
                    listener.onState(
                        if (hotwords.isBlank()) "Zipformer 已就绪 · 实时中文识别"
                        else "Zipformer 已就绪 · 实时中文识别 · 热词已启用",
                    )
                }
            } catch (t: Throwable) {
                running.set(false)
                clearAudioQueue()
                listener.onError("Zipformer 初始化失败：${t.message ?: t.javaClass.simpleName}", t)
                releaseNative()
            }
        }
    }

    override fun sendPcm16(chunk: ByteArray) {
        if (!running.get() || chunk.size < 2) return

        // AudioCapture reuses its ByteArray. Reuse a small pool of FloatArrays to keep GC pressure low.
        val samples = obtainSampleBuffer(chunk.size / 2)
        var src = 0
        var dst = 0
        while (src + 1 < chunk.size && dst < samples.size) {
            val value = ((chunk[src].toInt() and 0xff) or (chunk[src + 1].toInt() shl 8)).toShort()
            samples[dst++] = value / 32768.0f
            src += 2
        }

        var shouldScheduleDrain = false
        synchronized(audioLock) {
            if (!running.get()) {
                recycleSampleBufferLocked(samples)
                return
            }
            if (pendingAudio.size >= MAX_PENDING_AUDIO_FRAMES) {
                val stale = pendingAudio.removeFirst()
                recycleSampleBufferLocked(stale)
                droppedAudioFrames++
            }
            pendingAudio.addLast(samples)
            if (!drainScheduled) {
                drainScheduled = true
                shouldScheduleDrain = true
            }
        }

        if (shouldScheduleDrain) {
            runCatching { worker.execute { drainAudioQueue() } }
                .onFailure { clearAudioQueue() }
        }
    }

    /**
     * Drain all currently queued frames in one worker task. At most MAX_PENDING_AUDIO_FRAMES frames
     * can exist, so decoder slowdown cannot create an unbounded Runnable/FloatArray backlog.
     */
    private fun drainAudioQueue() {
        while (running.get()) {
            val samples = synchronized(audioLock) {
                if (pendingAudio.isEmpty()) {
                    drainScheduled = false
                    null
                } else {
                    pendingAudio.removeFirst()
                }
            } ?: return

            try {
                acceptSamples(samples)
            } finally {
                synchronized(audioLock) { recycleSampleBufferLocked(samples) }
            }
        }
        clearAudioQueue()
    }

    private fun obtainSampleBuffer(size: Int): FloatArray = synchronized(audioLock) {
        if (samplePool.isEmpty()) {
            FloatArray(size)
        } else {
            val pooled = samplePool.removeFirst()
            if (pooled.size == size) pooled else FloatArray(size)
        }
    }

    private fun recycleSampleBufferLocked(samples: FloatArray) {
        if (samplePool.size < MAX_SAMPLE_POOL_FRAMES) samplePool.addLast(samples)
    }

    private fun clearAudioQueue() {
        synchronized(audioLock) {
            while (pendingAudio.isNotEmpty()) recycleSampleBufferLocked(pendingAudio.removeFirst())
            drainScheduled = false
        }
    }

    private fun acceptSamples(samples: FloatArray) {
        val rec = recognizer ?: return
        val current = stream ?: return
        try {
            current.acceptWaveform(samples, SAMPLE_RATE)
            decodeReady(rec, current)
            publishResult(rec, current)
            if (rec.isEndpoint(current)) finalizeEndpoint(rec, current)
        } catch (t: Throwable) {
            listener?.onError("Zipformer 流式识别失败：${t.message ?: t.javaClass.simpleName}", t)
        }
    }

    private fun decodeReady(rec: OnlineRecognizer, current: OnlineStream) {
        while (running.get() && rec.isReady(current)) rec.decode(current)
    }

    private fun publishResult(rec: OnlineRecognizer, current: OnlineStream) {
        val text = rec.getResult(current).text.trim()
        if (text.isBlank() || text == lastPartial) return
        lastPartial = text
        listener?.onPartial(text)
        if (!speechStatusShown) {
            speechStatusShown = true
            listener?.onState("正在听课 · 实时识别中")
        }
    }

    private fun finalizeEndpoint(rec: OnlineRecognizer, current: OnlineStream) {
        val finalText = rec.getResult(current).text.trim().ifBlank { lastPartial }
        if (finalText.isNotBlank()) listener?.onFinal(finalText)
        rec.reset(current)
        lastPartial = ""
        speechStatusShown = false
        listener?.onState("Zipformer 已就绪 · 等待讲话")
    }

    override fun finish(onFinished: () -> Unit) {
        if (!running.get()) {
            onFinished()
            return
        }
        // AudioCapture is stopped before finish(), so the single drain task ahead of this control task
        // consumes every bounded pending frame first. There is no ever-growing queue to wait through.
        runCatching {
            worker.execute {
                try {
                    val rec = recognizer
                    val current = stream
                    if (rec != null && current != null) {
                        current.inputFinished()
                        while (rec.isReady(current)) rec.decode(current)
                        val finalText = rec.getResult(current).text.trim().ifBlank { lastPartial }
                        if (finalText.isNotBlank()) listener?.onFinal(finalText)
                        lastPartial = ""
                    }
                } catch (t: Throwable) {
                    listener?.onError("Zipformer 收尾失败：${t.message ?: t.javaClass.simpleName}", t)
                } finally {
                    onFinished()
                }
            }
        }.onFailure { onFinished() }
    }

    override fun stop() {
        if (!running.getAndSet(false)) {
            clearAudioQueue()
            releaseNative()
            return
        }
        clearAudioQueue()
        runCatching { worker.execute { releaseNative() } }
        worker.shutdown()
    }

    private fun releaseNative() {
        clearAudioQueue()
        runCatching { stream?.release() }
        stream = null
        runCatching { recognizer?.release() }
        recognizer = null
        lastPartial = ""
        speechStatusShown = false
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
        private const val MAX_HOTWORDS = 64
        // AudioCapture emits ~60 ms frames: 8 frames = ~480 ms maximum queued audio.
        private const val MAX_PENDING_AUDIO_FRAMES = 8
        private const val MAX_SAMPLE_POOL_FRAMES = MAX_PENDING_AUDIO_FRAMES + 2
    }
}
