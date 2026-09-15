package io.github.paper.classhelper.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingTranscriptStateTest {
    @Test fun keepsShortEndpointAndDoesNotLeakTextIntoNextSegment() {
        val state = StreamingTranscriptState()

        assertEquals("好", state.updatePartial("好"))
        assertEquals("好", state.takeFinal(""))
        assertEquals("下一句", state.updatePartial("下一句"))
        assertEquals("下一句", state.takeFinal("下一句"))
    }

    @Test fun finalResultWinsAndEmptySegmentProducesNoTranscript() {
        val state = StreamingTranscriptState()

        state.updatePartial("初始识别")
        assertEquals("修正后的完整句子", state.takeFinal("修正后的完整句子"))
        assertNull(state.takeFinal(""))
    }

    @Test fun duplicatePartialIsSuppressed() {
        val state = StreamingTranscriptState()

        assertEquals("课堂内容", state.updatePartial("课堂内容"))
        assertNull(state.updatePartial("课堂内容"))
    }
}
