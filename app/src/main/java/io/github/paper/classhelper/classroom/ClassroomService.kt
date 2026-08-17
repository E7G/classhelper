package io.github.paper.classhelper.classroom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.R
import io.github.paper.classhelper.asr.LocalSenseVoiceAsrEngine
import io.github.paper.classhelper.asr.StreamingAsrEngine
import io.github.paper.classhelper.audio.AudioCapture
import io.github.paper.classhelper.ui.ReaderActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ClassroomService : Service(), StreamingAsrEngine.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    override fun onCreate() {
        super.onCreate()
        app = application as ClassHelperApp
        asr = LocalSenseVoiceAsrEngine(app.graph.asrModels)
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
        ClassroomBus.update { it.copy(listening = true, stopping = false, status = "正在启动本地语音识别…", sessionId = sessionId) }
        asr.start(this)
        audio.start(onChunk = { asr.sendPcm16(it) }, onError = { onError("录音失败：${it.message}", it) })
    }

    override fun onState(state: String) {
        if (stopping) return
        ClassroomBus.update { it.copy(status = state, listening = true, stopping = false, sessionId = sessionId) }
        updateNotification(state)
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
        val docId = app.graph.settings.currentDocumentId
        val page = app.graph.settings.currentPage
        app.graph.db.addTranscript(clean, sessionId, docId, page)
        ClassroomBus.update { it.copy(partial = "", historyVersion = it.historyVersion + 1) }
        notes.onFinalTranscript(sessionId)
        detector.accept(clean)?.let { questions.answer(it, sessionId) }

        if (docId != null && clean.length >= 6) {
            scope.launch(Dispatchers.Default) {
                app.graph.knowledge.matchPage(clean, docId)?.let { match ->
                    ClassroomBus.update { state -> state.copy(matchedPage = match.page, matchedLabel = match.label) }
                }
            }
        }
    }

    override fun onError(message: String, cause: Throwable?) {
        ClassroomBus.update { it.copy(status = message) }
        updateNotification(message)
    }

    private fun gracefulStop() {
        if (stopping) return
        stopping = true
        audio.stop()
        partialQuestionJob?.cancel()
        ClassroomBus.update { it.copy(listening = true, stopping = true, status = "正在停止录音并收尾…") }
        updateNotification("录音已停止 · 正在收尾最后一句")

        // A backend finish callback should normally arrive quickly, but never let a missed
        // callback leave the foreground service and UI stuck in “结束听课” forever.
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
            updateNotification("录音已结束 · 正在整理最后课堂笔记")
            val done = CompletableDeferred<Unit>()
            // Keep the actual LLM request in process scope so a service teardown cannot cancel it.
            AutoNotePipeline(app, app.applicationScope).summarizeNow(finishingSession) { done.complete(Unit) }
            scope.launch {
                // Do not keep the microphone foreground service around indefinitely on slow networks.
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
        audio.stop()
        partialQuestionJob?.cancel()
        asr.stop()
        if (started && !stopping) {
            // Unexpected process/service teardown: keep session open so START_STICKY can resume it.
            app.graph.settings.activeSessionId = sessionId
        }
        started = false
        scope.cancel()
        ClassroomBus.update { it.copy(listening = false, stopping = false, status = "未开始听课", partial = "", sessionId = null) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台听课", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持麦克风流式识别"; setSound(null, null)
            }
        )
    }

    private fun startAsForeground() {
        val notification = buildNotification("正在后台听课")
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 11, Intent(this, ReaderActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 12, Intent(this, ClassroomService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
    }
}
