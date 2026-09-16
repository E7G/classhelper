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
import io.github.paper.classhelper.asr.LocalSenseVoiceAsrEngine
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
 * SenseVoice runs on its own VAD/audio/decode workers. Database/question/note/PDF work stays on
 * independent consumer lanes. The watchdog only repairs AudioRecord; it never resets ASR workers
 * mid-class, which could discard queued classroom audio.
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
    @Volatile private var speechActive = false
    @Volatile private var speechEpoch = 0L
    private val finishSequenceStarted = AtomicBoolean(false)
    private var sessionId: String? = null
    private var pendingWeakQuestionJob: Job? = null
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

    private fun newAsrEngine(): StreamingAsrEngine = LocalSenseVoiceAsrEngine(app.graph.asrModels)

    private fun startListening() {
        started = true
        stopping = false
        speechActive = false
        speechEpoch = 0L
        pendingWeakQuestionJob?.cancel()
        pendingWeakQuestionJob = null
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
                status = "正在启动 SenseVoice 本地中文识别…",
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
     * Only AudioRecord is auto-rebuilt. ASR workers remain observable through engine status, but are
     * never restarted mid-class because doing so can discard queued speech/VAD segments.
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

    override fun onSpeechStart() {
        if (stopping) return
        speechActive = true
        speechEpoch += 1L
        // A weak candidate is only useful when the teacher truly leaves room for an answer. As soon
        // as VAD sees speech resume, drop it rather than converting an ordinary lecture pause into a
        // false classroom question.
        pendingWeakQuestionJob?.cancel()
        pendingWeakQuestionJob = null
    }

    override fun onSpeechEnd() {
        speechActive = false
    }

    override fun onPartial(text: String) {
        if (stopping) return
        val snapshot = text
        uiScope.launch {
            if (!stopping) ClassroomBus.update { it.copy(partial = snapshot) }
        }
        // SenseVoice does not normally produce live partials. If an engine does provide one later,
        // keep it UI-only and never trigger question answering until stable final text arrives.
    }

    override fun onFinal(text: String) {
        if (stopping && finishSequenceStarted.get()) return
        val clean = text.trim()
        if (clean.isBlank()) return
        val sid = sessionId
        val docId = app.graph.settings.currentDocumentId
        val page = app.graph.settings.currentPage

        uiScope.launch { ClassroomBus.update { it.copy(partial = "") } }

        // Every new final supersedes a previously ambiguous candidate. Strong questions go straight
        // through. Weak questions get a short speech-aware confirmation window; this is not a fixed
        // delay for all questions, and VAD cancels it immediately if the teacher resumes speaking.
        pendingWeakQuestionJob?.cancel()
        pendingWeakQuestionJob = null
        detector.classify(clean)?.let { candidate ->
            when (candidate.confidence) {
                QuestionDetector.Confidence.STRONG -> {
                    detector.commit(candidate)?.let { questions.answer(it, sid) }
                }
                QuestionDetector.Confidence.WEAK -> {
                    if (!speechActive) {
                        val expectedSpeechEpoch = speechEpoch
                        pendingWeakQuestionJob = eventScope.launch {
                            delay(WEAK_QUESTION_CONFIRM_MS)
                            if (!stopping && !speechActive && speechEpoch == expectedSpeechEpoch) {
                                detector.commit(candidate)?.let { questions.answer(it, sid) }
                            }
                            pendingWeakQuestionJob = null
                        }
                    }
                }
            }
        }

        eventScope.launch {
            try {
                app.graph.db.addTranscript(clean, sid, docId, page)
                uiScope.launch {
                    ClassroomBus.update { it.copy(historyVersion = it.historyVersion + 1) }
                }

                notes.onFinalTranscript(sid)

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
        // Do not destroy/recreate ASR workers automatically here. Keeping queued PCM/VAD segments is
        // more important than hiding a transient native error; a fresh classroom start is the safe
        // hard-reset boundary.
    }

    private fun gracefulStop() {
        if (stopping) return
        stopping = true
        watchdogJob?.cancel()
        audioRestartJob?.cancel()
        pendingWeakQuestionJob?.cancel()
        pendingWeakQuestionJob = null
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
        pendingWeakQuestionJob?.cancel()
        pendingWeakQuestionJob = null
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
                description = "保持麦克风采集并进行本地 VAD 识别"
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
        private const val AUDIO_RESTART_DELAY_MS = 700L
        private const val WATCHDOG_INTERVAL_MS = 2_500L
        private const val AUDIO_STALL_MS = 7_000L
        private const val WEAK_QUESTION_CONFIRM_MS = 900L
        private const val ASR_FINISH_TIMEOUT_MS = 6_000L
        private const val FINAL_NOTE_TIMEOUT_MS = 12_000L
        private const val TAG = "ClassroomService"
    }
}
