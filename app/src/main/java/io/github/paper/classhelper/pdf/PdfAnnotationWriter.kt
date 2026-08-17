package io.github.paper.classhelper.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import io.github.paper.classhelper.data.CourseDb
import io.github.paper.classhelper.data.JournalRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import kotlin.math.hypot

/** Crash-safe PDF annotation writer with stable IDs and undo/redo support. */
class PdfAnnotationWriter(
    private val db: CourseDb,
    private val workspaceManager: PdfWorkspaceManager,
    private val scope: CoroutineScope
) {
    data class Command(val annotationId: String, val page: Int, val kind: String, val payload: String)
    data class EraseResult(val deleted: Boolean, val command: Command?)
    data class EraseBatchResult(val deletedCount: Int, val undoableCommands: List<Command>)

    private data class EraseTarget(
        val annotationId: String,
        val page: Int,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
        val persistedInPdf: Boolean,
        val points: List<Pair<Float, Float>> = emptyList(),
        val strokeWidth: Float = 0f,
    )


    private val mutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private val eraseIndex = mutableMapOf<String, MutableList<EraseTarget>>()
    // Pen-up must never wait for SQLite/fsync. Commands enter this tiny in-memory write-behind
    // queue immediately, then the existing crash-safe journal is persisted on Dispatchers.IO.
    private val pendingCommands = mutableMapOf<String, LinkedHashMap<String, Command>>()
    // Eraser hit-testing is intentionally off the UI thread, but an explicit “完成批注” must not
    // outrun a just-finished eraser gesture. Track those mutations and join them before flush.
    private val mutationJobs = mutableMapOf<String, MutableSet<Job>>()

    private fun eraseKey(workspaceId: String, page: Int) = "$workspaceId:$page"

    fun queueInk(workspace: PdfWorkspaceManager.Workspace, page: Int, stroke: InkOverlayView.Stroke): Command {
        val id = "ClassHelper:${UUID.randomUUID()}"
        val kind = if (stroke.tool == InkOverlayView.Tool.HIGHLIGHTER) "highlight" else "ink"
        val payload = JSONObject().apply {
            put("width", stroke.widthPt.toDouble()); put("color", stroke.color); put("opacity", stroke.opacity.toDouble())
            put("points", JSONArray().apply { stroke.points.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } })
        }.toString()
        val cmd = Command(id, page, kind, payload)
        queueCommand(workspace, cmd); return cmd
    }

    fun queueTextNote(workspace: PdfWorkspaceManager.Workspace, page: Int, x: Float, y: Float, text: String): Command {
        val id = "ClassHelper:${UUID.randomUUID()}"
        val payload = JSONObject().apply { put("x", x.toDouble()); put("y", y.toDouble()); put("text", text) }.toString()
        val cmd = Command(id, page, "text", payload)
        queueCommand(workspace, cmd)
        return cmd
    }

    /** Preload annotation bounds once so an eraser drag never re-opens the whole PDF per move event. */
    fun warmEraserIndex(workspace: PdfWorkspaceManager.Workspace, page: Int) {
        val key = eraseKey(workspace.id, page)
        synchronized(eraseIndex) { if (eraseIndex.containsKey(key)) return }
        scope.launch(Dispatchers.IO) {
            mutex.withLock { ensureEraseIndexLocked(workspace, page) }
        }
    }

    /**
     * Object eraser: resolve a whole drag path in one background pass. The UI can draw the
     * eraser footprint at full frame-rate while this method performs one index lookup and one
     * SQLite transaction after finger-up.
     */
    fun queueErasePath(
        workspace: PdfWorkspaceManager.Workspace,
        page: Int,
        path: List<InkOverlayView.PdfPoint>,
        radius: Float,
        onDeleted: (EraseBatchResult) -> Unit = {},
    ) {
        if (path.isEmpty()) { onDeleted(EraseBatchResult(0, emptyList())); return }
        val job = scope.launch(Dispatchers.IO) {
            val result = mutex.withLock {
                val targets = ensureEraseIndexLocked(workspace, page)
                var hits = targets.filter { hitTarget(it, path, radius) }
                if (hits.isEmpty()) {
                    // Pdfium and PDFBox may disagree by a few points around CropBox/rotation on
                    // non-standard files. A small second-pass tolerance keeps the object eraser
                    // usable without turning it into a broad rectangular delete tool.
                    val forgivingRadius = (radius * 1.65f).coerceAtMost(radius + 24f)
                    hits = targets.filter { hitTarget(it, path, forgivingRadius) }
                }
                if (hits.isEmpty()) return@withLock EraseBatchResult(0, emptyList())
                val commands = hits.mapNotNull { target ->
                    takePendingCommand(workspace.id, target.annotationId)
                        ?: db.getAnnotationHistory(target.annotationId)?.let { Command(it.annotationId, it.page, it.kind, it.payload) }
                }
                db.applyAnnotationEraseBatch(
                    workspace.id,
                    page,
                    hits.filter { it.persistedInPdf }.map { it.annotationId },
                    hits.filterNot { it.persistedInPdf }.map { it.annotationId },
                )
                targets.removeAll(hits.toSet())
                workspaceManager.markDirty(workspace.id)
                EraseBatchResult(hits.size, commands)
            }
            if (result.deletedCount > 0) schedule(workspace, 900)
            onDeleted(result)
        }
        trackMutationJob(workspace.id, job)
    }

    /** Queue deletion of every ClassHelper annotation on one page as one background operation. */
    fun queueClearPage(
        workspace: PdfWorkspaceManager.Workspace,
        page: Int,
        onQueued: (Int) -> Unit = {},
    ) {
        val job = scope.launch(Dispatchers.IO) {
            val count = mutex.withLock {
                val targets = ensureEraseIndexLocked(workspace, page).toList()
                if (targets.isEmpty()) return@withLock 0
                targets.forEach { takePendingCommand(workspace.id, it.annotationId) }
                db.applyAnnotationEraseBatch(
                    workspace.id,
                    page,
                    targets.filter { it.persistedInPdf }.map { it.annotationId },
                    targets.filterNot { it.persistedInPdf }.map { it.annotationId },
                )
                synchronized(eraseIndex) { eraseIndex[eraseKey(workspace.id, page)] = mutableListOf() }
                workspaceManager.markDirty(workspace.id)
                targets.size
            }
            if (count > 0) schedule(workspace, 250)
            onQueued(count)
        }
        trackMutationJob(workspace.id, job)
    }

    fun undo(workspace: PdfWorkspaceManager.Workspace, command: Command) {
        // Keep the UI instant. The committed preview is removed by ReaderActivity immediately;
        // journal/history mutation is serialized off the touch thread.
        val job = scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val memoryOnly = takePendingCommand(workspace.id, command.annotationId) != null
                if (!memoryOnly && db.deleteJournalByAnnotationId(command.annotationId) == 0) {
                    val payload = JSONObject().put("target", command.annotationId).toString()
                    db.addJournal(workspace.id, command.page, "delete_id", payload, "undo:${UUID.randomUUID()}")
                }
                if (command.kind == "text") db.deletePdfNote(command.annotationId)
                invalidateEraserIndex(workspace.id)
                workspaceManager.markDirty(workspace.id)
            }
            schedule(workspace, 300)
        }
        trackMutationJob(workspace.id, job)
    }

    fun redo(workspace: PdfWorkspaceManager.Workspace, command: Command) {
        val job = scope.launch(Dispatchers.IO) {
            mutex.withLock {
                // Delete a pending undo targeting this ID, otherwise restore the command through
                // the in-memory queue after this critical section.
                val removedUndo = db.pendingJournal(workspace.id).filter { row ->
                    row.kind == "delete_id" && runCatching { JSONObject(row.payload).optString("target") == command.annotationId }.getOrDefault(false)
                }.map { it.id }
                db.deleteJournal(removedUndo)
                if (removedUndo.isEmpty()) {
                    persistCommandLocked(workspace, command)
                } else {
                    workspaceManager.markDirty(workspace.id)
                }
                if (removedUndo.isNotEmpty() && command.kind == "text") {
                    val j = JSONObject(command.payload)
                    db.upsertPdfNote(command.annotationId, workspace.id, command.page, j.optString("text"))
                }
                invalidateEraserIndex(workspace.id)
            }
            schedule(workspace, 300)
        }
        trackMutationJob(workspace.id, job)
    }

    private fun queueCommand(workspace: PdfWorkspaceManager.Workspace, command: Command) {
        synchronized(pendingCommands) {
            pendingCommands.getOrPut(workspace.id) { linkedMapOf() }[command.annotationId] = command
        }
        registerPendingTarget(workspace.id, command)
        // Persist immediately, but never on the UI/touch thread.
        scope.launch(Dispatchers.IO) {
            mutex.withLock { persistOnePendingLocked(workspace, command.annotationId) }
        }
        schedule(workspace)
    }

    private fun registerPendingTarget(workspaceId: String, command: Command) {
        if (command.kind !in setOf("ink", "highlight", "text")) return
        boundsFromPayload(command.kind, command.payload)?.let { b ->
            synchronized(eraseIndex) {
                val geometry = geometryFromPayload(command.kind, command.payload)
                eraseIndex[eraseKey(workspaceId, command.page)]?.apply {
                    removeAll { it.annotationId == command.annotationId }
                    add(EraseTarget(command.annotationId, command.page, b[0], b[1], b[2], b[3], false, geometry.first, geometry.second))
                }
            }
        }
    }

    private fun takePendingCommand(workspaceId: String, annotationId: String): Command? = synchronized(pendingCommands) {
        val map = pendingCommands[workspaceId] ?: return@synchronized null
        val command = map.remove(annotationId)
        if (map.isEmpty()) pendingCommands.remove(workspaceId)
        command
    }

    private fun pendingCommandSnapshot(workspaceId: String, page: Int? = null): List<Command> = synchronized(pendingCommands) {
        pendingCommands[workspaceId]?.values?.filter { page == null || it.page == page }?.toList().orEmpty()
    }

    private fun persistOnePendingLocked(workspace: PdfWorkspaceManager.Workspace, annotationId: String) {
        val command = takePendingCommand(workspace.id, annotationId) ?: return
        persistCommandLocked(workspace, command)
    }

    private fun persistAllPendingLocked(workspace: PdfWorkspaceManager.Workspace) {
        val commands = synchronized(pendingCommands) {
            val values = pendingCommands.remove(workspace.id)?.values?.toList().orEmpty()
            values
        }
        commands.forEach { persistCommandLocked(workspace, it) }
    }

    private fun persistCommandLocked(workspace: PdfWorkspaceManager.Workspace, command: Command) {
        db.addAnnotationCommand(command.annotationId, workspace.id, command.page, command.kind, command.payload)
        if (command.kind == "text") {
            val j = JSONObject(command.payload)
            db.upsertPdfNote(command.annotationId, workspace.id, command.page, j.optString("text"))
        }
        workspaceManager.markDirty(workspace.id)
    }

    private fun trackMutationJob(workspaceId: String, job: Job) {
        synchronized(mutationJobs) {
            mutationJobs.getOrPut(workspaceId) { linkedSetOf() }.add(job)
        }
        job.invokeOnCompletion {
            synchronized(mutationJobs) {
                mutationJobs[workspaceId]?.let { set ->
                    set.remove(job)
                    if (set.isEmpty()) mutationJobs.remove(workspaceId)
                }
            }
        }
    }

    private suspend fun awaitMutationJobs(workspaceId: String) {
        while (true) {
            val snapshot = synchronized(mutationJobs) {
                mutationJobs[workspaceId]?.filter { !it.isCompleted }?.toList().orEmpty()
            }
            if (snapshot.isEmpty()) return
            snapshot.forEach { it.join() }
        }
    }

    fun schedule(workspace: PdfWorkspaceManager.Workspace, delayMs: Long = 1_800L) {
        val next = scope.launch(Dispatchers.IO) { delay(delayMs); flushNow(workspace, syncSource = false) }
        synchronized(jobs) {
            jobs.remove(workspace.id)?.cancel()
            jobs[workspace.id] = next
        }
    }

    /**
     * Always saves the private working PDF first. syncSource=true is used on explicit Save/onStop,
     * avoiding a SAF rewrite after every pen stroke.
     */
    suspend fun flushNow(workspace: PdfWorkspaceManager.Workspace, syncSource: Boolean = false): Result<Unit> {
        // Commit must include the eraser gesture that just ended, even if its geometry resolution
        // coroutine had not reached SQLite yet when the user tapped “完成批注”.
        awaitMutationJobs(workspace.id)
        val current = currentCoroutineContext()[Job]
        val stale = synchronized(jobs) { jobs.remove(workspace.id) }
        if (stale != null && stale !== current) stale.cancel()
        return mutex.withLock {
            runCatching {
                persistAllPendingLocked(workspace)
                val pending = db.pendingJournal(workspace.id)
                if (pending.isNotEmpty()) {
                    saveJournalTransaction(workspace, pending)
                    // Journal acknowledgement is deliberately last. If parsing, saving, fsync,
                    // or replacement fails, the original working PDF and all commands remain so
                    // the next flush can safely retry instead of reporting a false success.
                    db.deleteJournal(pending.map { it.id })
                    invalidateEraserIndex(workspace.id)
                }
                if (syncSource && db.getDocument(workspace.id)?.dirty == true) {
                    workspaceManager.syncToSource(workspace)
                }
            }
        }
    }

    /**
     * Apply add/delete commands to a fresh mutable /Annots list and commit them with one ordinary
     * PDFBox save. Add and erase deliberately share this exact path. The previous implementation
     * mixed selected-object incremental saves, full saves and a second reopen verification pass;
     * that split could make new ink disappear or make an erased ink annotation return after
     * “完成批注”.
     */
    private fun saveJournalTransaction(
        workspace: PdfWorkspaceManager.Workspace,
        pending: List<JournalRow>,
    ) {
        val source = workspace.workingFile
        check(source.isFile && source.length() > 0L) { "PDF 工作副本不存在" }
        val tmp = File(source.parentFile, source.nameWithoutExtension + ".saving.pdf")
        if (tmp.exists() && !tmp.delete()) error("无法清理旧的 PDF 临时文件")

        try {
            PDDocument.load(source).use { doc ->
                // Always work on detached mutable lists. PDFBox's getAnnotations() may be backed
                // by a COS array whose iterator/remove behaviour varies with malformed or indirect
                // /Annots entries. Explicitly assigning the final list back to the page makes both
                // additions and removals deterministic.
                val pageAnnotations = linkedMapOf<Int, MutableList<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>>()
                fun annotationsFor(pageIndex: Int): MutableList<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation> {
                    return pageAnnotations.getOrPut(pageIndex) {
                        ArrayList(doc.getPage(pageIndex).annotations)
                    }
                }

                pending.forEach { row ->
                    if (row.page !in 0 until doc.numberOfPages) return@forEach
                    val annotations = annotationsFor(row.page)
                    val stableId = row.annotationId.ifBlank { "ClassHelper:${UUID.randomUUID()}" }
                    when (row.kind) {
                        "ink", "highlight" -> {
                            // Replaying a retained journal after a crash must be idempotent.
                            annotations.removeAll { it.annotationName == stableId }
                            addInk(doc, annotations, stableId, row.payload, row.kind == "highlight")
                        }
                        "text" -> {
                            annotations.removeAll { it.annotationName == stableId }
                            addText(annotations, stableId, row.payload)
                        }
                        "delete_id" -> {
                            val target = JSONObject(row.payload).optString("target")
                            if (target.startsWith("ClassHelper:")) {
                                annotations.removeAll { it.annotationName == target }
                            }
                        }
                        "delete_at" -> deleteAt(annotations, row.payload)
                    }
                }

                pageAnnotations.forEach { (pageIndex, annotations) ->
                    doc.getPage(pageIndex).annotations = ArrayList(annotations)
                }

                // Standard full save is intentionally used for every journal transaction. It is
                // slower than a hand-selected incremental update but far more predictable for new
                // appearance streams and /Annots removals, and it runs off the UI thread only when
                // the write-behind journal is flushed.
                doc.save(tmp)
            }

            checkBasicPdfFile(tmp)
            replaceWorkingPdf(tmp, source)
        } catch (t: Throwable) {
            runCatching { if (tmp.exists()) tmp.delete() }
            throw t
        }
    }

    private fun checkBasicPdfFile(file: File) {
        check(file.isFile && file.length() >= 8L) { "PDF 保存失败：临时文件为空" }
        val header = ByteArray(5)
        file.inputStream().use { input ->
            check(input.read(header) == header.size) { "PDF 保存失败：文件头不完整" }
        }
        check(String(header, StandardCharsets.US_ASCII) == "%PDF-") { "PDF 保存失败：文件头无效" }
    }

    private fun replaceWorkingPdf(tmp: File, source: File) {
        // Android 8+ provides java.nio.file. Prefer an atomic same-directory replacement so the
        // app is never left with no working PDF between delete() and renameTo(). Some filesystems
        // do not support ATOMIC_MOVE; in that case REPLACE_EXISTING is still safer than deleting
        // the source first.
        runCatching {
            java.nio.file.Files.move(
                tmp.toPath(),
                source.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            java.nio.file.Files.move(
                tmp.toPath(),
                source.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse { moveError ->
            // Last-resort same-directory copy while preserving the original until the copy has
            // fully completed. Keep the journal if this fails so nothing is acknowledged early.
            val backup = File(source.parentFile, source.name + ".replace-backup")
            runCatching { if (backup.exists()) backup.delete() }
            if (source.exists() && !source.renameTo(backup)) throw moveError
            try {
                check(tmp.renameTo(source)) { "无法替换 PDF 工作文件" }
                if (backup.exists()) backup.delete()
            } catch (t: Throwable) {
                if (!source.exists() && backup.exists()) backup.renameTo(source)
                throw t
            }
        }
        check(source.isFile && source.length() > 0L) { "PDF 工作副本替换失败" }
    }

    private fun invalidateEraserIndex(workspaceId: String) {
        synchronized(eraseIndex) { eraseIndex.keys.removeAll { it.startsWith("$workspaceId:") } }
    }

    private fun ensureEraseIndexLocked(
        workspace: PdfWorkspaceManager.Workspace,
        page: Int,
    ): MutableList<EraseTarget> {
        val key = eraseKey(workspace.id, page)
        synchronized(eraseIndex) { eraseIndex[key]?.let { return it } }

        val pending = db.pendingJournal(workspace.id).filter { it.page == page }
        val pendingDeletes = pending.asSequence()
            .filter { it.kind == "delete_id" }
            .mapNotNull { runCatching { JSONObject(it.payload).optString("target").takeIf(String::isNotBlank) }.getOrNull() }
            .toHashSet()
        val targets = mutableListOf<EraseTarget>()

        runCatching {
            PDDocument.load(workspace.workingFile).use { doc ->
                if (page !in 0 until doc.numberOfPages) return@use
                doc.getPage(page).annotations.forEach { ann ->
                    val id = ann.annotationName.orEmpty()
                    val rect = ann.rectangle
                    if (id.startsWith("ClassHelper:") && id !in pendingDeletes && rect != null) {
                        val markup = ann as? PDAnnotationMarkup
                        val inkPoints = markup?.inkList?.flatMap { coords ->
                            buildList { var i = 0; while (i + 1 < coords.size) { add(coords[i] to coords[i + 1]); i += 2 } }
                        }.orEmpty()
                        val width = markup?.borderStyle?.width ?: 0f
                        targets += EraseTarget(
                            id, page, rect.lowerLeftX, rect.lowerLeftY, rect.upperRightX, rect.upperRightY, true, inkPoints, width
                        )
                    }
                }
            }
        }

        pending.forEach { row ->
            if (row.kind !in setOf("ink", "highlight", "text") || row.annotationId in pendingDeletes) return@forEach
            boundsFromPayload(row.kind, row.payload)?.let { b ->
                val geometry = geometryFromPayload(row.kind, row.payload)
                targets += EraseTarget(row.annotationId, page, b[0], b[1], b[2], b[3], false, geometry.first, geometry.second)
            }
        }
        // A fresh stroke may still be waiting for the IO journal write. Include it so switching
        // straight from pen to eraser can never make the stroke "come back" after a reload.
        pendingCommandSnapshot(workspace.id, page).forEach { command ->
            if (command.kind !in setOf("ink", "highlight", "text") || targets.any { it.annotationId == command.annotationId }) return@forEach
            boundsFromPayload(command.kind, command.payload)?.let { b ->
                val geometry = geometryFromPayload(command.kind, command.payload)
                targets += EraseTarget(command.annotationId, page, b[0], b[1], b[2], b[3], false, geometry.first, geometry.second)
            }
        }
        synchronized(eraseIndex) { eraseIndex[key] = targets }
        return targets
    }

    private fun hitTarget(target: EraseTarget, eraser: List<InkOverlayView.PdfPoint>, radius: Float): Boolean {
        if (eraser.isEmpty()) return false
        val expanded = radius + target.strokeWidth * 0.5f
        if (eraser.none { it.x >= target.minX - expanded && it.x <= target.maxX + expanded && it.y >= target.minY - expanded && it.y <= target.maxY + expanded }) {
            if (eraser.zipWithNext().none { (a, b) -> segmentIntersectsRect(a.x, a.y, b.x, b.y, target.minX - expanded, target.minY - expanded, target.maxX + expanded, target.maxY + expanded) }) return false
        }
        if (target.points.size < 2) return true
        if (eraser.size == 1) {
            val e = eraser.first()
            return target.points.zipWithNext().any { (a, b) -> pointSegmentDistance(e.x, e.y, a.first, a.second, b.first, b.second) <= expanded }
        }
        return eraser.zipWithNext().any { (ea, eb) ->
            target.points.zipWithNext().any { (sa, sb) ->
                segmentDistance(ea.x, ea.y, eb.x, eb.y, sa.first, sa.second, sb.first, sb.second) <= expanded
            }
        }
    }

    private fun geometryFromPayload(kind: String, payload: String): Pair<List<Pair<Float, Float>>, Float> = runCatching {
        if (kind == "text") return@runCatching emptyList<Pair<Float, Float>>() to 0f
        val json = JSONObject(payload)
        val pts = json.getJSONArray("points")
        val points = buildList {
            for (i in 0 until pts.length()) {
                val p = pts.getJSONArray(i)
                add(p.getDouble(0).toFloat() to p.getDouble(1).toFloat())
            }
        }
        points to json.optDouble("width", if (kind == "highlight") 11.0 else 1.5).toFloat()
    }.getOrDefault(emptyList<Pair<Float, Float>>() to 0f)

    private fun pointSegmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 0.0001f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun segmentDistance(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float {
        if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        return minOf(
            pointSegmentDistance(ax, ay, cx, cy, dx, dy),
            pointSegmentDistance(bx, by, cx, cy, dx, dy),
            pointSegmentDistance(cx, cy, ax, ay, bx, by),
            pointSegmentDistance(dx, dy, ax, ay, bx, by),
        )
    }

    private fun segmentsIntersect(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
        fun cross(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val c1 = cross(ax, ay, bx, by, cx, cy); val c2 = cross(ax, ay, bx, by, dx, dy)
        val c3 = cross(cx, cy, dx, dy, ax, ay); val c4 = cross(cx, cy, dx, dy, bx, by)
        return ((c1 <= 0f && c2 >= 0f) || (c1 >= 0f && c2 <= 0f)) && ((c3 <= 0f && c4 >= 0f) || (c3 >= 0f && c4 <= 0f))
    }

    private fun segmentIntersectsRect(ax: Float, ay: Float, bx: Float, by: Float, left: Float, bottom: Float, right: Float, top: Float): Boolean {
        if ((ax in left..right && ay in bottom..top) || (bx in left..right && by in bottom..top)) return true
        return segmentsIntersect(ax, ay, bx, by, left, bottom, right, bottom) ||
            segmentsIntersect(ax, ay, bx, by, right, bottom, right, top) ||
            segmentsIntersect(ax, ay, bx, by, right, top, left, top) ||
            segmentsIntersect(ax, ay, bx, by, left, top, left, bottom)
    }

    /** Returns minX, minY, maxX, maxY in PDF user space. */
    private fun boundsFromPayload(kind: String, payload: String): FloatArray? = runCatching {
        val json = JSONObject(payload)
        if (kind == "text") {
            val x = json.getDouble("x").toFloat(); val y = json.getDouble("y").toFloat()
            return@runCatching floatArrayOf(x, y, x + 24f, y + 24f)
        }
        val points = json.getJSONArray("points")
        if (points.length() == 0) return@runCatching null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until points.length()) {
            val p = points.getJSONArray(i); val x = p.getDouble(0).toFloat(); val y = p.getDouble(1).toFloat()
            minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
        }
        val width = json.optDouble("width", if (kind == "highlight") 11.0 else 1.5).toFloat().coerceIn(0.5f, 30f)
        floatArrayOf(minX - width * 2, minY - width * 2, maxX + width * 2, maxY + width * 2)
    }.getOrNull()

    private fun addInk(doc: PDDocument, annotations: MutableList<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>, id: String, payload: String, highlight: Boolean) {
        val json = JSONObject(payload); val pointsJson = json.getJSONArray("points")
        if (pointsJson.length() < 2) return
        val coords = FloatArray(pointsJson.length() * 2)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until pointsJson.length()) {
            val p = pointsJson.getJSONArray(i); val x = p.getDouble(0).toFloat(); val y = p.getDouble(1).toFloat()
            coords[i * 2] = x; coords[i * 2 + 1] = y
            minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
        }
        val width = json.optDouble("width", if (highlight) 11.0 else 1.5).toFloat().coerceIn(0.5f, 30f)
        val colorInt = json.optInt("color", if (highlight) 0xffffdf44.toInt() else 0xff111111.toInt())
        val opacity = json.optDouble("opacity", if (highlight) 0.28 else 1.0).toFloat().coerceIn(0.05f, 1f)
        val rgb = floatArrayOf(android.graphics.Color.red(colorInt) / 255f, android.graphics.Color.green(colorInt) / 255f, android.graphics.Color.blue(colorInt) / 255f)
        val ink = PDAnnotationMarkup()
        ink.cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK)
        ink.inkList = arrayOf(coords)
        ink.annotationName = id
        ink.contents = if (highlight) "ClassHelper Highlight" else "ClassHelper Ink"
        ink.rectangle = PDRectangle(minX - width * 2, minY - width * 2, (maxX - minX + width * 4).coerceAtLeast(4f), (maxY - minY + width * 4).coerceAtLeast(4f))
        ink.color = PDColor(rgb, PDDeviceRGB.INSTANCE)
        ink.borderStyle = PDBorderStyleDictionary().apply { this.width = width }
        ink.constantOpacity = opacity
        attachInkAppearance(doc, ink, coords, width, rgb)
        annotations.add(ink)
    }

    private fun addText(annotations: MutableList<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>, id: String, payload: String) {
        val json = JSONObject(payload); val x = json.getDouble("x").toFloat(); val y = json.getDouble("y").toFloat()
        val note = PDAnnotationText()
        note.annotationName = id; note.contents = json.getString("text"); note.titlePopup = "ClassHelper"
        note.rectangle = PDRectangle(x, y, 24f, 24f)
        note.color = PDColor(floatArrayOf(1f, 0.85f, 0.15f), PDDeviceRGB.INSTANCE)
        annotations.add(note)
    }

    private fun deleteAt(annotations: MutableList<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>, payload: String) {
        val j = JSONObject(payload); val x = j.getDouble("x").toFloat(); val y = j.getDouble("y").toFloat(); val r = j.optDouble("radius", 12.0).toFloat()
        val target = annotations.lastOrNull { ann ->
            val name = ann.annotationName.orEmpty(); val rect = ann.rectangle
            name.startsWith("ClassHelper:") && rect != null && x >= rect.lowerLeftX - r && x <= rect.upperRightX + r && y >= rect.lowerLeftY - r && y <= rect.upperRightY + r
        }
        if (target != null) {
            target.annotationName?.let { db.deletePdfNote(it) }
            annotations.remove(target)
        }
    }

    private fun attachInkAppearance(doc: PDDocument, ink: PDAnnotationMarkup, coords: FloatArray, width: Float, rgb: FloatArray) {
        val rect = ink.rectangle ?: return
        if (coords.size < 4) return
        val appearance = PDAppearanceStream(doc)
        appearance.bBox = PDRectangle(0f, 0f, rect.width.coerceAtLeast(1f), rect.height.coerceAtLeast(1f))
        fun n(value: Float): String = String.format(Locale.US, "%.4f", value)
        val content = buildString(coords.size * 8 + 128) {
            append("q\n").append(n(rgb[0])).append(' ').append(n(rgb[1])).append(' ').append(n(rgb[2])).append(" RG\n")
            append(n(width)).append(" w\n1 J\n1 j\n")
            append(n(coords[0] - rect.lowerLeftX)).append(' ').append(n(coords[1] - rect.lowerLeftY)).append(" m\n")
            var i = 2
            while (i + 1 < coords.size) {
                append(n(coords[i] - rect.lowerLeftX)).append(' ').append(n(coords[i + 1] - rect.lowerLeftY)).append(" l\n"); i += 2
            }
            append("S\nQ\n")
        }
        appearance.stream.createOutputStream().use { it.write(content.toByteArray(StandardCharsets.US_ASCII)) }
        val appearanceDictionary = PDAppearanceDictionary()
        appearanceDictionary.setNormalAppearance(appearance)
        ink.appearance = appearanceDictionary
    }
}
