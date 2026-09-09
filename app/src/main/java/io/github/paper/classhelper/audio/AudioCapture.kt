package io.github.paper.classhelper.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal-allocation 16 kHz PCM capture with clean recovery after AudioRecord failures. */
class AudioCapture {
    data class Health(
        val running: Boolean,
        val lastReadAgeMs: Long,
    )

    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var lastReadMs: Long = 0L

    @SuppressLint("MissingPermission")
    fun start(onChunk: (ByteArray) -> Unit, onError: (Throwable) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val sampleRate = 16_000
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunkBytes = 1_920
        val fourSecondsBytes = sampleRate * 2 * 4
        val bufferBytes = maxOf(min * 2, fourSecondsBytes)

        val audio = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: Throwable) {
            running.set(false)
            onError(t)
            return
        }

        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            running.set(false)
            audio.release()
            onError(IllegalStateException("麦克风初始化失败"))
            return
        }

        recorder = audio
        lastReadMs = SystemClock.elapsedRealtime()
        try {
            audio.startRecording()
        } catch (t: Throwable) {
            running.set(false)
            recorder = null
            audio.release()
            onError(t)
            return
        }

        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = ByteArray(chunkBytes)
            var zeroReads = 0
            try {
                while (running.get()) {
                    var off = 0
                    while (off < buf.size && running.get()) {
                        val n = audio.read(buf, off, buf.size - off, AudioRecord.READ_BLOCKING)
                        if (n < 0) error("AudioRecord.read=$n")
                        if (n == 0) {
                            zeroReads++
                            if (zeroReads >= MAX_ZERO_READS) error("AudioRecord 连续返回空数据")
                            continue
                        }
                        zeroReads = 0
                        lastReadMs = SystemClock.elapsedRealtime()
                        off += n
                    }
                    if (off == buf.size) onChunk(buf)
                }
            } catch (t: Throwable) {
                if (running.get()) onError(t)
            } finally {
                running.set(false)
                runCatching { audio.stop() }
                audio.release()
                if (recorder === audio) recorder = null
                thread = null
            }
        }, "ClassHelper-Audio").also { it.start() }
    }

    fun isRunning(): Boolean = running.get()

    fun health(): Health {
        val now = SystemClock.elapsedRealtime()
        val readAt = lastReadMs
        return Health(
            running = running.get(),
            lastReadAgeMs = if (readAt <= 0L) Long.MAX_VALUE else (now - readAt).coerceAtLeast(0L),
        )
    }

    fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
        thread?.interrupt()
        thread = null
    }

    companion object {
        private const val MAX_ZERO_READS = 8
    }
}
