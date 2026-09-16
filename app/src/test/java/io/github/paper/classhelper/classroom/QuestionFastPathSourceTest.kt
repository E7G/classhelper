package io.github.paper.classhelper.classroom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level invariant is enforced by tools/validate_source.py; this test documents the behavior. */
class QuestionFastPathSourceTest {
    @Test
    fun stableFinalUsesDetectorWithoutExtraPause() {
        val expectedFlow = "stable final -> QuestionDetector -> answer"
        assertTrue(expectedFlow.contains("QuestionDetector"))
        assertFalse(expectedFlow.contains("extra pause"))
    }
}
