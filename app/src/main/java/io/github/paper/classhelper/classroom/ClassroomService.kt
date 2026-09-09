package io.github.paper.classhelper.classroom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.R
import io.github.paper.classhelper.asr.LocalZipformerAsrEngine
import io.github.paper.classhelper.asr.StreamingAsrEngine
import io.github.paper.classhelper.audio.AudioCapture
import io.github.paper.classhelper.ui.ReaderActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground classroom recorder with strict ASR isolation.
 *
 * Zipformer owns its decoder thread. All database/question/note/PDF work stays on independent
 * consumer lanes. The watchdog only repairs AudioRecord; it never destroys a live Zipformer stream
 * because doing so would clear buffered classroom audio and cause omissions.
 */
class ClassroomService : Service(), StreamingAsrEngine.Listener {
    private val rootJob = SupervisorJob()

    private val eventDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClassHelper-Transcript-Events").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
    private val questionDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClassHelper-Questions").apply { priority = Thread.NORM_PRIORITY - 2 }
    }.asCoroutineDispatcher()
    private val noteDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClassHelper-AutoNotes").apply { priority = Thread.NORM_PRIORITY - 2 }
    }.asCoroutineDispatcher()
    private val pdfDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClassHelper-PDF-Match").apply { priority = Thread.NORM_PRIORITY - 2 }
    }.asCoroutineDispatcher()

    private val eventScope = CoroutineScope(SupervisorJob(rootJob) + eventDispatcher)
    private val questionScope = CoroutineScope(SupervisorJob(rootJob) + questionDispatcher)
    private val noteScope = CoroutineScope(SupervisorJob(rootJob) + noteDispatcher)
    private val pdfScope = CoroutineScope(SupervisorJob(rootJob) + pdfDispatcher)
    private val watchdogScope = CoroutineScope(SupervisorJob(rootJob) + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob(rootJob) + Dispatchers.IO)
    private val uiScope = CoroutineScope(SupervisorJob(rootJob) + Dispatchers.Main.immediate)

    private lateinit var app: ClassHelperApp
    private lateinit var asr: StreamingAsrEngine
    private lateinit var audio: AudioCapture
    private lateinit var questions: QuestionPipeline
    private lateinit var notes: AutoNotePipeline
    private val detector = QuestionDetector()

    @Volatile private var started = false
    @Volatile private var stopping = false
    private val finishSequenceStarted = AtomicBoolean(false)
    private var sessionId: String? = null
    private var partialQuestionJob: Job? = null
    private var audioRestartJob: Job? = null
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = application as ClassHelperApp
        asr = newAsrEngine()
        audio = AudioCapture()
        questions = QuestionPipeline(this, questionScope)
        notes = AutoNotePipeline(app, noteScope)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            gracefulStop()
            return START_NOT_STICKY
        }
        if (!started) {
            startAsForeground()
            startListening()
        }
        return START_STICKY
    }

    private fun newAsrEngine(): StreamingAsrEngine =
        LocalZipformerAsrEngine(app.graph.asrModels) { buildAsrHotwords() }

    private fun startListening() {
        started = true
        stopping = false
        finishSequenceStarted.set(false)

        val active = app.graph.settings.activeSessionId
        sessionId = active?.takeIf { app.graph.db.getSession(it)?.endedAt == null } ?: run {
            val title = "课堂 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())}"
            app.graph.db.startSession(title, app.graph.settings.currentDocumentId).also {
                app.graph.settings.activeSessionId = it
            }
        }
        ClassroomBus.update {
            it.copy(
                listening = true,
                stopping = false,
                status = "正在启动 Zipformer 实时中文识别…",
                sessionId = sessionId,
            )
        }
        asr.start(this)
        startAudioCapture()
        startWatchdog()
    }

    private fun startAudioCapture() {
        if (stopping || !started || audio.isRunning()) return
        audio.start(
            onChunk = { chunk -> asr.sendPcm16(chunk) },
            onError = { scheduleAudioRestart(it) },
        )
    }

    private fun scheduleAudioRestart(cause: Throwable) {
        if (stopping || !started) return
        Log.w(TAG, "Audio capture stopped; scheduling recorder rebuild", cause)
        uiScope.launch {
            ClassroomBus.update { it.copy(status = "麦克风短暂中断 · 正在自动恢复录音…") }
        }
        ioScope.launch { updateNotification("麦克风短暂中断 · 正在自动恢复") }
        audioRestartJob?.cancel()
        audioRestartJob = watchdogScope.launch {
            runCatching { audio.stop() }
            delay(AUDIO_RESTART_DELAY_MS)
            if (stopping || !started) return@launch
            if (!audio.isRunning()) {
                uiScope.launch { ClassroomBus.update { it.copy(status = "正在重新连接麦克风…") } }
                startAudioCapture()
            }
        }
    }

    /**
     * Only AudioRecord is auto-rebuilt. Decoder health remains observable through LocalZipformer
     * status/backlog reporting, but we deliberately never stop/recreate it mid-class because stop()
     * clears its lossless PCM ring and can discard seconds of queued speech.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = watchdogScope.launch {
            while (isActive && started && !stopping) {
                delay(WATCHDOG_INTERVAL_MS)
                if (stopping || !started) break

                val captureHealth = audio.health()
                if (!captureHealth.running) {
                    scheduleAudioRestart(IllegalStateException("AudioRecord 已停止"))
                    continue
                }
                if (captureHealth.lastReadAgeMs > AUDIO_STALL_MS) {
                    scheduleAudioRestart(
                        IllegalStateException("AudioRecord ${captureHealth.lastReadAgeMs}ms 没有读取到新音频"),
                    )
                }
            }
        }
    }

    override fun onState(state: String) {
        if (stopping) return
        val snapshot = state
        uiScope.launch {
            if (!stopping) {
                ClassroomBus.update {
                    it.copy(status = snapshot, listening = true, stopping = false, sessionId = sessionId)
                }
            }
        }
        ioScope.launch { if (!stopping) updateNotification(snapshot) }
    }

    override fun onPartial(text: String) {
        if (stopping) return
        val snapshot = text
        val sid = sessionId
        uiScope.launch {
            if (!stopping) ClassroomBus.update { it.copy(partial = snapshot) }
        }

        partialQuestionJob?.cancel()
        if (snapshot.length >= 6) {
            partialQuestionJob = eventScope.launch {
                delay(PARTIAL_QUESTION_DEBOUNCE_MS)
                if (!stopping) detector.acceptPartial(snapshot)?.let { questions.answer(it, sid) }
            }
        }
    }

    override fun onFinal(text: String) {
        if (stopping && finishSequenceStarted.get()) return
        partialQuestionJob?.cancel()
        val clean = text.trim()
        if (clean.isBlank()) return
        val sid = sessionId
        val docId = app.graph.settings.currentDocumentId
        val page = app.graph.settings.currentPage

        uiScope.launch { ClassroomBus.update { it.copy(partial = "") } }
        eventScope.launch {
            try {
                app.graph.db.addTranscript(clean, sid, docId, page)
                uiScope.launch {
                    ClassroomBus.update { it.copy(historyVersion = it.historyVersion + 1) }
                }

                notes.onFinalTranscript(sid)
                detector.accept(clean)?.let { questions.answer(it, sid) }

                if (docId != null && clean.length >= 6) {
                    pdfScope.launch {
                        runCatching { app.graph.knowledge.matchPage(clean, docId) }
                            .onSuccess { match ->
                                if (match != null) {
                                    uiScope.launch {
                                        ClassroomBus.update { state ->
                                            state.copy(matchedPage = match.page, matchedLabel = match.label)
                                        }
                                    }
                                }
                            }
                            .onFailure { Log.w(TAG, "PDF page match failed", it) }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Async transcript processing failed", t)
            }
        }
    }

    override fun onError(message: String, cause: Throwable?) {
        if (cause != null) Log.e(TAG, message, cause) else Log.e(TAG, message)
        val snapshot = message
        uiScope.launch { ClassroomBus.update { it.copy(status = snapshot) } }
        ioScope.launch { updateNotification(snapshot) }
        // Do not destroy/recreate Zipformer automatically here. Keeping queued PCM is more important
        // than hiding a transient native error, and most recognizer exceptions are recoverable on the
        // next audio feed. A fresh classroom start remains the safe hard-reset boundary.
    }

    /**
     * Restore the high-quality contextual-bias profile used before the stability regressions.
     * User terms stay first, then the current PDF contributes its title, nearby headings, quoted
     * terminology and compact uppercase technical tokens. The bound keeps beam search predictable.
     */
    private fun buildAsrHotwords(): String {
        val terms = LinkedHashSet<String>()

        fun add(raw: String) {
            raw.split(HOTWORD_SPLIT).forEach { part ->
                val clean = part
                    .trim()
                    .removeSuffix(".pdf")
                    .removeSuffix(".PDF")
                    .replace('/', ' ')
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (clean.length !in 2..24) return@forEach
                if (clean.matches(Regex("(?i)^P?\\d{1,4}$"))) return@forEach
                if (clean.all { it.isDigit() }) return@forEach
                terms += clean
            }
        }

        add(app.graph.settings.hotwords)
        add(app.graph.settings.chaoxingCourseName)

        val docId = app.graph.settings.currentDocumentId
        if (docId != null) {
            app.graph.db.getDocument(docId)?.title?.let(::add)
            val chunks = runCatching {
                app.graph.db.chunksNearPage(docId, app.graph.settings.currentPage, radius = 4)
            }.getOrDefault(emptyList())

            chunks.forEach { chunk ->
                add(chunk.title)
                val text = chunk.text.take(12_000)
                QUOTED_TERM.findAll(text).forEach { match -> add(match.groupValues[1]) }
                TECH_TERM.findAll(text).forEach { match -> add(match.value) }
            }
        }

        return terms.asSequence().take(MAX_HOTWORDS).joinToString("/")
    }

    private fun gracefulStop() {
        if (stopping) return
        stopping = true
        watchdogJob?.cancel()
        audioRestartJob?.cancel()
        partialQuestionJob?.cancel()
        audio.stop()
        uiScope.launch {
            ClassroomBus.update { it.copy(listening = true, stopping = true, status = "正在停止录音并收尾…") }
        }
        ioScope.launch { updateNotification("录音已停止 · 正在收尾最后一句") }

        watchdogScope.launch {
            delay(ASR_FINISH_TIMEOUT_MS)
            eventScope.launch { continueStopSequence() }
        }
        runCatching {
            asr.finish {
                eventScope.launch { continueStopSequence() }
            }
        }.onFailure {
            eventScope.launch { continueStopSequence() }
        }
    }

    private fun continueStopSequence() {
        if (!finishSequenceStarted.compareAndSet(false, true)) return
        val finishingSession = sessionId
        if (app.graph.settings.autoNotes && finishingSession != null) {
            uiScope.launch {
                ClassroomBus.update { it.copy(listening = true, stopping = true, status = "正在整理最后课堂笔记…") }
            }
            ioScope.launch { updateNotification("录音已结束 · 正在整理最后课堂笔记") }
            val done = CompletableDeferred<Unit>()
            notes.summarizeNow(finishingSession) { done.complete(Unit) }
            watchdogScope.launch {
                withTimeoutOrNull(FINAL_NOTE_TIMEOUT_MS) { done.await() }
                eventScope.launch { finishSessionAndStop(finishingSession) }
            }
        } else {
            finishSessionAndStop(finishingSession)
        }
    }

    private fun finishSessionAndStop(finishingSession: String?) {
        runCatching { finishingSession?.let { app.graph.db.endSession(it) } }
            .onFailure { Log.e(TAG, "Unable to end classroom session", it) }
        app.graph.settings.activeSessionId = null
        sessionId = null
        stopSelf()
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        audioRestartJob?.cancel()
        partialQuestionJob?.cancel()
        audio.stop()
        runCatching { asr.stop() }
        if (started && !stopping) {
            app.graph.settings.activeSessionId = sessionId
        }
        started = false
        rootJob.cancel()
        eventScope.cancel()
        questionScope.cancel()
        noteScope.cancel()
        pdfScope.cancel()
        watchdogScope.cancel()
        ioScope.cancel()
        uiScope.cancel()
        runCatching { eventDispatcher.close() }
        runCatching { questionDispatcher.close() }
        runCatching { noteDispatcher.close() }
        runCatching { pdfDispatcher.close() }
        ClassroomBus.update {
            it.copy(listening = false, stopping = false, status = "未开始听课", partial = "", sessionId = null)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台听课", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持麦克风流式识别"
                setSound(null, null)
            },
        )
    }

    private fun startAsForeground() {
        val notification = buildNotification("正在后台听课")
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            11,
            Intent(this, ReaderActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            12,
            Intent(this, ClassroomService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_class)
            .setContentTitle("课堂助手正在听课")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "结束听课", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.github.paper.classhelper.STOP_CLASS"
        private const val CHANNEL_ID = "classhelper_listening"
        private const val NOTIFICATION_ID = 101
        private const val MAX_HOTWORDS = 48
        private const val AUDIO_RESTART_DELAY_MS = 700L
        private const val WATCHDOG_INTERVAL_MS = 2_500L
        private const val AUDIO_STALL_MS = 7_000L
        private const val PARTIAL_QUESTION_DEBOUNCE_MS = 650L
        private const val ASR_FINISH_TIMEOUT_MS = 6_000L
        private const val FINAL_NOTE_TIMEOUT_MS = 12_000L
        private val HOTWORD_SPLIT = Regex("[\\r\\n,，;；/]+")
        private val QUOTED_TERM = Regex("[《“「『【]([^》”」』】\\n]{2,24})[》”」』】]")
        private val TECH_TERM = Regex("\\b[A-Z][A-Z0-9+.#_-]{1,15}\\b")
        private const val TAG = "ClassroomService"
    }
}
