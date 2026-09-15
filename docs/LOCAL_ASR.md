# Local ASR

## Default model

ClassHelper 当前主课堂 ASR 是 **Streaming Zipformer Transducer INT8（2025-06-30 中文模型）**，不是旧 README 中描述的 SenseVoice + Silero VAD 链路。

- Model id: `streaming-zipformer-zh-int8-2025-06-30`
- Runtime: sherpa-onnx `1.13.5`
- Provider: CPU
- Model type: `zipformer2`
- Modeling unit: `cjkchar`
- Sample rate: 16 kHz
- Feature dim: 80
- Decoding: `modified_beam_search`
- Max active paths: 4
- Hotword score: 2.0

模型文件：

| 文件 | 约大小 | 用途 |
| --- | ---: | --- |
| `encoder.int8.onnx` | 161 MB | Streaming Zipformer encoder |
| `decoder.onnx` | 5.2 MB | Transducer decoder |
| `joiner.int8.onnx` | 1.0 MB | Transducer joiner |
| `tokens.txt` | 20 KB | 中文 token 表 |

合计约 167 MB。下载前建议至少保留约 260 MB 可用空间。

模型源：

1. Hugging Face：`csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30`
2. HF Mirror 备用源

## Runtime pipeline

```text
AudioRecord (16 kHz mono PCM16)
        ↓
LocalZipformerAsrEngine
        ↓
OnlineRecognizer / Zipformer2
        ↓
partial transcript
        ↓
endpoint
        ↓
final transcript + reset stream
        ↓
QuestionDetector / PDF matching / notes / session storage
```

这是原生 streaming transducer：应用持续向同一个在线识别流送入 PCM，模型持续产生 partial；endpoint 成立后提交 final 并重置该段 stream 状态。

## Endpoint policy

当前 endpoint 配置：

- Rule 1：没有有效语音时约 `3.0 s`
- Rule 2：已经检测到语音后，尾部静音约 `1.60 s`
- Rule 3：单段最长 `30.0 s`

课堂语音中短停顿很多，因此参数比低延迟聊天场景更保守，优先减少一句话被切得过碎。

### 为什么 endpoint 必须 reset

旧实现曾尝试在 endpoint 过早、文字过短时继续复用当前 sherpa stream。实际这会让一个已经处于 endpoint 状态的 native stream 被继续使用，后续可能出现：

- 下一句和上一句粘连；
- 新的 final 不再正常产生；
- partial/final 状态跨句泄漏。

当前实现把 endpoint 明确当作 stream 生命周期边界：**只要 `rec.isEndpoint(stream)` 成立，就完成当前段并调用 `rec.reset(stream)`。**

`StreamingTranscriptState` 负责保存当前段最近一次有效 partial：

- 重复 partial 不重复发布；
- endpoint 时 native final 为空，回退到本段最后有效 partial；
- final 提交后立即清空状态；
- 下一段不会继承上一段 partial。

相应行为有 JVM 单元测试覆盖。

## Hotwords

`ClassroomService` 创建 Zipformer stream 时会通过 `buildAsrHotwords()` 提供课程热词。热词用于课程名、专业名词和当前学习材料相关词语的识别偏置。

Hotwords 只影响 ASR 解码，不会替代后续的 PDF/资料检索。

## Audio lifecycle

`ClassroomService` 不会在课堂中途因为普通 watchdog 检查而随意销毁 Zipformer engine。

- `AudioRecord` 异常：只重建录音端。
- Zipformer 解码流：由 ASR engine 自己维护。
- 停止课堂：先停止麦克风，再 flush 当前识别段，尽量保留最后一句。

这样可以避免为了“恢复”录音而清空仍在排队/缓冲的课堂语音。

## Model download lifecycle

模型由 `AsrModelManager` 管理。

- Android 14+：`AsrModelDownloadJobService`，User-Initiated Data Transfer Job。
- Android 13 及以下：`AsrModelDownloadService`，`dataSync` foreground service。
- 支持 HTTP Range 断点续传。
- 主源失败后会尝试备用镜像。
- 已完整下载的文件直接复用；未完成的 `.part` 可以继续。
- 模型目录优先为应用专属外部 Downloads 下的 `asr_models`，不可用时回退内部 files 目录。

模型准备完成后，课堂 ASR 不需要网络，也没有 ASR URL / host / port 配置。

## Legacy engine note

源码中仍保留 `LocalSenseVoiceAsrEngine.kt` 作为历史/兼容实现，但当前 `ClassroomService.newAsrEngine()` 明确实例化的是 `LocalZipformerAsrEngine`。文档和测试应以 Zipformer 主链路为准。
