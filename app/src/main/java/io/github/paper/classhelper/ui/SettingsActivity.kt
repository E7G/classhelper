package io.github.paper.classhelper.ui

import android.os.Bundle
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.paper.classhelper.ClassHelperApp
import io.github.paper.classhelper.R
import io.github.paper.classhelper.asr.AsrModelManager
import io.github.paper.classhelper.ocr.OcrModelManager
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var app: ClassHelperApp
    private lateinit var modelStatus: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var modelAction: Button
    private lateinit var modelDelete: Button
    private lateinit var ocrModelStatus: TextView
    private lateinit var ocrModelProgress: ProgressBar
    private lateinit var ocrModelAction: Button
    private lateinit var ocrModelDelete: Button

    private val modelListener: (AsrModelManager.State) -> Unit = { state ->
        runOnUiThread { if (!isDestroyed) renderModelState(state) }
    }
    private val ocrModelListener: (OcrModelManager.State) -> Unit = { state ->
        runOnUiThread { if (!isDestroyed) renderOcrModelState(state) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        app = application as ClassHelperApp
        val s = app.graph.settings

        val hot = findViewById<EditText>(R.id.hotwordsEdit)
        val base = findViewById<EditText>(R.id.llmBaseUrlEdit)
        val key = findViewById<EditText>(R.id.llmKeyEdit)
        val model = findViewById<EditText>(R.id.llmModelEdit)
        val auto = findViewById<CheckBox>(R.id.autoNotesCheck)
        val ocr = findViewById<CheckBox>(R.id.autoOcrCheck)
        val ocrHigh = findViewById<MaterialSwitch>(R.id.ocrHighAccuracyCheck)
        val notify = findViewById<CheckBox>(R.id.showAnswerNotificationCheck)

        modelStatus = findViewById(R.id.asrModelStatusText)
        modelProgress = findViewById(R.id.asrModelProgress)
        modelAction = findViewById(R.id.asrModelActionButton)
        modelDelete = findViewById(R.id.asrModelDeleteButton)
        findViewById<TextView>(R.id.asrModelNameText).text = app.graph.asrModels.model.displayName
        ocrModelStatus = findViewById(R.id.ocrModelStatusText)
        ocrModelProgress = findViewById(R.id.ocrModelProgress)
        ocrModelAction = findViewById(R.id.ocrModelActionButton)
        ocrModelDelete = findViewById(R.id.ocrModelDeleteButton)

        hot.setText(s.hotwords)
        base.setText(s.llmBaseUrl)
        key.setText(s.llmApiKey)
        model.setText(s.llmModel)
        auto.isChecked = s.autoNotes
        ocr.isChecked = s.autoOcr
        ocrHigh.isChecked = s.ocrHighAccuracy
        notify.isChecked = s.showAnswerNotification

        modelAction.setOnClickListener {
            when (app.graph.asrModels.currentState()) {
                is AsrModelManager.State.Preparing,
                is AsrModelManager.State.Downloading -> app.graph.asrModels.cancelDownload()
                is AsrModelManager.State.Ready -> Unit
                else -> app.graph.asrModels.download()
            }
        }
        modelDelete.setOnClickListener {
            Md3eDialogUi.showConfirm(
                context = this,
                title = "删除本地语音模型？",
                message = "删除后会释放约 230 MB 空间；下次听课前需要重新下载。",
                positiveLabel = "删除",
                danger = true,
            ) {
                if (app.graph.asrModels.delete()) Toast.makeText(this, "本地语音模型已删除", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this, "模型删除失败", Toast.LENGTH_SHORT).show()
            }
        }

        ocrModelAction.setOnClickListener {
            when (app.graph.ocrModels.currentState()) {
                is OcrModelManager.State.Preparing, is OcrModelManager.State.Downloading -> app.graph.ocrModels.cancelDownload()
                is OcrModelManager.State.Ready -> Unit
                else -> app.graph.ocrModels.download()
            }
        }
        ocrModelDelete.setOnClickListener {
            Md3eDialogUi.showConfirm(
                context = this, title = "删除 PP-OCRv6 模型？",
                message = "只删除应用私有的高精度 OCR 模型。扫描 PDF 仍可自动回退 ML Kit。",
                positiveLabel = "删除", danger = true
            ) {
                if (app.graph.ocrModels.delete()) Toast.makeText(this, "高精度 OCR 模型已删除", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this, "OCR 模型正在使用或删除失败", Toast.LENGTH_SHORT).show()
            }
        }

        val llmTestButton = findViewById<Button>(R.id.testLlmApiButton)
        val llmTestStatus = findViewById<TextView>(R.id.llmTestStatusText)
        llmTestButton.setOnClickListener {
            val baseNow = base.text.toString().trim()
            val keyNow = key.text.toString()
            val modelNow = model.text.toString().trim()
            llmTestButton.isEnabled = false; llmTestButton.text = "正在测试…"
            llmTestStatus.text = "正在发送最小真实请求，测试地址、鉴权、模型和返回格式…"
            lifecycleScope.launch {
                runCatching { app.graph.llm.testConnection(baseNow, keyNow, modelNow) }
                    .onSuccess { r -> llmTestStatus.text = "可用 · ${r.latencyMs} ms · ${r.model} · 返回：${r.preview}" }
                    .onFailure { e -> llmTestStatus.text = "不可用 · ${e.message ?: e.javaClass.simpleName}" }
                llmTestButton.isEnabled = true; llmTestButton.text = "重新测试 LLM API"
            }
        }

        findViewById<Button>(R.id.settingsBackButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.openLibraryButton).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener {
            s.hotwords = hot.text.toString()
            s.llmBaseUrl = base.text.toString()
            s.llmApiKey = key.text.toString()
            s.llmModel = model.text.toString()
            s.autoNotes = auto.isChecked
            s.autoOcr = ocr.isChecked
            s.ocrHighAccuracy = ocrHigh.isChecked
            s.showAnswerNotification = notify.isChecked
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        app.graph.asrModels.addListener(modelListener)
        app.graph.asrModels.refresh()
        app.graph.ocrModels.addListener(ocrModelListener)
        app.graph.ocrModels.refresh()
    }

    override fun onStop() {
        app.graph.asrModels.removeListener(modelListener)
        app.graph.ocrModels.removeListener(ocrModelListener)
        super.onStop()
    }

    private fun renderModelState(state: AsrModelManager.State) {
        when (state) {
            AsrModelManager.State.Missing -> {
                modelStatus.text = "未安装 · 首次约 230 MB，下载完成后完全本地识别"
                modelProgress.visibility = View.GONE
                modelAction.isEnabled = true
                modelAction.text = "后台下载模型"
                modelDelete.visibility = View.GONE
            }
            is AsrModelManager.State.Preparing -> {
                modelStatus.text = state.message + " · 可以离开此页面"
                modelProgress.visibility = View.VISIBLE
                modelProgress.isIndeterminate = true
                modelAction.isEnabled = true
                modelAction.text = "取消下载"
                modelDelete.visibility = View.GONE
            }
            is AsrModelManager.State.Downloading -> {
                val doneMb = state.downloaded / 1024.0 / 1024.0
                val totalMb = state.total / 1024.0 / 1024.0
                modelStatus.text = String.format(Locale.getDefault(), "后台下载 %d%% · %s · %s  (%.1f / %.1f MB)", state.overallPercent, state.fileName, state.source, doneMb, totalMb)
                modelProgress.visibility = View.VISIBLE
                modelProgress.isIndeterminate = false
                modelProgress.progress = state.overallPercent
                modelAction.isEnabled = true
                modelAction.text = "取消下载"
                modelDelete.visibility = View.GONE
            }
            is AsrModelManager.State.Ready -> {
                val mb = state.totalBytes / 1024.0 / 1024.0
                modelStatus.text = String.format(Locale.getDefault(), "已就绪 · %.1f MB · SenseVoiceSmall 本地识别，可直接开始听课", mb)
                modelProgress.visibility = View.GONE
                modelProgress.isIndeterminate = false
                modelAction.text = "已安装"
                modelAction.isEnabled = false
                modelDelete.visibility = View.VISIBLE
            }
            is AsrModelManager.State.Error -> {
                modelStatus.text = "下载失败：${state.message}"
                modelProgress.visibility = View.GONE
                modelProgress.isIndeterminate = false
                modelAction.isEnabled = true
                modelAction.text = "重新下载"
                modelDelete.visibility = View.GONE
            }
        }
    }

    private fun renderOcrModelState(state: OcrModelManager.State) {
        when (state) {
            OcrModelManager.State.Missing -> {
                ocrModelStatus.text = "未安装 · 当前扫描页使用 ML Kit；下载后启用 PP-OCRv6 检测 + 逐行识别"
                ocrModelProgress.visibility = View.GONE; ocrModelAction.isEnabled = true; ocrModelAction.text = "下载 OCR 模型"; ocrModelDelete.visibility = View.GONE
            }
            is OcrModelManager.State.Preparing -> {
                ocrModelStatus.text = state.message
                ocrModelProgress.visibility = View.VISIBLE; ocrModelProgress.isIndeterminate = true; ocrModelAction.isEnabled = true; ocrModelAction.text = "暂停下载"; ocrModelDelete.visibility = View.GONE
            }
            is OcrModelManager.State.Downloading -> {
                ocrModelStatus.text = String.format(Locale.getDefault(), "%s · %d%% · %.1f / %.1f MB", state.fileName, state.percent, state.downloaded / 1024.0 / 1024.0, state.total / 1024.0 / 1024.0)
                ocrModelProgress.visibility = View.VISIBLE; ocrModelProgress.isIndeterminate = false; ocrModelProgress.progress = state.percent; ocrModelAction.isEnabled = true; ocrModelAction.text = "暂停下载"; ocrModelDelete.visibility = View.GONE
            }
            is OcrModelManager.State.Ready -> {
                ocrModelStatus.text = String.format(Locale.getDefault(), "已安装 · %.1f MB · SHA-256 已校验 · ONNX Runtime 本地运行", state.totalBytes / 1024.0 / 1024.0)
                ocrModelProgress.visibility = View.GONE; ocrModelAction.isEnabled = false; ocrModelAction.text = "模型已安装"; ocrModelDelete.visibility = View.VISIBLE
            }
            is OcrModelManager.State.Error -> {
                ocrModelStatus.text = "下载未完成 · ${state.message}"
                ocrModelProgress.visibility = View.GONE; ocrModelAction.isEnabled = true; ocrModelAction.text = "继续下载"; ocrModelDelete.visibility = View.GONE
            }
        }
    }

}