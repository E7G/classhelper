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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground classroom recorder.
 *
 * The ASR callback path is intentionally tiny: callbacks only snapshot state and enqueue work.
 * Database writes, question detection/LLM, auto notes, and PDF matching run on independent async
 * lanes so none of them can pause the Zipformer decoder or microphone capture.
 */
class ClassroomService : Service(), StreamingAsrEngine.Listener {
    private val rootJob = SupervisorJob()

    private val eventExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ClassHelper-ASR-Events").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val questionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ClassHelper-Questions").apply { priority = Thread.NORM_PRIORITY - 2 }
    }
    private val noteExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ClassHelper-AutoNotes").apply { priority = Thread.NORM_PRIORITY - 2 }
    }

    private val eventDispatcher = eventExecutor.asCoroutineDispatcher()
    private val questionDispatcher = questionExecutor.asCoroutineDispatcher()
    private val noteDispatcher = noteExecutor.asCoroutineDispatcher()

    /** Ordered lane for transcript persistence + detector state. Never runs on the ASR worker. */
    private val asrEventScope = CoroutineScope(SupervisorJob(rootJob) + eventDispatcher)
    /** Dedicated serial lane for retrieval + LLM answering. */
    private val questionScope = CoroutineScope(SupervisorJob(rootJob) + questionDispatcher)
    /** Dedicated serial lane for automatic note generation. */
    private val noteScope = CoroutineScope(SupervisorJob(rootJob) + noteDispatcher)
    /** Miscellaneous work such as PDF page matching and stop timers. */
    private val backgroundScope = CoroutineScope(SupervisorJob(rootJob) + Dispatchers.Default)
    /** UI/notification updates are posted off the ASR callback thread as well. */
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
    @Volatile private var partialQuestionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = application as ClassHelperApp
        asr = LocalZipformerAsrEngine(app.graph.asrModels) { buildAsrHotwords() }
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

    private fun startListening() {
        started = true
        stopping = false
        finishSequenceStarted.set(false)
        val active = app.graph.settings.activeSessionId
        sessionId = active?.takeIf { app.graph.db.getSession(it)?.endedAt == null } ?: run {
            val title = "课堂 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())}"
            app.graph.db.startSession(title, app.graph.settings.currentDocumentId).also { app.graph.settings.activeSessionId = it }
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
        audio.start(onChunk = { asr.sendPcm16(it) }, onError = { onError("录音失败：${it.message}", it) })
    }

    /** ASR worker only posts the UI state and immediately returns. */
    override fun onState(state: String) {
        if (stopping) return
        uiScope.launch {
            if (stopping) return@launch
            ClassroomBus.update { it.copy(status = state, listening = true, stopping = false, sessionId = sessionId) }
            updateNotification(state)
        }
    }

    /**
     * Partial text never performs question detection on the recognizer thread. Only the latest
     * partial is debounced on the ordered event lane; answering itself is sent to questionScope.
     */
    override fun onPartial(text: String) {
        val snapshot = text
        val sid = sessionId
        uiScope.launch { ClassroomBus.update { it.copy(partial = snapshot) } }

        partialQuestionJob?.cancel()
        if (snapshot.length >= 6) {
            partialQuestionJob = asrEventScope.launch {
                delay(650)
                detector.acceptPartial(snapshot)?.let { questions.answer(it, sid) }
            }
        }
    }

    /**
     * Final ASR callbacks are enqueue-only. All persistence and downstream intelligence is async,
     * so a slow database, retrieval, network request, LLM stream, or note job cannot stop Zipformer.
     */
    override fun onFinal(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return

        partialQuestionJob?.cancel()
        val sid = sessionId
        val docId = app.graph.settings.currentDocumentId
        val page = app.graph.settings.currentPage

        uiScope.launch { ClassroomBus.update { it.copy(partial = "") } }
        asrEventScope.launch {
            try {
                app.graph.db.addTranscript(clean, sid, docId, page)
                uiScope.launch { ClassroomBus.update { it.copy(historyVersion = it.historyVersion + 1) } }

                // These methods only enqueue into their own independent lanes.
                notes.onFinalTranscript(sid)
                detector.accept(clean)?.let { questions.answer(it, sid) }

                if (docId != null && clean.length >= 6) {
                    backgroundScope.launch {
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
                // A business-layer failure is logged only; ASR keeps running and is not put into an
                // error/stopped state because recognition itself is still healthy.
                Log.e(TAG, "Async transcript processing failed", t)
            }
        }
    }

    /** Recognition errors are reported asynchronously; this callback never blocks the ASR worker. */
    override fun onError(message: String, cause: Throwable?) {
        if (cause != null) Log.e(TAG, message, cause) else Log.e(TAG, message)
        uiScope.launch {
            ClassroomBus.update { it.copy(status = message) }
            updateNotification(message)
        }
    }

    /**
     * Build a small contextual-bias list for the streaming transducer. User-entered terms are kept
     * first, then the current PDF contributes its title, nearby section titles, quoted terminology,
     * and compact uppercase technical tokens. Keeping the list small avoids slowing beam search.
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

        val docId = app.graph.settings.currentDocumentId
        if (docId != null) {
            app.graph.db.getDocument(docId)?.title?.let(::add)
            val chunks = runCatching {
                app.graph.db.chunksNearPage(docId, app.graph.settings.currentPage, radius = 4)
            }.getOrDefault(emptyList())

            chunks.forEach { chunk ->
                add(chunk.title)
                val chunkText = chunk.text.take(12_000)
                QUOTED_TERM.findAll(chunkText).forEach { match -> add(match.groupValues[1]) }
                TECH_TERM.findAll(chunkText).forEach { match -> add(match.value) }
            }
        }

        return terms.asSequence().take(MAX_HOTWORDS).joinToString("/")
    }

    private fun gracefulStop() {
        if (stopping) return
        stopping = true
        audio.stop()
        partialQuestionJob?.cancel()
        ClassroomBus.update { it.copy(listening = true, stopping = true, status = "正在停止录音并收尾…") }
        updateNotification("录音已停止 · 正在收尾最后一句")

        backgroundScope.launch {
            delay(6_000L)
            continueStopSequence()
        }
        runCatching { asr.finish { continueStopSequence() } }
            .onFailure { continueStopSequence() }
    }

    private fun continueStopSequence() {
        if (!finishSequenceStarted.compareAndSet(false, true)) return
        val finishingSession = sessionId
        if (app.graph.settings.autoNotes && finishingSession != null) {
            uiScope.launch {
                ClassroomBus.update { it.copy(listening = true, stopping = true, status = "正在整理最后课堂笔记…") }
                updateNotification("录音已结束 · 正在整理最后课堂笔记")
            }
            val done = CompletableDeferred<Unit>()
            notes.summarizeNow(finishingSession) { done.complete(Unit) }
            backgroundScope.launch {
                withTimeoutOrNull(12_000L) { done.await() }
                finishSessionAndStop(finishingSession)
            }
        } else {
            backgroundScope.launch { finishSessionAndStop(finishingSession) }
        }
    }

    private fun finishSessionAndStop(finishingSession: String?) {
        runCatching { finishingSession?.let { app.graph.db.endSession(it) } }
            .onFailure { Log.e(TAG, "Failed to finish classroom session", it) }
        app.graph.settings.activeSessionId = null
        sessionId = null
        stopSelf()
    }

    override fun onDestroy() {
        audio.stop()
        partialQuestionJob?.cancel()
        asr.stop()
        if (started && !stopping) {
            app.graph.settings.activeSessionId = sessionId
        }
        started = false
        rootJob.cancel()
        eventDispatcher.close()
        questionDispatcher.close()
        noteDispatcher.close()
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
        private const val TAG = "ClassroomService"
        private val HOTWORD_SPLIT = Regex("[\\r\\n,，;；/]+")
        private val QUOTED_TERM = Regex("[《“「『【]([^》”」』】\\n]{2,24})[》”」』】]")
        private val TECH_TERM = Regex("\\b[A-Z][A-Z0-9+.#_-]{1,15}\\b")
    }
}
