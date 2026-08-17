package io.github.paper.classhelper.classroom

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuestionDetectorTest {
    @Test fun ignoresNormalLectureStatement() {
        assertNull(QuestionDetector().accept("今天我们继续学习投资理论。"))
    }

    @Test fun acceptsTeacherQuestionAndSuppressesNearDuplicate() {
        val detector = QuestionDetector()
        assertNotNull(detector.accept("为什么利率上升会导致企业投资下降？"))
        assertNull(detector.accept("为什么利率上升会导致企业投资下降？"))
    }

    @Test fun acceptsStrongStreamingPartialWithoutQuestionMark() {
        assertNotNull(QuestionDetector().acceptPartial("那么为什么利率上升会让投资下降"))
    }

    @Test fun acceptsShortClassroomQuestion() {
        assertNotNull(QuestionDetector().accept("什么是机会成本？"))
    }
}
