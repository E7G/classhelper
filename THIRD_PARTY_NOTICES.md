# Third-party notices

ClassHelper Native itself is distributed under **GPL-3.0**.

Android/runtime dependencies:

- **AhmerPdfium / AhmerPdfViewer** — Apache License 2.0 — PDFium-based Android PDF rendering/viewer.
- **PdfBox-Android (Tom Roush)** — Apache License 2.0 — Android port of Apache PDFBox; used for text extraction, outlines, and standard PDF annotation persistence.
- **OkHttp / Okio** — Apache License 2.0 — model download and LLM HTTP/SSE transport.
- **AndroidX** — Apache License 2.0.
- **Kotlin Coroutines** — Apache License 2.0.
- **Google ML Kit Text Recognition (Chinese)** — Google ML Kit terms/licensing apply — on-device Chinese OCR fallback.
- **ONNX Runtime Android** — MIT License — local PP-OCRv6 detector/recognizer runtime.
- **sherpa-onnx v1.13.5** — MIT License — Android native ASR/VAD runtime through the official AAR.
- **Silero VAD model** — runtime-downloaded speech activity model used for sentence segmentation.

Runtime-downloaded model:

- **RapidOCR PP-OCRv6 Small detector/recognizer ONNX weights** — downloaded from the RapidAI/RapidOCR ModelScope model repository, SHA-256 verified, and stored in app-private storage. Review the upstream RapidOCR/PaddleOCR model licenses before redistribution.


The model binaries are not embedded in this source archive.

External service:

- **LLM provider/model** — optional user-supplied OpenAI-compatible endpoint. Only AI Q&A/note features need it; local speech recognition does not. Provider/model terms apply.

Before redistributing an APK, review the exact dependency/model versions and retain all notices required by their licenses/terms.

- **SenseVoiceSmall** — model weights from FunAudioLLM / ModelScope, used through the sherpa-onnx converted INT8 ONNX model. Check the upstream model card/license before redistribution.
- **SenseVoiceSmall INT8 (`model.int8.onnx` + `tokens.txt`)** — downloaded on first use from the sherpa-onnx converted model repository and stored in app-private storage.
- **silero_vad.onnx** — downloaded alongside SenseVoice and used only for local speech endpoint detection.
