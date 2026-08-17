package io.github.paper.classhelper.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-overhead local classroom ASR: SenseVoiceSmall INT8 + Silero VAD.
 *
 * SenseVoice itself is an offline model, so classroom "streaming" is implemented as fast VAD-sized
 * utterances: AudioRecord never stops, Silero emits completed speech segments, and SenseVoice decodes
 * each segment immediately on a dedicated worker. This avoids re-decoding the same growing utterance
 * over and over while keeping the PDF/UI thread free.
 *
 * We keep up to 20 seconds of PCM while the model is loading so tapping "开始听课" does not silently
 * lose the teacher's first sentence. ITN is enabled because the 2024-07-17 SenseVoice conversion can
 * emit punctuation/numbers with it enabled, which improves question detection and automatic notes.
 */
class LocalSenseVoiceAsrEngine(
    private val models: AsrModelManager
) : StreamingAsrEngine {
    private val audioWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClassHelper-SenseVoice-Audio").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val decodeWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClassHelper-SenseVoice-Decode").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val running = AtomicBoolean(false)

    @Volatile private var listener: StreamingAsrEngine.Listener? = null
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var vad: Vad? = null
    @Volatile private var ready = false

    // Owned by audioWorker only.
    private val beforeReady = ArrayDeque<FloatArray>()
    private var beforeReadySamples = 0
    private val vadWindow = FloatArray(VAD_WINDOW_SIZE)
    private var vadWindowFill = 0
    private var speechWasDetected = false

    override fun start(listener: StreamingAsrEngine.Listener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        listener.onState("正在加载 SenseVoiceSmall…")

        val dir = models.modelDirectory()
        if (dir == null) {
            running.set(false)
            listener.onError("SenseVoiceSmall 模型尚未下载")
            return
        }

        decodeWorker.execute {
            try {
                val senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(dir, "model.int8.onnx").absolutePath,
                    language = "auto",
                    useInverseTextNormalization = true
                )
                val recognizerConfig = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = senseVoice,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        numThreads = recommendedThreads(),
                        debug = false,
                        provider = "cpu"
                    ),
                    decodingMethod = "greedy_search"
                )
                val createdRecognizer = OfflineRecognizer(assetManager = null, config = recognizerConfig)

                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = File(dir, "silero_vad.onnx").absolutePath,
                        threshold = 0.48f,
                        minSilenceDuration = 0.45f,
                        minSpeechDuration = 0.20f,
                        windowSize = VAD_WINDOW_SIZE,
                        maxSpeechDuration = 20.0f
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false
                )
                val createdVad = Vad(assetManager = null, config = vadConfig)

                recognizer = createdRecognizer
                vad = createdVad
                ready = true
                listener.onState("SenseVoiceSmall 已就绪 · 本地识别")
                audioWorker.execute { drainBeforeReady() }
            } catch (t: Throwable) {
                running.set(false)
                listener.onError("SenseVoiceSmall 初始化失败：${t.message}", t)
            }
        }
    }

    override fun sendPcm16(chunk: ByteArray) {
        if (!running.get() || chunk.size < 2) return
        // AudioCapture reuses its byte buffer, so convert before returning to its capture loop.
        val samples = FloatArray(chunk.size / 2)
        var src = 0
        var dst = 0
        while (src + 1 < chunk.size) {
            val value = ((chunk[src].toInt() and 0xff) or (chunk[src + 1].toInt() shl 8)).toShort()
            samples[dst++] = value / 32768.0f
            src += 2
        }
        audioWorker.execute { acceptSamples(samples) }
    }

    private fun acceptSamples(samples: FloatArray) {
        if (!running.get()) return
        if (!ready || vad == null) {
            beforeReady.addLast(samples)
            beforeReadySamples += samples.size
            while (beforeReadySamples > MAX_PREINIT_SAMPLES && beforeReady.isNotEmpty()) {
                beforeReadySamples -= beforeReady.removeFirst().size
            }
            return
        }
        feedVad(samples)
    }

    private fun drainBeforeReady() {
        if (!running.get() || !ready) return
        while (beforeReady.isNotEmpty()) {
            val frame = beforeReady.removeFirst()
            beforeReadySamples -= frame.size
            feedVad(frame)
        }
        beforeReadySamples = 0
    }

    private fun feedVad(samples: FloatArray) {
        val detector = vad ?: return
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(VAD_WINDOW_SIZE - vadWindowFill, samples.size - offset)
            samples.copyInto(vadWindow, vadWindowFill, offset, offset + count)
            vadWindowFill += count
            offset += count
            if (vadWindowFill == VAD_WINDOW_SIZE) {
                detector.acceptWaveform(vadWindow)
                vadWindowFill = 0
                val speech = detector.isSpeechDetected()
                if (speech && !speechWasDetected) listener?.onState("听到讲话 · 正在记录")
                speechWasDetected = speech
                drainVadSegments(detector)
            }
        }
    }

    private fun drainVadSegments(detector: Vad) {
        while (!detector.empty()) {
            val segment = detector.front()
            detector.pop()
            if (segment.samples.size >= MIN_DECODE_SAMPLES) {
                listener?.onState("正在识别刚才的语句…")
                decodeWorker.execute { decode(segment.samples) }
            }
        }
    }

    private fun decode(samples: FloatArray) {
        val rec = recognizer ?: run {
            listener?.onError("SenseVoiceSmall 尚未完成初始化")
            return
        }
        var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
        try {
            stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val result = rec.getResult(stream)
            val text = result.text.trim()
            if (text.isNotBlank()) listener?.onFinal(text)
            if (running.get()) listener?.onState("SenseVoiceSmall 已就绪 · 等待讲话")
        } catch (t: Throwable) {
            listener?.onError("SenseVoiceSmall 识别失败：${t.message}", t)
        } finally {
            runCatching { stream?.release() }
        }
    }

    override fun finish(onFinished: () -> Unit) {
        if (!running.get()) {
            onFinished()
            return
        }
        decodeWorker.execute {
            audioWorker.execute {
                drainBeforeReady()
                val detector = vad
                if (detector != null) {
                    if (vadWindowFill > 0) {
                        java.util.Arrays.fill(vadWindow, vadWindowFill, VAD_WINDOW_SIZE, 0f)
                        detector.acceptWaveform(vadWindow)
                        vadWindowFill = 0
                    }
                    detector.flush()
                    drainVadSegments(detector)
                }
                decodeWorker.execute { onFinished() }
            }
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        audioWorker.execute {
            beforeReady.clear()
            beforeReadySamples = 0
            vadWindowFill = 0
            speechWasDetected = false
        }
        audioWorker.shutdown()
        decodeWorker.execute {
            runCatching { vad?.release() }
            vad = null
            runCatching { recognizer?.release() }
            recognizer = null
            ready = false
        }
        decodeWorker.shutdown()
    }

    private fun recommendedThreads(): Int = when {
        Runtime.getRuntime().availableProcessors() >= 8 -> 3
        Runtime.getRuntime().availableProcessors() >= 6 -> 2
        else -> 1
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val VAD_WINDOW_SIZE = 512
        private const val MIN_DECODE_SAMPLES = SAMPLE_RATE / 5
        private const val MAX_PREINIT_SAMPLES = SAMPLE_RATE * 20
    }
}
