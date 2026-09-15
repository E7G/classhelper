# Local ASR

## Active model

ClassHelper's classroom recognition path is **SenseVoiceSmall INT8 + Silero VAD**, using sherpa-onnx `1.13.5` on CPU. SenseVoice is an offline model: audio capture remains continuous, Silero detects completed speech segments, and a dedicated worker decodes each segment once. Only stable final text enters transcript storage, question detection, PDF matching, and notes.

- Model id: `sensevoice-small-int8-2024-07-17`
- Language: `zh`
- Inverse text normalization: enabled
- Decode: `greedy_search`
- Audio: 16 kHz mono PCM16, converted to float for inference
- CPU threads: SenseVoice uses 1–3 threads based on available processors; VAD uses 1 thread
- Download: about 229 MiB total; leave at least 340 MiB free for download and installation

| File | Approximate size | Purpose |
| --- | ---: | --- |
| `model.int8.onnx` | 228 MiB | SenseVoiceSmall acoustic model |
| `tokens.txt` | 309 KiB | Token table |
| `silero_vad.onnx` | 629 KiB | Speech segmentation |

Sources use Hugging Face with HF Mirror fallback for SenseVoice; Silero VAD uses the sherpa-onnx release with ModelScope fallback. Files are validated before activation. Model preparation supports resumable downloads.

## Runtime pipeline

```text
AudioRecord (16 kHz mono PCM16)
        ↓
bounded pre-init audio buffer (up to 20 seconds)
        ↓
Silero VAD (512 samples / 32 ms windows)
        ↓
completed utterance ──→ dedicated decode worker
                              ↓
                    SenseVoiceSmall INT8
                              ↓
                     stable final text
                              ↓
       transcript / question detector / PDF matching / notes
```

VAD parameters are tuned for lectures, where pauses can occur inside clauses:

- Threshold: `0.48`
- Minimum speech: `0.20 s`
- Minimum trailing silence: `1.8 s`
- Maximum segment: `30.0 s`
- Flush: pad the final partial VAD window, flush detector state, then drain queued segments when stopping

Longer utterances retain more context and reduce mid-sentence fragmentation. The trade-off is later final delivery and no live partial subtitle. This SenseVoice path does not apply Zipformer hotword bias; the retained course-term setting is informational and disabled for this engine.

## Threading and lifecycle

- Audio capture hands off copied PCM buffers to a single audio/VAD worker; conversion does not retain `AudioCapture`'s reusable byte array.
- While model initialization runs, a bounded 20-second PCM queue preserves the beginning of a lecture without unbounded memory growth.
- Completed segments are submitted in order to a single decode worker. Offline streams are released after each segment.
- `finish()` flushes the last partial VAD window and queued segments before reporting completion.
- `AudioRecord` recovery is independent; a routine watchdog check must not tear down a healthy ASR engine.

## Model download lifecycle

`AsrModelManager` owns the active model, integrity checks, mirror fallback, resumable `.part` files, and removal of legacy model directories. On Android 14+, download uses a User-Initiated Data Transfer `JobService`; Android 13 and below use the `dataSync` foreground service. Models are stored under app-specific Downloads when available and otherwise under app files.

After download, classroom inference is fully local and requires no ASR URL, host, or port. Existing Zipformer files are removed by model cleanup after migration to the new active model.

## Quality benchmark note

The supplied lesson video was evaluated against its companion VTT with whitespace and punctuation excluded from character error rate. The selected `1.8 s` VAD hangover scored **94.72% character accuracy** (CER 5.28%), versus **84.91%** for the previous Zipformer app parameters on the same sample. It commits about 200 ms sooner than the previous 2.0-second setting. This is a single-video comparison against an unverified subtitle, not a guaranteed production accuracy rate; it did not reach the requested 95% threshold.
