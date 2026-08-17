# Architecture

- `audio/` — Android `AudioRecord`, 16 kHz mono PCM16, low-allocation capture.
- `asr/` — app-owned model downloader plus local `sherpa-onnx v1.13.5 + SenseVoiceSmall INT8` JNI runtime; no ASR server configuration.
- `classroom/` — foreground microphone service, transcript/session persistence, question detector, fast answer and auto-note pipelines.
- `pdf/` — PDFium reader overlay plus standard PDF annotation persistence through PDFBox-Android.
- `knowledge/` — PDF/OCR/Office/Markdown/TXT import and local retrieval.
- `llm/` — optional OpenAI-compatible API for answers and note organization.
- `data/` — course/session/transcript/question/note/reference storage.

ASR path:

`AudioRecord -> LocalSenseVoiceAsrEngine -> Silero VAD endpoint -> sherpa-onnx v1.13.5 + SenseVoiceSmall INT8 -> final text -> QuestionDetector/notes`

AI answer path:

`question -> current PDF + local references + recent transcript -> LLM API -> answer preview/history`

Speech recognition remains local after the one-time model download. The LLM layer is independent and can be absent when only transcription/PDF reading is needed.
