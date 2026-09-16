# Question latency

The active SenseVoice + Silero VAD path emits a stable final only after the VAD boundary is reached. With the current lecture tuning, that boundary includes about 1.8 seconds of trailing silence.

Question handling therefore treats stable final delivery as the pause signal. `ClassroomService` does not add another fixed post-final thinking delay. A likely question immediately enters `QuestionDetector`; accepted questions then start retrieval and the optional LLM pipeline.

False-positive control remains in `QuestionDetector` through question scoring, short cross-segment context and duplicate suppression rather than an additional latency timer.
