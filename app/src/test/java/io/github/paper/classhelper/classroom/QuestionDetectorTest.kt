package io.github.paper.classhelper.classroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuestionDetectorTest {
    @Test fun ignoresNormalLectureStatement() {
        assertNull(QuestionDetector().classify("今天我们继续学习投资理论。"))
    }

    @Test fun rejectsCommonExplanatoryWhySentence() {
        assertNull(QuestionDetector().classify("我们来看为什么利率上升会导致企业投资下降。"))
        assertNull(QuestionDetector().classify("这就是为什么需求曲线通常向下倾斜。"))
    }

    @Test fun rejectsCommonExplanatoryHowSentence() {
        assertNull(QuestionDetector().classify("下面讲一下怎么计算机会成本。"))
        assertNull(QuestionDetector().classify("接下来我们看什么叫边际效用。"))
    }

    @Test fun strongDirectQuestionIsImmediate() {
        val candidate = QuestionDetector().classify("为什么利率上升会导致企业投资下降？")
        assertNotNull(candidate)
        assertEquals(QuestionDetector.Confidence.STRONG, candidate!!.confidence)
    }

    @Test fun directQuestionWithoutPunctuationCanStillBeStrong() {
        val candidate = QuestionDetector().classify("什么是机会成本")
        assertNotNull(candidate)
        assertEquals(QuestionDetector.Confidence.STRONG, candidate!!.confidence)
    }

    @Test fun ambiguousEmbeddedQuestionIsWeak() {
        val candidate = QuestionDetector().classify("这个结果为什么会这样")
        assertNotNull(candidate)
        assertEquals(QuestionDetector.Confidence.WEAK, candidate!!.confidence)
    }

    @Test fun classroomPromptBoostsFollowingQuestion() {
        val detector = QuestionDetector()
        assertNull(detector.classify("大家想一想。"))
        val candidate = detector.classify("为什么边际成本会先降后升？")
        assertNotNull(candidate)
        assertEquals(QuestionDetector.Confidence.STRONG, candidate!!.confidence)
        assert(candidate.text.contains("大家想一想"))
    }

    @Test fun acceptedQuestionSuppressesNearDuplicate() {
        val detector = QuestionDetector()
        val first = detector.classify("为什么利率上升会导致企业投资下降？")!!
        assertNotNull(detector.commit(first))
        assertNull(detector.classify("为什么利率上升会导致企业投资下降？"))
    }
}
