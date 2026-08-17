package io.github.paper.classhelper.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Lightweight Android implementation of RapidOCR's PP-OCRv6 det -> crop -> CTC-rec pipeline. */
class PpOcrV6Engine(modelDir: File) : Closeable {
    data class Result(val text: String, val lineCount: Int, val confidence: Float)
    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Float)
    private data class Rec(val text: String, val score: Float)

    private val env = OrtEnvironment.getEnvironment("ClassHelper-PP-OCRv6")
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        setInterOpNumThreads(1)
        setCPUArenaAllocator(false)
    }
    private val det = env.createSession(File(modelDir, "PP-OCRv6_det_small.onnx").absolutePath, options)
    private val rec = env.createSession(File(modelDir, "PP-OCRv6_rec_small.onnx").absolutePath, options)
    private val detInputName = det.inputNames.first()
    private val recInputName = rec.inputNames.first()
    private val characters: List<String> = loadCharacters()

    fun recognize(bitmap: Bitmap, highAccuracy: Boolean = false): Result {
        val boxes = detect(bitmap, if (highAccuracy) 2200 else 1600)
        if (boxes.isEmpty()) return Result("", 0, 0f)
        val sorted = boxes.sortedWith(compareBy<Box> { it.top }.thenBy { it.left })
        val lines = ArrayList<Pair<Box, Rec>>(sorted.size)
        for (box in sorted.take(MAX_LINES)) {
            val crop = crop(bitmap, box) ?: continue
            try {
                var one = recognizeLine(crop)
                if (one.score < 0.55f) {
                    val rotated180 = rotate(crop, 180f)
                    try { val alt = recognizeLine(rotated180); if (alt.score > one.score) one = alt } finally { rotated180.recycle() }
                }
                if (one.score < 0.48f && crop.height > crop.width * 1.25f) {
                    val rotated = rotate90(crop)
                    try {
                        val alt = recognizeLine(rotated)
                        if (alt.score > one.score) one = alt
                    } finally { rotated.recycle() }
                }
                if (one.text.isNotBlank() && one.score >= MIN_REC_SCORE) lines += box to one
            } finally { crop.recycle() }
        }
        if (lines.isEmpty()) return Result("", 0, 0f)
        val grouped = orderIntoRows(lines)
        val text = grouped.joinToString("\n") { row -> row.joinToString(" ") { it.second.text.trim() }.trim() }.trim()
        val confidence = lines.map { it.second.score }.average().toFloat()
        return Result(text, lines.size, confidence)
    }

    private fun detect(source: Bitmap, sideLimit: Int): List<Box> {
        val ratio = min(1f, sideLimit.toFloat() / max(source.width, source.height).coerceAtLeast(1))
        val w = max(32, ((source.width * ratio / 32f).roundToInt() * 32))
        val h = max(32, ((source.height * ratio / 32f).roundToInt() * 32))
        val resized = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
        try {
            val input = imageTensor(resized, h, w)
            input.use { tensor ->
                det.run(mapOf(detInputName to tensor)).use { result ->
                    val out = result.iterator().next().value as? OnnxTensor ?: return emptyList()
                    val shape = out.info.shape
                    if (shape.size < 2) return emptyList()
                    val mh = shape[shape.size - 2].toInt(); val mw = shape[shape.size - 1].toInt()
                    if (mh <= 0 || mw <= 0) return emptyList()
                    val prob = out.floatBuffer ?: return emptyList()
                    val map = FloatArray(mh * mw); prob.get(map, 0, min(map.size, prob.remaining()))
                    return postProcess(map, mw, mh, source.width, source.height)
                }
            }
        } finally { if (resized !== source) resized.recycle() }
    }

    private fun imageTensor(bitmap: Bitmap, h: Int, w: Int): OnnxTensor {
        val pixels = IntArray(w * h); bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = ByteBuffer.allocateDirect(4 * 3 * w * h).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (channel in 0..2) {
            for (p in pixels) {
                val v = when (channel) { 0 -> (p shr 16) and 0xff; 1 -> (p shr 8) and 0xff; else -> p and 0xff }
                buf.put((v / 255f - 0.5f) / 0.5f)
            }
        }
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, h.toLong(), w.toLong()))
    }

    /** DB-like threshold+dilation+connected-components post process, intentionally no OpenCV dependency. */
    private fun postProcess(prob: FloatArray, w: Int, h: Int, dstW: Int, dstH: Int): List<Box> {
        val mask = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var hit = false
            for (dy in 0..1) for (dx in 0..1) {
                val xx = x - dx; val yy = y - dy
                if (xx >= 0 && yy >= 0 && prob[yy * w + xx] > DET_THRESH) hit = true
            }
            mask[y * w + x] = hit
        }
        val seen = BooleanArray(mask.size); val queue = IntArray(mask.size); val boxes = ArrayList<Box>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0; var tail = 0
            if (tail >= queue.size) break
            queue[tail++] = start; seen[start] = true
            var minX = start % w; var maxX = minX; var minY = start / w; var maxY = minY; var scoreSum = 0f; var count = 0
            while (head < tail) {
                val idx = queue[head++]; val x = idx % w; val y = idx / w
                scoreSum += prob[idx]; count++; minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (mask[ni] && !seen[ni] && tail < queue.size) { seen[ni] = true; queue[tail++] = ni }
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1; val score = if (count > 0) scoreSum / count else 0f
            if (bw < 3 || bh < 3 || count < 8 || score < BOX_THRESH) continue
            val expandX = max(2, ((bw * (UNCLIP_RATIO - 1f)) / 2f).roundToInt())
            val expandY = max(2, ((bh * (UNCLIP_RATIO - 1f)) / 2f).roundToInt())
            val l = ((minX - expandX).coerceAtLeast(0) / w.toFloat() * dstW).roundToInt()
            val r = ((maxX + expandX + 1).coerceAtMost(w) / w.toFloat() * dstW).roundToInt()
            val t = ((minY - expandY).coerceAtLeast(0) / h.toFloat() * dstH).roundToInt()
            val b = ((maxY + expandY + 1).coerceAtMost(h) / h.toFloat() * dstH).roundToInt()
            if (r - l >= 6 && b - t >= 6) boxes += Box(l, t, r, b, score)
            if (boxes.size >= MAX_LINES * 2) break
        }
        return mergeNearby(boxes)
    }

    private fun mergeNearby(boxes: List<Box>): List<Box> {
        if (boxes.size < 2) return boxes
        val sorted = boxes.sortedWith(compareBy<Box> { it.top }.thenBy { it.left }).toMutableList()
        val out = mutableListOf<Box>()
        for (b in sorted) {
            val last = out.lastOrNull()
            if (last != null) {
                val overlapY = min(last.bottom, b.bottom) - max(last.top, b.top)
                val minH = min(last.bottom - last.top, b.bottom - b.top).coerceAtLeast(1)
                val gap = b.left - last.right
                if (overlapY > minH * 0.55f && gap in -4..max(12, minH)) {
                    out[out.lastIndex] = Box(min(last.left, b.left), min(last.top, b.top), max(last.right, b.right), max(last.bottom, b.bottom), max(last.score, b.score)); continue
                }
            }
            out += b
        }
        return out
    }

    private fun crop(bitmap: Bitmap, box: Box): Bitmap? {
        val pad = max(2, ((box.bottom - box.top) * 0.08f).roundToInt())
        val r = Rect((box.left - pad).coerceAtLeast(0), (box.top - pad).coerceAtLeast(0), (box.right + pad).coerceAtMost(bitmap.width), (box.bottom + pad).coerceAtMost(bitmap.height))
        if (r.width() < 2 || r.height() < 2) return null
        return Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
    }

    private fun recognizeLine(source: Bitmap): Rec {
        val ratio = source.width / source.height.toFloat().coerceAtLeast(1f)
        val inputInfo = rec.inputInfo.values.first().info as TensorInfo
        val fixedW = inputInfo.shape.getOrNull(3)?.takeIf { it > 0 }?.toInt()
        val targetW = fixedW ?: ceil(48f * max(320f / 48f, ratio)).toInt().coerceIn(32, 2048)
        val resizedW = min(targetW, ceil(48f * ratio).toInt().coerceAtLeast(1))
        val resized = Bitmap.createScaledBitmap(source, resizedW, 48, true)
        try {
            val pixels = IntArray(resizedW * 48); resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, 48)
            val buf = ByteBuffer.allocateDirect(4 * 3 * 48 * targetW).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (c in 0..2) for (y in 0 until 48) {
                val base = y * resizedW
                for (x in 0 until targetW) {
                    val v = if (x < resizedW) {
                        val p = pixels[base + x]; when(c) { 0 -> (p shr 16) and 0xff; 1 -> (p shr 8) and 0xff; else -> p and 0xff }
                    } else 128
                    buf.put((v / 255f - 0.5f) / 0.5f)
                }
            }
            buf.rewind()
            OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, 48, targetW.toLong())).use { input ->
                rec.run(mapOf(recInputName to input)).use { result ->
                    val out = result.iterator().next().value as? OnnxTensor ?: return Rec("", 0f)
                    val shape = out.info.shape; if (shape.size < 3) return Rec("", 0f)
                    val time = shape[shape.size - 2].toInt(); val classes = shape[shape.size - 1].toInt()
                    val fb = out.floatBuffer ?: return Rec("", 0f); val values = FloatArray(time * classes); fb.get(values, 0, min(values.size, fb.remaining()))
                    val sb = StringBuilder(); var last = -1; var scoreSum = 0f; var kept = 0
                    for (t in 0 until time) {
                        var best = 0; var bestV = Float.NEGATIVE_INFINITY; val offset = t * classes
                        for (c in 0 until classes) { val v = values[offset + c]; if (v > bestV) { bestV = v; best = c } }
                        if (best != 0 && best != last && best in characters.indices) { sb.append(characters[best]); scoreSum += bestV; kept++ }
                        last = best
                    }
                    return Rec(sb.toString().trim(), if (kept == 0) 0f else scoreSum / kept)
                }
            }
        } finally { resized.recycle() }
    }

    private fun loadCharacters(): List<String> {
        val raw = rec.metadata.customMetadata["character"] ?: error("PP-OCRv6 模型缺少 character metadata")
        val normalized = raw.replace("\\n", "\n")
        val list = normalized.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toMutableList()
        list.add(" "); list.add(0, "blank")
        return list
    }

    private fun orderIntoRows(lines: List<Pair<Box, Rec>>): List<List<Pair<Box, Rec>>> {
        val rows = mutableListOf<MutableList<Pair<Box, Rec>>>()
        for (line in lines.sortedBy { it.first.top }) {
            val h = (line.first.bottom - line.first.top).coerceAtLeast(1)
            val cy = (line.first.top + line.first.bottom) / 2
            val row = rows.lastOrNull()
            val fits = row?.let { existing ->
                val e = existing.first().first; val eh = (e.bottom - e.top).coerceAtLeast(1); val ecy = (e.top + e.bottom) / 2
                kotlin.math.abs(cy - ecy) <= max(h, eh) * 0.65f
            } ?: false
            if (fits) row!!.add(line) else rows += mutableListOf(line)
        }
        rows.forEach { it.sortBy { p -> p.first.left } }
        return rows
    }

    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun rotate90(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.height, src.width, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out); val matrix = android.graphics.Matrix().apply { postRotate(90f); postTranslate(src.height.toFloat(), 0f) }
        canvas.drawColor(android.graphics.Color.WHITE); canvas.drawBitmap(src, matrix, null); return out
    }

    override fun close() { runCatching { det.close() }; runCatching { rec.close() }; runCatching { options.close() } }

    companion object {
        private const val DET_THRESH = 0.30f
        private const val BOX_THRESH = 0.50f
        private const val UNCLIP_RATIO = 1.6f
        private const val MIN_REC_SCORE = 0.25f
        private const val MAX_LINES = 800
    }
}
