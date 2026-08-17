# Privacy

## Local speech recognition

The ASR model is downloaded once into app-private storage. Classroom microphone PCM is processed locally by `sherpa-onnx v1.13.5 + SenseVoiceSmall INT8`; the app does not require or contact an ASR server during recognition.

## LLM API

If the user enables question answering/automatic notes, the app may send **text** such as the detected question, recent transcript context, and selected document excerpts to the configured OpenAI-compatible LLM endpoint. The LLM provider's privacy policy applies.

## PDF/OCR

PDF rendering, standard annotation writing, local indexing and ML Kit OCR run on device.

## Storage

ASR model files, working PDFs, transcripts, notes and indexes live in app-private storage except when the user explicitly opens/saves/exports a document through Android's Storage Access Framework.

## Network permission

`INTERNET` is required for the one-time ASR model download and optional LLM API use. Once the ASR model is installed, speech transcription itself does not need network access.
