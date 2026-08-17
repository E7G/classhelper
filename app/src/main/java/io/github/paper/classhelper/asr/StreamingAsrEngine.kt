package io.github.paper.classhelper.asr

interface StreamingAsrEngine {
    interface Listener {
        fun onState(state: String) {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        fun onError(message: String, cause: Throwable? = null) {}
    }

    fun start(listener: Listener)
    fun sendPcm16(chunk: ByteArray)
    /** Graceful class end: flushes the current local utterance/final transcript before teardown. */
    fun finish(onFinished: () -> Unit = {})
    fun stop()
}
