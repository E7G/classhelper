package io.github.paper.classhelper.classroom

import kotlin.math.max

/**
 * Classroom-oriented question intent detector.
 *
 * A VAD pause only tells us that the teacher stopped speaking; it does not prove the previous
 * utterance was a question. This detector therefore separates strong questions from ambiguous ones,
 * boosts explicit classroom prompts, penalizes lecture/explanation phrasing, and suppresses near
 * duplicates. The service may answer STRONG candidates immediately while giving WEAK candidates a
 * short speech-aware confirmation window.
 */
class QuestionDetector {
    enum class Confidence { WEAK, STRONG }

    data class Candidate(
        val text: String,
        val confidence: Confidence,
        val score: Int,
    )

    private val history = mutableListOf<String>()
    private var lastAccepted = ""

    /** Cheap, non-mutating prefilter retained for callers that only need a yes/no hint. */
    fun mayBeQuestion(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.length >= 3 && hasQuestionCore(normalized) && score(normalized) >= WEAK_THRESHOLD
    }

    /**
     * Classifies one stable ASR final. History is only used to attach prompt-only lead-ins such as
     * "大家想一想" to the actual question in the next segment; ordinary lecture history is not
     * concatenated, which avoids accidental score inflation.
     */
    fun classify(finalText: String): Candidate? {
        val text = normalize(finalText)
        if (text.length < 3) return null

        val previous = history.lastOrNull()
        history.add(text)
        while (history.size > 4) history.removeAt(0)

        val candidate = if (
            previous != null &&
            isPromptOnly(previous) &&
            hasQuestionCore(text)
        ) {
            "$previous，$text"
        } else {
            text
        }.trim('，', '。', ' ')

        if (!hasQuestionCore(candidate)) return null
        val value = score(candidate)
        if (value < WEAK_THRESHOLD) return null
        if (similar(candidate, lastAccepted) > DUPLICATE_THRESHOLD) return null

        val confidence = if (isStrong(candidate, value)) Confidence.STRONG else Confidence.WEAK
        return Candidate(candidate, confidence, value)
    }

    /** Marks a previously classified candidate as accepted, applying duplicate suppression. */
    fun commit(candidate: Candidate): String? = acceptCandidate(candidate.text)

    /** Compatibility helper: directly accepts any candidate that passes classification. */
    fun accept(finalText: String): String? = classify(finalText)?.let(::commit)

    /** Partial text is never used by the current SenseVoice path; only very strong partials qualify. */
    fun acceptPartial(partialText: String): String? {
        val text = normalize(partialText)
        if (text.length !in 6..180 || !hasQuestionCore(text)) return null
        val value = score(text)
        if (!isStrong(text, value)) return null
        return acceptCandidate(text)
    }

    private fun acceptCandidate(candidate: String): String? {
        if (similar(candidate, lastAccepted) > DUPLICATE_THRESHOLD) return null
        lastAccepted = candidate
        history.clear()
        return candidate
    }

    private fun score(s: String): Int {
        var value = 0
        val strongPrompt = strongClassroomPrompts.any { it in s }
        val directStart = startsWithDirectQuestion(s)
        val nearStart = directQuestionWords.any { word -> s.indexOf(word) in 0..6 }
        val hasQuestionWord = questionWords.any { it in s }
        val hasQuestionEnding = questionEndings.any { ending ->
            s.endsWith(ending) || "$ending？" in s || "$ending?" in s
        }
        val hasQuestionMark = '？' in s || '?' in s

        if (strongPrompt) value += 4
        value += when {
            directStart -> 4
            nearStart -> 2
            hasQuestionWord -> 1
            else -> 0
        }
        if (hasQuestionEnding) value += 2
        if (hasQuestionMark) value += 2
        if (s.length in 4..120) value += 1
        if (s.contains("请") && imperativeQuestionWords.any { it in s }) value += 2

        // These are common lecture/explanation constructions containing interrogative words but not
        // actually asking students a question. They are the main source of false positives in class.
        if (isExplanatory(s)) value -= 5
        return value
    }

    private fun isStrong(s: String, value: Int): Boolean {
        val strongPrompt = strongClassroomPrompts.any { it in s }
        if (isExplanatory(s) && !strongPrompt) return false

        val hasQuestionMark = '？' in s || '?' in s
        val directStart = startsWithDirectQuestion(s)
        return value >= STRONG_THRESHOLD ||
            (strongPrompt && value >= 5) ||
            (hasQuestionMark && value >= 5) ||
            (directStart && value >= 5)
    }

    private fun startsWithDirectQuestion(s: String): Boolean {
        var head = s
        for (prefix in discoursePrefixes) {
            if (head.startsWith(prefix) && head.length > prefix.length) {
                head = head.removePrefix(prefix)
                break
            }
        }
        return directQuestionWords.any { head.startsWith(it) }
    }

    private fun hasQuestionCore(s: String): Boolean =
        questionWords.any { it in s } ||
            questionEndings.any { s.endsWith(it) || "$it？" in s || "$it?" in s } ||
            '？' in s || '?' in s

    private fun isPromptOnly(s: String): Boolean = promptOnlyPhrases.any { it in s }

    private fun isExplanatory(s: String): Boolean {
        if (explanatoryPatterns.any { it in s }) return true
        val startsLikeLecture = explanatoryPrefixes.any { s.startsWith(it) }
        return startsLikeLecture && questionWords.any { it in s }
    }

    private val discoursePrefixes = listOf("那么", "那", "所以", "请问")

    private val directQuestionWords = listOf(
        "为什么", "怎么", "如何", "什么", "多少", "哪个", "哪一个", "哪种", "哪里", "哪儿", "谁",
        "是否", "是不是", "能不能", "能否", "可不可以", "什么时候", "何时", "怎么样", "请问"
    )

    private val questionWords = listOf(
        "为什么", "怎么", "如何", "什么", "多少", "哪一个", "哪个", "哪种", "哪里", "哪儿", "是谁", "谁来",
        "是否", "是不是", "能不能", "能否", "可不可以", "什么时候", "何时", "怎么样", "请问",
        "怎么算", "怎么求", "怎么做", "选什么", "选择什么", "哪个正确", "有什么区别", "区别是什么",
        "请回答", "请解释", "请说明", "请分析", "请比较", "说说", "谈谈", "举个例子"
    )

    private val strongClassroomPrompts = listOf(
        "大家想一想", "大家想想", "你们想一想", "你们觉得", "同学们觉得", "谁回答", "谁知道", "谁来说",
        "有人知道", "谁能回答", "谁能说说", "这个问题", "回答一下", "请回答", "请解释", "请说明",
        "请分析", "请比较", "请判断", "请计算", "请选择", "告诉我", "请大家", "思考一个问题"
    )

    private val promptOnlyPhrases = listOf(
        "大家想一想", "大家想想", "你们想一想", "请大家想一想", "思考一个问题", "这个问题大家想一下"
    )

    private val explanatoryPrefixes = listOf(
        "我们来看", "我们看看", "我们先看", "我们首先看", "下面来看", "下面讲", "下面介绍", "接下来",
        "现在来看", "现在看", "这里讲", "这里看", "先来看", "再来看", "刚才说到", "这一节讲", "本节讲"
    )

    private val explanatoryPatterns = listOf(
        "这就是为什么", "这也就是为什么", "也就是说为什么", "我们来看为什么", "我们看看为什么",
        "下面讲一下怎么", "下面讲讲怎么", "接下来看看怎么", "接下来讲如何", "我们来看什么叫",
        "我们看看什么叫", "下面介绍什么是", "下面看看什么是", "刚才说到为什么", "这里解释为什么",
        "这里说明为什么", "之所以"
    )

    private val imperativeQuestionWords = listOf("回答", "解释", "说明", "分析", "比较", "判断", "计算", "选择")
    private val questionEndings = listOf("吗", "呢", "么", "没有", "对不对", "是不是", "是什么", "为什么", "怎么办", "怎么样")

    private fun normalize(s: String) = s.replace(Regex("\\s+"), "").replace("。？", "？").trim()

    private fun similar(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b || a.contains(b) || b.contains(a)) {
            return minOf(a.length, b.length).toDouble() / max(a.length, b.length)
        }
        val aa = a.windowed(2).toSet()
        val bb = b.windowed(2).toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        return aa.intersect(bb).size.toDouble() / aa.union(bb).size
    }

    companion object {
        private const val WEAK_THRESHOLD = 3
        private const val STRONG_THRESHOLD = 7
        private const val DUPLICATE_THRESHOLD = 0.82
    }
}
