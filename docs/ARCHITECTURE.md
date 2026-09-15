# Architecture

ClassHelper 是一个 PDF-first 的 Android 原生课堂助手。阅读、课堂录音、ASR、问题识别、资料检索、LLM 和笔记之间通过清晰的模块边界连接；本地阅读/ASR 不依赖 LLM。

## Modules

- `audio/` — Android `AudioRecord`，16 kHz mono PCM16 低分配采集。
- `asr/` — Streaming Zipformer 模型下载、sherpa-onnx `OnlineRecognizer`、partial/final 状态管理与课程热词。
- `classroom/` — microphone foreground service、session/transcript 持久化、问题检测、回答和自动笔记流水线。
- `chaoxing/` — 学习通课程与资料读取/预览兼容层，保持只读。
- `ketangpai/` — 课堂派课程与资源浏览、增量同步与权限边界处理。
- `pdf/` — PDF 阅读配套能力与 PDFBox-Android 标准 annotation 持久化。
- `ocr/` — 可选 PP-OCRv6/ONNX Runtime 高精度 OCR 模型管理与推理。
- `knowledge/` — PDF/OCR/Office/Markdown/TXT 导入、索引和本地检索。
- `library/` — 资料库元数据整理与分类。
- `llm/` — 可选 OpenAI-compatible `/chat/completions` 客户端。
- `data/` — 课程、session、transcript、question、note、reference 等持久化。
- `ui/` — Reader、Settings、Library、学习通/课堂派资料页面。

## ASR path

```text
AudioRecord
   ↓
LocalZipformerAsrEngine
   ↓
sherpa-onnx OnlineRecognizer
   ↓
partial ───────────────→ UI live subtitle
   ↓ endpoint
final + stream reset
   ↓
ClassroomService
   ├─→ transcript DB
   ├─→ QuestionDetector / QuestionPipeline
   ├─→ PDF page matching
   └─→ AutoNotePipeline
```

当前主模型为 `sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30`。模型安装后 ASR 完全本地运行。

`ClassroomService` 的 watchdog 只修复 `AudioRecord`；不会为了普通健康检查销毁仍在工作的 Zipformer engine，以免清掉已缓冲的课堂音频。

## Question / answer path

```text
stable ASR final
   ↓
QuestionDetector.mayBeQuestion
   ↓
quiet thinking-pause gate
   ↓
QuestionDetector.accept
   ↓
current PDF + local references + recent transcript
   ↓
optional OpenAI-compatible LLM
   ↓
answer preview/history
```

partial 只用于实时显示和判断“老师仍在说话”，不会直接触发 LLM。

## Knowledge path

```text
PDF text layer ─┐
scan-page OCR ──┤
PPTX/DOCX ──────┤
Markdown/TXT ───┼─→ local index/retrieval ─→ question context
PDF notes ──────┤
recent transcript┘
```

扫描页优先按需 OCR；普通 PDF 直接使用文本层。

## Platform material path

学习通和课堂派是课程资料接入层，不是自动化刷课层。

- 学习通：读取课程与可访问资料，兼容不同附件 JSON/预览 URL 形态。
- 课堂派：浏览与增量同步课程资源；明确禁止下载的资源不绕过限制。
- 远程资源只有在用户使用对应功能时才产生网络访问。

## Background / download policy

- 课堂录音：microphone ForegroundService。
- Android 14+ ASR 模型下载：User-Initiated Data Transfer `JobService`。
- Android 13 及以下模型下载：`dataSync` ForegroundService。
- 不申请显式 WakeLock。
- 不依赖 WorkManager 周期任务。

## Build-time regression guard

`tools/validate_source.py` 在 CI 中检查关键架构约束，包括：

- Streaming Zipformer 文件、endpoint/reset、modified beam search、hotwords wiring；
- Android 14+ UIDT + 旧系统 FGS 下载路径；
- PDF 批注标准保存路径；
- Reader UI 的关键兼容性标记；
- 禁止重新引入 Flutter、WebView、WAKE_LOCK、WorkManager 周期依赖。
