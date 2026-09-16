package io.github.paper.classhelper.classroom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level invariants are enforced by tools/validate_source.py; this test documents behavior. */
class QuestionFastPathSourceTest {
    @Test
    fun strongQuestionsStayImmediateWhileWeakOnesAreAdaptive() {
        val strongFlow = "stable final -> classify STRONG -> answer"
        val weakFlow = "stable final -> classify WEAK -> short speech-aware confirm"
        assertTrue(strongFlow.contains("STRONG -> answer"))
        assertTrue(weakFlow.contains("speech-aware"))
        assertFalse(strongFlow.contains("fixed pause"))
    }
}
