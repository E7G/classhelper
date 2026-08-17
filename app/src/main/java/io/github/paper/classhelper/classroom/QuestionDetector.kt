package io.github.paper.classhelper.classroom

import kotlin.math.max

/** Recall-first question detector with duplicate suppression, but no fixed cooldown. */
class QuestionDetector {
    private val history = mutableListOf<String>()
    private var lastAccepted = ""

    fun acceptPartial(partialText: String): String? {
        val text = normalize(partialText)
        if (text.length !in 6..180 || !hasQuestionCore(text)) return null
        val value = score(text)
        val directQuestion = directQuestionWords.any { word ->
            val index = text.indexOf(word)
            index in 0..6
        }
        if (value < 4 && !(directQuestion && value >= 3)) return null
        return acceptCandidate(text)
    }

    fun accept(finalText: String): String? {
        val text = normalize(finalText)
        if (text.length < 3) return null
        history.add(text); while (history.size > 4) history.removeAt(0)
        val combined = history.takeLast(3).joinToString("，")
        val candidate = when {
            hasQuestionCore(text) && score(text) >= 2 -> text
            hasQuestionCore(combined) && score(combined) >= 4 -> combined
            else -> return null
        }.trim('，', '。', ' ')
        val accepted = acceptCandidate(candidate)
        if (accepted != null) history.clear()
        return accepted
    }

    private fun acceptCandidate(candidate: String): String? {
        if (similar(candidate, lastAccepted) > 0.82) return null
        lastAccepted = candidate
        return candidate
    }

    private fun score(s: String): Int {
        var v = 0
        if (questionWords.any { it in s }) v += 2
        if (classroomWords.any { it in s }) v += 2
        if (questionEndings.any { s.endsWith(it) || "$it？" in s || "$it?" in s }) v += 1
        if ('？' in s || '?' in s) v += 2
        if (s.length in 6..120) v += 1
        if (s.contains("请") && imperativeQuestionWords.any { it in s }) v += 1
        return v
    }

    private fun hasQuestionCore(s: String): Boolean =
        questionWords.any { it in s } || questionEndings.any { s.endsWith(it) || "$it？" in s || "$it?" in s } || '？' in s || '?' in s

    private val directQuestionWords = listOf(
        "为什么", "怎么", "如何", "什么", "多少", "哪个", "哪一个", "谁", "是否", "能不能", "可不可以", "请问"
    )

    private val questionWords = listOf(
        "为什么", "怎么", "如何", "什么", "多少", "哪一个", "哪个", "是谁", "谁来", "是否", "能不能", "可不可以", "请问",
        "怎么算", "怎么求", "怎么做", "选什么", "选择什么", "哪个正确", "有什么区别", "区别是什么",
        "请回答", "请解释", "请说明", "请分析", "请比较", "说说", "谈谈", "举个例子"
    )
    private val classroomWords = listOf("大家想", "想一想", "谁回答", "谁知道", "谁来说", "有人知道", "这个问题", "回答一下", "告诉我", "请大家", "谁能")
    private val imperativeQuestionWords = listOf("回答", "解释", "说明", "分析", "比较", "判断", "计算", "选择")
    private val questionEndings = listOf("吗", "呢", "么", "没有", "对不对", "是不是", "是什么", "为什么", "怎么办")

    private fun normalize(s: String) = s.replace(Regex("\\s+"), "").replace("。？", "？").trim()

    private fun similar(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b || a.contains(b) || b.contains(a)) return minOf(a.length, b.length).toDouble() / max(a.length, b.length)
        val aa = a.windowed(2).toSet(); val bb = b.windowed(2).toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        return aa.intersect(bb).size.toDouble() / aa.union(bb).size
    }
}
