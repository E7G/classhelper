package io.github.paper.classhelper.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal allocation 16 kHz PCM capture. No wake lock is held here. */
class AudioCapture {
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(onChunk: (ByteArray) -> Unit, onError: (Throwable) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val sampleRate = 16_000
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Keep 60 ms callback granularity for low-latency streaming. A larger AudioRecord backing
        // buffer gives the ASR thread extra headroom during brief CPU stalls without allocating per frame.
        val chunkBytes = 1_920
        val fourSecondsBytes = sampleRate * 2 * 4
        val bufferBytes = maxOf(min * 2, fourSecondsBytes)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            running.set(false)
            audio.release()
            onError(IllegalStateException("麦克风初始化失败"))
            return
        }
        recorder = audio
        audio.startRecording()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = ByteArray(chunkBytes)
            try {
                while (running.get()) {
                    var off = 0
                    while (off < buf.size && running.get()) {
                        val n = audio.read(buf, off, buf.size - off, AudioRecord.READ_BLOCKING)
                        if (n < 0) error("AudioRecord.read=$n")
                        off += n
                    }
                    if (off == buf.size) onChunk(buf)
                }
            } catch (t: Throwable) {
                if (running.get()) onError(t)
            } finally {
                runCatching { audio.stop() }
                audio.release()
                recorder = null
            }
        }, "ClassHelper-Audio").also { it.start() }
    }

    fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
        thread?.interrupt()
        thread = null
    }
}
