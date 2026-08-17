package io.github.paper.classhelper.classroom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.R
import io.github.paper.classhelper.asr.AsrModelManager
import io.github.paper.classhelper.ui.ReaderActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 14+ user-initiated data-transfer job for the ~230 MB SenseVoiceSmall model.
 *
 * UIDT is the platform API intended for a large download explicitly started by the user. Unlike the
 * previous direct dataSync foreground-service launch, scheduling the job doesn't synchronously enter
 * an OEM foreground-service implementation from the button click path.
 */
@RequiresApi(34)
class AsrModelDownloadJobService : JobService() {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClassHelper-SenseVoice-UIDT").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val stopped = AtomicBoolean(false)
    private lateinit var app: ClassHelperApp
    @Volatile private var params: JobParameters? = null

    override fun onCreate() {
        super.onCreate()
        app = application as ClassHelperApp
        createChannel()
    }

    override fun onStartJob(params: JobParameters): Boolean {
        this.params = params
        stopped.set(false)
        return try {
            setJobNotification(params, "准备下载 SenseVoiceSmall…", 0, true)
            worker.execute {
                try {
                    val result = app.graph.asrModels.performDownload { state ->
                        if (!stopped.get()) updateNotification(params, state)
                    }
                    if (!stopped.get()) {
                        when (result) {
                            is AsrModelManager.State.Ready -> setJobNotification(params, "模型已就绪", 100, false)
                            is AsrModelManager.State.Error -> setJobNotification(params, "下载暂停/失败：${result.message}", 0, false)
                            else -> Unit
                        }
                        jobFinished(params, false)
                    }
                } catch (t: Throwable) {
                    // Never let an uncaught worker exception terminate the whole app process.
                    app.graph.asrModels.reportState(
                        AsrModelManager.State.Error("后台下载异常：${t.message ?: t.javaClass.simpleName}")
                    )
                    runCatching { jobFinished(params, true) }
                }
            }
            true
        } catch (t: Throwable) {
            app.graph.asrModels.reportState(
                AsrModelManager.State.Error("系统后台任务启动失败：${t.message ?: t.javaClass.simpleName}")
            )
            false
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped.set(true)
        app.graph.asrModels.cancelFromService()
        // Partial .part files are durable; system-initiated stops may be retried. Explicit app cancel()
        // removes the job from JobScheduler, so returning true here does not resurrect a user pause.
        return true
    }

    override fun onDestroy() {
        stopped.set(true)
        app.graph.asrModels.cancelFromService()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun updateNotification(params: JobParameters, state: AsrModelManager.State) {
        when (state) {
            is AsrModelManager.State.Preparing -> setJobNotification(params, state.message, 0, true)
            is AsrModelManager.State.Downloading -> setJobNotification(
                params,
                "${state.fileIndex}/${state.fileCount} ${state.fileName} · ${state.overallPercent}%",
                state.overallPercent,
                false
            )
            is AsrModelManager.State.Ready -> setJobNotification(params, "模型已就绪", 100, false)
            is AsrModelManager.State.Error -> setJobNotification(params, "下载失败：${state.message}", 0, false)
            AsrModelManager.State.Missing -> Unit
        }
    }

    private fun setJobNotification(params: JobParameters, text: String, progress: Int, indeterminate: Boolean) {
        val openIntent = PendingIntent.getActivity(
            this,
            2304,
            Intent(this, ReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_class)
            .setContentTitle("课堂助手 · SenseVoiceSmall")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(progress in 0..99)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()

        setNotification(
            params,
            NOTIFICATION_ID,
            notification,
            JobService.JOB_END_NOTIFICATION_POLICY_DETACH
        )
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

    companion object {
        const val JOB_ID = 2303
        private const val CHANNEL_ID = "sensevoice_model_uidt"
        private const val NOTIFICATION_ID = 2303

        fun schedule(context: Context, estimatedBytes: Long): Int {
            val scheduler = context.getSystemService(JobScheduler::class.java)
                ?: return JobScheduler.RESULT_FAILURE
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, AsrModelDownloadJobService::class.java)
            )
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresStorageNotLow(true)
                .setEstimatedNetworkBytes(estimatedBytes, 0L)
                .build()
            return scheduler.schedule(info)
        }
    }
}
