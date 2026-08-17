# ClassHelper Native v1.7.0 — MD3E + SenseVoiceSmall + PP-OCRv6

> UI 基于官方 Material 3 Expressive 主题重构：动态色、浮动圆角工具栏、Tonal Card、MD3 文本框与更清晰的阅读/课堂层级。底层 PDF、SenseVoiceSmall、后台下载和课堂逻辑保持不变。

> Build baseline: AGP 8.9.1 · Gradle 8.11.1 · Kotlin 2.2.0 · compileSdk 36 · JDK 17.

ClassHelper Native 是旧 Flutter ClassHelper 的 **Android 全原生重写**。旧工程只作为需求参考；本工程不包含 Flutter、Dart、WebView 或本地 LLM。语音识别使用本地 SenseVoiceSmall INT8，sherpa-onnx 仅作为 Android native 推理运行时。

目标：**帮我听课，密而不疏**——后台持续听课、尽量不漏老师问题、老师提问时快速显示参考答案，同时保留原始课堂记录并自动整理笔记。

当前版本：`1.7.0-sensevoice`

## 内置语音模型源

主 ASR 为 **SenseVoiceSmall INT8 (2024-07-17)**。选择这一版是因为它支持普通话、粤语、英语、日语、韩语，并且在 `useInverseTextNormalization=true` 时可以输出数字/标点，更适合课堂问题检测和自动笔记。

运行文件：

- `model.int8.onnx` — 约 228 MB
- `tokens.txt` — 约 308 KB
- `silero_vad.onnx` — 约 0.6 MB，用于本地分句

合计首次下载约 **230 MB**。主模型下载源为 sherpa-onnx 转换后的 Hugging Face 仓库 `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`，并提供 HF Mirror 备用地址。模型下载完成后，课堂语音识别完全在手机本地完成。

## 第一次打开怎么用

1. 点击 **“打开 PDF”** 打开课件/教材。
2. 直接点 **“开始听课”**。
3. 第一次如果还没有语音模型，App 会弹出 **“下载并开始”**：下载约 230 MB 的高准确率本地模型。
4. 下载完成后自动进入麦克风权限流程并开始识别。
5. 以后再点“开始听课”即可直接使用，**没有 ASR 地址、端口、服务器等配置**。
6. LLM API 只用于老师提问抢答和自动笔记；只需要转写/PDF 阅读时可以不配置 LLM。

设置页仍提供模型状态、下载/删除和课程热词，方便管理，但不是开始听课的必经步骤。

## 1. App 内置模型下载 + 本地语音识别

语音识别现在完全由 App 管理：

```text
首次点击开始听课
      ↓
模型不存在
      ↓
下载并开始（约 230 MB）
      ↓
App 私有目录 /asr_models
      ↓
sherpa-onnx v1.13.5 + SenseVoiceSmall INT8 本地加载
      ↓
以后无需任何 ASR 配置
```

默认模型：`SenseVoiceSmall INT8 2024-07-17`，文件为 `model.int8.onnx + tokens.txt`，并附加 Silero VAD，合计约 230 MB。

下载器：
- Android 14+ 使用 UIDT Job；Android 13 及以下保留顺序 `dataSync` 前台服务兼容路径。
- 模型文件串行下载，支持 HTTP Range 断点续传。
- 已下载完整文件会直接复用，`.part` 文件会继续下载。
- 下载失败只影响模型状态，不应让 ReaderActivity 崩溃。


课堂识别不连接 ASR 服务器，音频不需要上传到第三方 ASR 服务。

> 运行时说明：v1.5 已移除旧 Streaming Paraformer 主模型，改为 SenseVoiceSmall INT8。SenseVoice 通过短停顿分段做准实时本地识别；ASR 仍封装在 `StreamingAsrEngine` 接口后，PDF、课堂问答和笔记层不依赖具体模型。

### 本地准实时识别链路

```text
AudioRecord 16 kHz PCM16
        ↓
初始化期间最多缓存约 20 秒 PCM
        ↓
Silero VAD（约 0.45 s 静音判定句末）
        ↓
SenseVoiceSmall INT8 完整语句识别
        ↓
稳定 final transcript → 问题检测 / PDF 匹配 / 自动笔记
```

这版以准确率优先：
- 不再使用简单 RMS/能量阈值硬切课堂语音。
- Silero VAD 使用约 `0.45 s` 静音、`0.2 s` 最短语音、最长 `20 s` 分段。
- SenseVoice 只对 VAD 完整语句做一次解码，避免讲话过程中反复重算。
- SenseVoice 模型加载期间保留最多约 20 秒音频，降低“刚开始听课时第一句话丢失”的概率。
- 正式课堂记录、老师问题检测和自动笔记全部只消费稳定 final。

详见 [`docs/LOCAL_ASR.md`](docs/LOCAL_ASR.md)。


## 高精度 PDF OCR（PP-OCRv6）

普通 PDF 仍优先直接读取文本层；疑似扫描页才运行 OCR。设置页可选下载约 30 MB 的 **PP-OCRv6 Small** 高精度模型，使用 ONNX Runtime 在设备本地执行“文本检测 → 逐行识别 → CTC 解码 → 阅读顺序重排”。模型未安装或单页识别失败时自动回退现有 ML Kit 中文 OCR。

- 自动索引：只 OCR 文本层过少的扫描页。
- 阅读器“更多 → OCR”：显式重新识别整份 PDF。
- 默认模式：扫描页长边约 2400 px；高精度/强制 OCR 可到约 3000 px，并有 12 MP 内存上限。
- PP-OCRv6 模型下载后进行 SHA-256 校验；字典直接从 ONNX `character` metadata 读取，避免模型/词表错配。
- OCR 图片不会发给 LLM API。

详见 [`docs/OCR.md`](docs/OCR.md)。

## 2. 资料库与存储管理

资料库用于统一整理应用内真正需要长期保留的学习内容，同时把系统资源占用单独列出：

- **内容资料**：主阅读 PDF、导入参考资料、课堂会话。
- **关联内容**：转写、问答、AI/手工笔记、PDF 批注、便签、书签。
- **系统占用**：PDF 私有工作副本、SenseVoice 模型、SQLite/全文索引、临时缓存。

每个可整理对象支持：一级分类、二级分类、课程、主题、标签、备注、状态、收藏，并明确显示是 **AI / 本地规则 / 手动** 整理；AI 条目额外保留置信度与分类依据。

AI 整理是“元数据建议器”，不会自动移动、重命名或删除文件。配置 LLM 后可逐项或批量处理未分类内容；没有可用 LLM 时只做本地规则初分。手动编辑始终具有最终决定权。

详细分类规则与删除边界见 [`docs/LIBRARY.md`](docs/LIBRARY.md)。

## 3. 原生 PDF 阅读器

### v1.4 沉浸式阅读 UI
- PDF 占满整个 ReaderActivity；顶栏和底栏改为覆盖层，不再减少 PDF 可视高度。
- 轻点 PDF 显示工具栏；普通操作后约 6 秒自动隐藏，滑动结束后延迟收起，避免轻微拖动导致工具栏瞬间消失。
- 顶栏仅保留打开、文件/页码、听课、课堂侧栏、设置。
- 底栏仅保留翻页、页码、搜索、批注、更多；批注和低频工具按需展开。
- 实时转写、页匹配、老师提问、AI 答案、课堂记录统一到一个右侧课堂侧栏。
- 侧栏的转写/记录区域独立滚动，底部提问框和资料/课堂/整理/导出操作固定，不会再被长转写顶出屏幕。
- 批注模式保持必要工具可见，不执行阅读态自动隐藏。

- Kotlin + XML/View UI。
- AhmerPdfViewer / Pdfium 渲染。
- 连续阅读、双指/双击缩放、上下页、页码跳转；支持“整页”一键回到完整页视图。
- PDF 目录读取与跳转。
- 全文搜索。
- 应用内书签。
- 自动记录当前 PDF 与阅读页码。
- 课堂稳定转写后，本地匹配 PDF 页面并提示“可能讲到 Pxx”；默认不强制自动翻页。

## 4. 批注真正写回 PDF

不是 Canvas 假覆盖，而是标准 PDF Annotation：

- 自由画笔 → Ink Annotation。
- 荧光笔 → Ink Annotation + 透明度。
- 文字便签 → Text Annotation。
- AI 答案可附到当前页。
- 圆形轨迹橡皮只删除 ClassHelper 创建的批注；拖动实时显示擦头/扫掠轨迹，长按可清空当前页。
- 撤销 / 重做。
- 稳定 Annotation UUID。
- appearance stream 提高第三方阅读器兼容性。

保存策略：
1. 落笔先在内存画布立即显示，journal/history 通过后台 write-behind 迅速持久化，不阻塞触摸线程。
2. 连续笔画/擦除批量写 App 私有工作 PDF；擦除快速连划会合并提交。
3. 显式保存或离开时同步用户原 PDF。
4. 原文档不可写时可以“另存为”。

## 4. 扫描 PDF 本地 OCR

- 优先使用 PDF 原文本层。
- 只有文本极少的页面才 OCR。
- ML Kit 中文 OCR 在设备端执行。
- 单 recognizer、逐页处理，控制 Bitmap 和内存峰值。
- OCR 文本进入与普通 PDF 相同的搜索/资料索引。

## 5. 后台持续听课

- Android `AudioRecord`。
- microphone ForegroundService。
- 在 App 前台点击“开始听课”后，可切后台或锁屏继续。
- 不申请显式 WakeLock。
- 每堂课独立 Session。
- 原始 final 转写永久保留。
- 正常结束时先停止麦克风，再 flush 最后一段语音，然后结束 Session。

## 6. 老师问题抢答

- 去掉固定 15 秒冷却。
- recall-first 问句评分 + 近重复抑制。
- SenseVoice 以 VAD 完整语句 final 作为问题检测输入，并启用 ITN/标点以改善问句检测。
- 句末约 0.5 秒静音即提交识别；识别完成后马上进入资料检索/LLM 抢答。

问题上下文优先级：
1. 当前 PDF 当前页 ±2。
2. 当前 PDF FTS / 中文 n-gram 相关片段。
3. PDF 文字便签。
4. PPTX / DOCX / Markdown / TXT 参考资料。
5. 最近课堂 final 转写。

## 7. LLM API

- OpenAI-compatible `/chat/completions`。
- SSE 流式显示答案。
- API Key 可用 Android Keystore 加密保存。
- 局域网无鉴权兼容接口可留空 API Key。
- **语音识别不依赖 LLM API**。

如果 LLM 没有配置，本地 PDF 阅读、手写、OCR、后台语音转写仍可使用；只是 AI 抢答和自动整理不可用。

## 8. 自动课堂笔记

- 原始 final transcript 和 AI 笔记分开保存。
- 自动提取主题、核心概念、老师强调、课堂问题、例子、易错点、待复习内容。
- 自动笔记是低优先级批处理，不阻塞老师提问抢答。
- 下课时会整理最后一轮课堂内容。
- 整堂 Session 可导出 Markdown（时间、智能笔记、问题/答案/依据、原始转写）。

## 9. 更多参考资料

支持导入：
- `.pptx`
- `.docx`
- `.md`
- `.txt`

统一进入本地资料检索索引，供老师提问时作为参考。PPTX/DOCX 当前做轻量 OOXML 文本抽取，不作为 Office 排版阅读器。

## 总体架构

```text
                         ┌───────────────────────────┐
                         │      PDF Reader / UI      │
                         │ Pdfium + XML/View         │
                         └────────────┬──────────────┘
                                      │ current page
                                      ▼
AudioRecord ──PCM──► Silero VAD + LocalSenseVoiceAsrEngine ─► ClassroomService
                         │ partial/final                    │
                         └──────────────────────────────────┤
                                                            │
                      ┌────────────────────┬────────────────┴───┐
                      ▼                    ▼                    ▼
               QuestionDetector      AutoNotePipeline      Page Matcher
                      │                    │                    │
                      ▼                    │                    └─► Pxx 提示
              KnowledgeRepository         │
              ├ PDF current ±2            │
              ├ PDF FTS / n-gram          │
              ├ PDF text notes            │
              ├ PPTX/DOCX/MD/TXT          │
              └ recent transcript         │
                      │                    │
                      └────────────┬───────┘
                                   ▼
                        OpenAI-compatible LLM
                                   │ SSE
                                   ▼
                         答案预览 / 自动笔记

InkOverlay ─► AnnotationJournal ─► PDFBox-Android ─► 工作 PDF ─► 原 PDF / 另存
```

## 权限与隐私

需要：
- `RECORD_AUDIO`：课堂听课。
- `FOREGROUND_SERVICE_MICROPHONE`：后台持续录音。
- `POST_NOTIFICATIONS`：后台状态/答案预览（Android 13+）。
- `INTERNET`：**首次语音模型下载**以及可选的 LLM API。

不会申请：
- `WAKE_LOCK`
- 存储全盘权限
- 悬浮窗权限

数据流：
- **语音模型下载好以后，课堂 PCM 由手机本地 sherpa-onnx v1.13.5 + SenseVoiceSmall INT8 处理，不发送到 ASR 服务器。**
- PDF/OCR/资料索引默认在本地处理。
- 只有使用 AI 抢答/自动笔记时，问题文本、相关资料片段和近期课堂文本会发送到你配置的 LLM API。

详见 [`docs/PRIVACY.md`](docs/PRIVACY.md)。

## 低占用 / 低功耗策略

- 无 Flutter / WebView / 本地 LLM 常驻。
- ASR 只使用一个本地 native 模型上下文。
- partial 解码按固定间隔采样，不对每个 60 ms 音频帧都推理。
- 端点后再完整 final 解码，普通静音阶段不持续跑 LLM。
- 自动笔记批处理。
- 页面匹配使用 FTS + 中文 n-gram，不常驻 embedding 模型。
- OCR 只处理疑似扫描页且顺序执行。
- PDF 批注先写工作副本，减少 SAF 原文件频繁 IO。
- Release 开启 R8/resource shrink。
- 当前 APK native ABI 默认只打包 `arm64-v8a`，减少体积；如需 x86_64/armeabi-v7a 可在 `app/build.gradle.kts` 调整。

## 构建

要求：
- JDK 17
- Android SDK Platform 36
- Android Build Tools 36.0.0

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

构建阶段会固定获取官方 `sherpa-onnx v1.13.5` Android AAR 并校验 SHA-256；**约 230 MB 的 SenseVoiceSmall INT8 模型不会打进源码包/APK，而是在 App 首次使用时自行下载**。

GitHub Actions 安装 Android 36，并执行 runtime 获取校验、source validation、unit test、lint、assembleDebug。

## 编译兼容基线

已保留用户确认的修复：
- Android Gradle Plugin 8.9.1。
- compileSdk 36。
- Kotlin 2.2.0。
- PDFBox-Android 使用 `setNormalAppearance` 兼容 API。
- AhmerPdfViewer 使用其实际 Kotlin Listener 回调签名。
- AppCompat theme 属性名称已修正。

## License

本项目采用 GPL-3.0。第三方依赖、sherpa-onnx v1.13.5 + SenseVoiceSmall INT8 和运行时下载模型说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
