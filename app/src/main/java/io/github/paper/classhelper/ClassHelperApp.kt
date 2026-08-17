package io.github.paper.classhelper

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.google.android.material.color.DynamicColors
import io.github.paper.classhelper.data.CourseDb
import io.github.paper.classhelper.asr.AsrModelManager
import io.github.paper.classhelper.knowledge.KnowledgeRepository
import io.github.paper.classhelper.llm.LlmClient
import io.github.paper.classhelper.pdf.PdfWorkspaceManager
import io.github.paper.classhelper.ocr.OcrModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.paper.classhelper.util.CrashReporter

class ClassHelperApp : Application() {
    /** Process-wide low-priority scope for work that should survive a foreground service teardown. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        PDFBoxResourceLoader.init(this)
        val db = CourseDb(this)
        val settings = SettingsStore(this)
        val workspace = PdfWorkspaceManager(this, db)
        val asrModels = AsrModelManager(this)
        val ocrModels = OcrModelManager(this)
        graph = AppGraph(
            settings = settings,
            asrModels = asrModels,
            ocrModels = ocrModels,
            db = db,
            workspace = workspace,
            knowledge = KnowledgeRepository(db),
            llm = LlmClient(settings)
        )
    }
}

data class AppGraph(
    val settings: SettingsStore,
    val asrModels: AsrModelManager,
    val ocrModels: OcrModelManager,
    val db: CourseDb,
    val workspace: PdfWorkspaceManager,
    val knowledge: KnowledgeRepository,
    val llm: LlmClient
)
