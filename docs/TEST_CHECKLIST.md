# Test checklist

## First-run local ASR

- Delete the ASR model from Settings.
- Tap “开始听课” in Reader.
- Confirm the first-run flow offers model download.
- Confirm the model shown is Streaming Zipformer INT8, not SenseVoice.
- Confirm progress is visible and an interrupted download can resume from `.part`.
- Confirm the Hugging Face source can fall back to HF Mirror when needed.
- Android 14+：confirm download is scheduled through the user-initiated transfer job.
- Android 13 and below：confirm the `dataSync` foreground downloader is used.
- On completion, confirm microphone permission is requested and listening starts.
- Disable network after the model is ready and confirm transcription still works.
- Confirm Settings has no ASR URL / host / port fields.

## Streaming ASR quality / lifecycle

- Speak continuous Chinese with short natural pauses and confirm partial text updates continuously.
- Pause for about 1.6 s after speech and confirm one stable final is emitted.
- Speak a very short utterance such as “好” or “对” and confirm it can finalize instead of poisoning the next segment.
- Immediately speak a second sentence after the first endpoint and confirm text from the previous partial does not leak into it.
- Confirm later sentences continue producing partial/final results after multiple endpoints; no merged/stalled endpointed stream.
- Confirm duplicate partial text is not repeatedly published.
- Test course/PDF hotwords and confirm recognition still initializes normally with and without hotwords.
- Background/lock screen during an active class and confirm the microphone foreground service continues.
- Force/reproduce an `AudioRecord` interruption and confirm only audio capture is rebuilt; the classroom session is not silently discarded.
- Stop class during speech and confirm the last segment is flushed to final transcript when possible.

## Question pipeline

- Confirm live partial alone never directly triggers an LLM request.
- Speak a question and continue talking immediately; pending question handling should be cancelled by new speech.
- Speak a question and leave the configured thinking pause; confirm it enters the answer pipeline.
- Leave LLM settings empty and confirm local ASR/PDF/records remain usable.
- Configure an OpenAI-compatible endpoint and confirm answer preview/history works.

## Learning platform resources

### 学习通

- Login/load courses and confirm course list can be read.
- Test courses whose attachment `objectId` uses different common field spellings/nesting.
- Test a course whose `attachments` array is nested rather than at the expected top level.
- Confirm PDF/file material can be previewed or delegated to the system browser as appropriate.
- Confirm the module remains read-only: no sign-in, course-progress submission, homework submission or course mutation.

### 课堂派

- Load course list and open resource browser on a high-DPI device.
- Confirm controls are not collapsed into thin raw-pixel-height rows.
- Sync resources twice and confirm unchanged files are reused instead of blindly redownloaded.
- Confirm Unicode filenames remain readable.
- For a resource explicitly marked as non-downloadable, confirm ClassHelper does not bypass that restriction and only keeps allowed metadata/preview behavior.

## PDF / annotation

- Open normal and large PDFs.
- Pen/highlight/eraser/undo/redo, save, close, reopen in another PDF reader and confirm standard annotations persist.
- Test rapid erasing and verify removed annotations do not reappear after save/reopen.
- Test Save As with SAF.
- Open a scanned PDF and confirm OCR is only used automatically for pages with little/no text layer.
- Force high-accuracy OCR and confirm PP-OCRv6 can fall back safely when unavailable/failing.

## Library / knowledge

- Import PDF plus `.pptx`, `.docx`, `.md`, `.txt` references.
- Confirm local retrieval can return relevant content without LLM configured.
- Confirm removing an item from the ClassHelper library does not delete the user's original external document unless the UI explicitly states otherwise.
- Confirm an active classroom session cannot be accidentally deleted.

## CI / release

- `python3 tools/validate_source.py` prints `VALIDATION OK`.
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` passes.
- `./gradlew :app:assembleRelease` passes.
- CI Android SDK setup must not request the removed legacy SDK package `tools`.
- Release packaging verifies APK signature, runs `aapt dump badging`, and generates `.sha256`.
- Main-branch success publishes the tag matching `versionName` and attaches APK + checksum.
