# Test checklist

## First-run local ASR
- Delete the model from Settings.
- Tap “开始听课” in Reader.
- Confirm the first-run dialog offers “下载并开始”.
- Confirm progress appears and download survives a temporary network interruption/resumes from `.part`.
- Confirm ModelScope failure falls back to the secondary mirror.
- On completion, confirm microphone permission is requested and listening starts automatically.
- Disable network after the model is ready and confirm transcription still works.
- Confirm Settings has no ASR URL/host/port fields.

## ASR quality / lifecycle
- Speak continuous Chinese with short pauses and confirm partial text updates.
- Confirm final text is emitted after endpoint silence and does not duplicate overlap text.
- Background/lock screen during an active class and confirm foreground microphone service continues.
- Stop class during speech and confirm the last segment is flushed to final transcript.

## PDF / annotation
- Open normal and large PDFs.
- Pen/highlight/eraser/undo/redo, save, close, reopen in another PDF reader and confirm standard annotations persist.
- Test Save As with SAF.
- Open scanned PDF and confirm OCR is only used for pages with little/no text layer.

## AI
- Leave LLM fields empty: local transcription/PDF reading must still work.
- Configure an OpenAI-compatible endpoint and confirm teacher-question preview and automatic notes.
- Verify document evidence/references remain local until a Q&A/note request is made.
