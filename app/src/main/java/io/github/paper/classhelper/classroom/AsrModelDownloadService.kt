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
import io.github.paper.classhelper.asr.AsrModelManager
import io.github.paper.classhelper.ui.ReaderActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-at-a-time, resumable SenseVoice model downloader.
 *
 * This replaces Android DownloadManager because several OEM DownloadProvider implementations crash
 * when seven large app-private requests are enqueued together. The service starts only after a user
 * taps Download, enters foreground immediately, and keeps running while the app is backgrounded.
 */
class AsrModelDownloadService : Service() {
    private lateinit var app: ClassHelperApp
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClassHelper-SenseVoice-Download").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val started = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        app = application as ClassHelperApp
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            app.graph.asrModels.cancelFromService()
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 8+ requires startForeground quickly after startForegroundService(). Do this before
        // touching the model directory or network so a slow storage mount can never trigger ANR/kill.
        showForeground("准备下载 SenseVoiceSmall…", 0, true, ongoing = true)

        if (started.compareAndSet(false, true)) {
            worker.execute {
                val result = app.graph.asrModels.performDownload(::updateNotification)
                when (result) {
                    is AsrModelManager.State.Ready -> showForeground("模型已就绪", 100, false, ongoing = false)
                    is AsrModelManager.State.Error -> showForeground(result.message, 0, false, ongoing = false)
                    else -> Unit
                }
                stopSelf(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        app.graph.asrModels.cancelFromService()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        app.graph.asrModels.cancelFromService()
        stopSelf(startId)
    }

    private fun updateNotification(state: AsrModelManager.State) {
        when (state) {
            is AsrModelManager.State.Preparing -> showForeground(state.message, 0, true, ongoing = true)
            is AsrModelManager.State.Downloading -> showForeground(
                "${state.fileIndex}/${state.fileCount} ${state.fileName} · ${state.overallPercent}%",
                state.overallPercent,
                false,
                ongoing = true
            )
            is AsrModelManager.State.Ready -> showForeground("模型已就绪", 100, false, ongoing = false)
            is AsrModelManager.State.Error -> showForeground("下载失败：${state.message}", 0, false, ongoing = false)
            AsrModelManager.State.Missing -> Unit
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "语音模型下载", NotificationManager.IMPORTANCE_LOW).apply {
                description = "SenseVoiceSmall 本地语音模型后台下载"
                setSound(null, null)
            }
        )
    }

    private fun showForeground(text: String, progress: Int, indeterminate: Boolean, ongoing: Boolean) {
        val openIntent = PendingIntent.getActivity(
            this,
            2204,
            Intent(this, ReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this,
            2205,
            Intent(this, AsrModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_class)
            .setContentTitle("课堂助手 · SenseVoiceSmall")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
        if (ongoing) builder.addAction(0, "暂停", cancelIntent)
        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "io.github.paper.classhelper.DOWNLOAD_SENSEVOICE_MODEL"
        const val ACTION_CANCEL = "io.github.paper.classhelper.CANCEL_SENSEVOICE_MODEL_DOWNLOAD"
        private const val CHANNEL_ID = "sensevoice_model_download"
        private const val NOTIFICATION_ID = 2203
    }
}
