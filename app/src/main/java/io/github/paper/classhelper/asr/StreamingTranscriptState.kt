package io.github.paper.classhelper.asr

/** Tracks one native Zipformer segment and prevents partial text leaking across endpoint resets. */
internal class StreamingTranscriptState {
    private var partialText = ""

    /** Returns changed, non-empty text for UI publication; duplicate partials are ignored. */
    fun updatePartial(text: String): String? {
        val clean = text.trim()
        if (clean.isBlank() || clean == partialText) return null
        partialText = clean
        return clean
    }

    /** Completes the current segment, falling back to its latest partial, then clears state. */
    fun takeFinal(text: String): String? {
        val result = text.trim().ifBlank { partialText }
        reset()
        return result.ifBlank { null }
    }

    fun reset() {
        partialText = ""
    }
}
