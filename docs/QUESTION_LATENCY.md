# Question latency

The active SenseVoice + Silero VAD path emits a stable final only after the VAD boundary is reached. With the current lecture tuning, that boundary includes about 1.8 seconds of trailing silence.

A pause is therefore treated as evidence that an utterance ended, not as proof that the teacher asked a question. `QuestionDetector` classifies stable finals into two confidence levels after applying classroom prompt boosts, lecture/explanation penalties and duplicate suppression.

- `STRONG`: direct interrogatives, explicit question punctuation or strong classroom prompts such as “大家想一想” / “谁能回答” / “请解释”. These enter retrieval and the optional LLM immediately after stable final.
- `WEAK`: ambiguous interrogative wording that could still be ordinary lecture narration. These use a 900 ms confirmation window.

The weak confirmation window is speech-aware. Silero VAD publishes speech-start/speech-end transitions through `StreamingAsrEngine.Listener`; if the teacher resumes speaking before the weak window expires, `ClassroomService` cancels that candidate immediately. This avoids turning routine lecture pauses into false questions while preserving the strong-question fast path.

Common explanatory constructions such as “我们来看为什么……”, “下面讲一下怎么……” and “接下来我们看什么叫……” are explicitly down-weighted rather than relying on a longer global pause timer.
