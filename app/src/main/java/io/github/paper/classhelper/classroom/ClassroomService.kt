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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class ClassroomService : Service(), StreamingAsrEngine.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val finalMutex = Mutex()
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

    override fun onCreate() {
        super.onCreate()
        app = application as ClassHelperApp
        asr = LocalZipformerAsrEngine(app.graph.asrModels) { buildAsrHotwords() }
        audio = AudioCapture()
        questions = QuestionPipeline(this, scope)
        notes = AutoNotePipeline(app, scope)
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
        startAudioCapture()
    }

    private fun startAudioCapture() {
        if (stopping || !started || audio.isRunning()) return
        audio.start(
            onChunk = { asr.sendPcm16(it) },
            onError = { scheduleAudioRestart(it) },
        )
    }

    /**
     * AudioRecord can occasionally die after routing, vendor power-management or driver errors.
     * Recover the recorder independently so the class session and Zipformer stream keep running.
     */
    private fun scheduleAudioRestart(cause: Throwable) {
        if (stopping || !started) return
        Log.w(TAG, "Audio capture stopped; scheduling recorder rebuild", cause)
        ClassroomBus.update { it.copy(status = "麦克风短暂中断 · 正在自动恢复录音…") }
        scope.launch(Dispatchers.IO) { updateNotification("麦克风短暂中断 · 正在自动恢复") }
        audioRestartJob?.cancel()
        audioRestartJob = scope.launch {
            delay(AUDIO_RESTART_DELAY_MS)
            if (stopping || !started) return@launch
            if (!audio.isRunning()) {
                ClassroomBus.update { it.copy(status = "正在重新连接麦克风…") }
                startAudioCapture()
            }
        }
    }

    override fun onState(state: String) {
        if (stopping) return
        ClassroomBus.update { it.copy(status = state, listening = true, stopping = false, sessionId = sessionId) }
        // NotificationManager is a Binder call. Never make the Zipformer decoder wait on it.
        scope.launch(Dispatchers.IO) {
            if (!stopping) updateNotification(state)
        }
    }

    override fun onPartial(text: String) {
        ClassroomBus.update { it.copy(partial = text) }
        partialQuestionJob?.cancel()
        if (text.length >= 6) {
            partialQuestionJob = scope.launch {
                delay(650)
                detector.acceptPartial(text)?.let { questions.answer(it, sessionId) }
            }
        }
    }

    override fun onFinal(text: String) {
        partialQuestionJob?.cancel()
        val clean = text.trim()
        if (clean.isBlank()) return
        val sid = sessionId
        val docId = app.graph.settings.currentDocumentId
        val page = app.graph.settings.currentPage

        // Return to the decoder immediately. Ordered DB/business work is serialized by one Mutex on
        // the existing service scope; this is deliberately not the old multi-executor architecture.
        ClassroomBus.update { it.copy(partial = "") }
        scope.launch(Dispatchers.IO) {
            finalMutex.withLock {
                try {
                    app.graph.db.addTranscript(clean, sid, docId, page)
                    ClassroomBus.update { it.copy(historyVersion = it.historyVersion + 1) }
                    notes.onFinalTranscript(sid)
                    detector.accept(clean)?.let { questions.answer(it, sid) }

                    if (docId != null && clean.length >= 6) {
                        scope.launch(Dispatchers.Default) {
                            runCatching { app.graph.knowledge.matchPage(clean, docId) }
                                .onSuccess { match ->
                                    if (match != null) {
                                        ClassroomBus.update { state ->
                                            state.copy(matchedPage = match.page, matchedLabel = match.label)
                                        }
                                    }
                                }
                                .onFailure { Log.w(TAG, "PDF page match failed", it) }
                        }
                    }
                } catch (t: Throwable) {
                    // Business persistence must never turn a healthy recognizer into an ASR error.
                    Log.e(TAG, "Transcript post-processing failed", t)
                }
            }
        }
    }

    override fun onError(message: String, cause: Throwable?) {
        if (cause != null) Log.e(TAG, message, cause) else Log.e(TAG, message)
        ClassroomBus.update { it.copy(status = message) }
        scope.launch(Dispatchers.IO) { updateNotification(message) }
    }

    /**
     * Keep contextual beam search opt-in. Automatic PDF term extraction used to make almost every
     * class enter modified-beam mode with dozens of terms, which can fall behind realtime on
     * thermally throttled tablets. Explicit user hotwords still get the accuracy boost.
     */
    private fun buildAsrHotwords(): String {
        val terms = LinkedHashSet<String>()
        app.graph.settings.hotwords.split(HOTWORD_SPLIT).forEach { part ->
            val clean = part
                .trim()
                .replace('/', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.length !in 2..24) return@forEach
            if (clean.matches(Regex("(?i)^P?\\d{1,4}$"))) return@forEach
            if (clean.all { it.isDigit() }) return@forEach
            terms += clean
        }
        return terms.asSequence().take(MAX_HOTWORDS).joinToString("/")
    }

    private fun gracefulStop() {
        if (stopping) return
        stopping = true
        audioRestartJob?.cancel()
        audio.stop()
        partialQuestionJob?.cancel()
        ClassroomBus.update { it.copy(listening = true, stopping = true, status = "正在停止录音并收尾…") }
        scope.launch(Dispatchers.IO) { updateNotification("录音已停止 · 正在收尾最后一句") }

        scope.launch {
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
            ClassroomBus.update { it.copy(listening = true, stopping = true, status = "正在整理最后课堂笔记…") }
            scope.launch(Dispatchers.IO) { updateNotification("录音已结束 · 正在整理最后课堂笔记") }
            val done = CompletableDeferred<Unit>()
            AutoNotePipeline(app, app.applicationScope).summarizeNow(finishingSession) { done.complete(Unit) }
            scope.launch {
                withTimeoutOrNull(12_000L) { done.await() }
                finishSessionAndStop(finishingSession)
            }
        } else {
            finishSessionAndStop(finishingSession)
        }
    }

    private fun finishSessionAndStop(finishingSession: String?) {
        finishingSession?.let { app.graph.db.endSession(it) }
        app.graph.settings.activeSessionId = null
        sessionId = null
        stopSelf()
    }

    override fun onDestroy() {
        audioRestartJob?.cancel()
        audio.stop()
        partialQuestionJob?.cancel()
        asr.stop()
        if (started && !stopping) {
            app.graph.settings.activeSessionId = sessionId
        }
        started = false
        scope.cancel()
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
        private const val MAX_HOTWORDS = 24
        private const val AUDIO_RESTART_DELAY_MS = 700L
        private val HOTWORD_SPLIT = Regex("[\\r\\n,，;；/]+")
        private const val TAG = "ClassroomService"
    }
}
