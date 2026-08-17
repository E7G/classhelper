# Local ASR

## Default model

- **SenseVoiceSmall INT8 (2024-07-17)**
- Languages: Mandarin Chinese, Cantonese, English, Japanese, Korean
- Files: `model.int8.onnx`, `tokens.txt`, plus `silero_vad.onnx`
- Download size: about 230 MB; keep at least 340 MB free during installation.
- Runtime: sherpa-onnx v1.13.5, CPU, fully local after download.

The 2024-07-17 model is intentionally used instead of the 2025-09-09 Cantonese-tuned model because the former supports ITN/punctuation when enabled, which is more useful for classroom question detection and notes.

## Runtime pipeline

`AudioRecord -> Silero VAD -> SenseVoiceSmall INT8 -> final transcript -> question detector / PDF matching / notes`

SenseVoice is offline rather than a native streaming transducer. The app keeps AudioRecord running continuously and uses short VAD-completed utterances to provide near-real-time classroom transcription without repeatedly decoding the same growing audio buffer.

- VAD silence endpoint: about 450 ms
- Minimum speech: 200 ms
- Maximum single segment: 20 s
- ITN: enabled
- Language: auto
- Model-load audio safety buffer: up to 20 s

The ASR server URL setting is not used. Download the model once and tap **开始听课**.
