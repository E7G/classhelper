# ClassHelper Native 1.12.10

> Android 原生课堂听课助手：PDF 阅读/批注 + 本地课堂 ASR + 课堂问题检测 + 可选 LLM 抢答/笔记 + 学习通/课堂派课程资料接入。

ClassHelper Native 是一个 **PDF-first** 的 Android 课堂助手。核心目标是让“看课件、听老师、留课堂记录、发现提问、查资料”保持在同一个工作流里。

主 ASR 为 **sherpa-onnx 1.13.5 + SenseVoiceSmall INT8 + Silero VAD**。长语音先由 VAD 保留上下文、按完整语句切分，再交给离线 SenseVoice 解码，优先提升课堂连续讲解的识别质量。

当前版本：`1.12.10-question-fastpath`

## 当前技术基线

- Android 原生 Kotlin + XML/View UI
- `minSdk 26` / `targetSdk 35` / `compileSdk 36`
- JDK 17
- arm64-v8a
- sherpa-onnx `1.13.5`
- SenseVoiceSmall INT8 + Silero VAD 中文 ASR
- AhmerPdfViewer + PDFBox-Android
- ML Kit 中文 OCR + 可选 PP-OCRv6/ONNX Runtime 高精度 OCR
- OpenAI-compatible `/chat/completions` LLM 接口（可选）

## 1. 本地课堂 ASR

默认模型：`sensevoice-small-int8-2024-07-17`

模型文件：

- `model.int8.onnx`：约 228 MiB
- `tokens.txt`：约 309 KiB
- `silero_vad.onnx`：约 629 KiB

总下载量约 **229 MiB**。应用在开始听课时检测模型；未安装时可直接启动系统管理的后台下载。模型准备完成后，课堂语音识别在手机本地运行，不依赖 ASR 服务器。建议至少保留 **340 MiB** 可用空间。

当前识别链路：

```text
AudioRecord 16 kHz PCM16
        ↓
Silero VAD (512-sample windows)
        ↓
completed speech segment
        ↓
SenseVoiceSmall INT8 / greedy offline decode
        ↓
stable final → 课堂记录 / 问题检测 / PDF 匹配 / 自动笔记
```

准确率优先的 VAD 参数：

- 语言提示：`zh`；启用逆文本规范化（ITN）
- VAD 阈值：`0.48`；最短静音：`1.8 s`；最短语音：`0.2 s`
- 单段最长：`30 s`；较长上下文能减少老师句中停顿导致的碎片化
- 音频持续采集；每个完整 VAD 语段异步解码一次，再只将稳定 final 送入课堂后续流水线

此链路不产生实时 partial，也不使用课程热词偏置；设置页会明确说明该取舍。由于当前结果不是实时输出，Reader 已移除实时字幕浮层；稳定 final 仍会保存为课堂转写并继续用于问题检测、PDF 匹配和自动笔记。

详见 [`docs/LOCAL_ASR.md`](docs/LOCAL_ASR.md)。

## 2. ASR 模型下载

模型由 App 自己管理，支持断点续传和镜像回退：

- 主源：Hugging Face
- 备用：HF Mirror
- Android 14+：User-Initiated Data Transfer `JobService`
- Android 13 及以下：`dataSync` 前台服务
- 推荐至少保留约 **340 MiB** 可用空间

下载目录优先使用应用专属外部 Downloads 下的 `asr_models`；不可用时回退到内部应用目录。删除模型不会影响 PDF、课堂记录或资料库数据。

## 3. PDF 阅读与标准批注

ReaderActivity 仍以 PDF 为主界面：

- 连续阅读、缩放、翻页、页码跳转、全文搜索、目录、书签
- 顶栏/底栏覆盖 PDF，不长期挤压阅读区
- 工具栏可自动隐藏，批注状态下保持必要控件可见
- 课堂转写、页匹配、老师问题、AI 答案统一在课堂侧栏
- 课堂识别只提示可能相关页，不根据弱匹配强制自动跳页

批注使用 PDF 标准 Annotation 持久化：

- 画笔：Ink Annotation
- 荧光笔：Ink Annotation + 透明度
- 文字便签：Text Annotation
- 橡皮：删除 ClassHelper 创建的对应批注
- 撤销 / 重做
- 采用临时文件 + 标准保存 + 原子替换优先的持久化路径

## 4. PDF 文本、OCR 与本地知识库

PDF 优先读取文本层；扫描页再进入 OCR。

- 默认 OCR：ML Kit 中文文本识别
- 可选高精度 OCR：PP-OCRv6 Small + ONNX Runtime
- 普通自动索引只对文本层过少的页面执行 OCR
- 支持导入 `.pptx` / `.docx` / `.md` / `.txt` 参考资料
- PDF、OCR 文本、Office/Markdown/TXT 进入本地检索，用于课堂问题的资料上下文

详见 [`docs/OCR.md`](docs/OCR.md)。

## 5. 后台听课与课堂记录

课堂录音使用 `AudioRecord` + microphone ForegroundService。开始听课后可切到后台/锁屏继续。

当前服务把工作拆到独立执行通道：

- ASR 解码
- transcript / session 事件
- 问题处理
- 自动笔记
- PDF 页匹配

watchdog 只在 `AudioRecord` 异常时重建录音端，不会在课堂中途为了“恢复”而销毁仍存活的 VAD/解码链路，避免丢失已经缓冲的课堂音频。

每堂课保存独立 session；稳定 final transcript 持久化到数据库。停止听课时会先停止录音，再要求 ASR flush 最后一段。

## 6. 老师问题检测与 LLM

问题检测只消费稳定 final，不直接拿尚未稳定的 partial 去请求 LLM。

流程大致为：

```text
ASR final
  ↓
轻量问题候选判断
  ↓
问句评分 / 去重
  ↓
本地资料检索
  ↓
OpenAI-compatible LLM（可选）
  ↓
答案预览 / 课堂记录
```

当前 SenseVoice final 本身已经在 Silero VAD 检测到约 `1.8 s` 尾静音后才产生，因此问题检测不再额外叠加固定“思考停顿”。一旦 stable final 命中问题判定，就立即进入资料检索和回答；相较 `1.12.9` 去掉了额外 `1.2 s` 固定等待。误触发控制仍由 `QuestionDetector` 的问句评分、跨段上下文与重复问题抑制负责。

LLM 是可选层。没有配置 LLM 时，以下功能仍可正常使用：

- PDF 阅读与批注
- 本地 ASR
- 原始课堂转写
- OCR / 本地资料索引

API Key 通过应用的 SecretStore 保存；局域网无鉴权兼容接口也可以不填 Key。

## 7. 自动课堂笔记

课堂原始 transcript 与 AI 整理结果分开保存。自动笔记走低优先级通道，不阻塞语音采集和主 ASR 解码。

资料检索优先使用当前 PDF、相关 PDF 文本/便签、导入参考资料以及最近课堂记录，再交给可选 LLM 做回答或整理。

## 8. 学习通课程资料

代码包含独立的学习通课程与资料读取模块：

- 读取课程列表与课程资料
- 兼容多种附件 JSON 层级和 objectId 字段写法
- 兼容常见下载/预览地址字段
- 可将可访问的课程资料纳入 ClassHelper 课程/资料工作流
- 在线资源必要时交给系统浏览器处理

该模块保持 **只读**：不签到、不刷课、不提交作业、不修改课程数据。

## 9. 课堂派课程资料

课堂派模块支持：

- 课程列表
- 课程资源浏览
- 增量资源同步/缓存
- 本地文件预览
- Unicode 文件名
- 对明确限制下载的资源保持权限边界，不通过绕过权限的方式抓取文件

## 10. 资料库

资料库统一管理：

- PDF / 导入参考资料
- 课堂 session
- transcript / question / answer / note
- PDF 批注、便签、书签
- 分类、课程、主题、标签、备注、收藏/归档等元数据

AI 整理只写元数据建议，不自动移动、重命名或删除原文件。详见 [`docs/LIBRARY.md`](docs/LIBRARY.md)。

## 11. 隐私与边界

项目的 source validation 会阻止以下能力被意外重新引入：

- Flutter runtime
- Android WebView runtime
- 显式 `WAKE_LOCK`
- WorkManager 周期后台任务
- stable final 后重复添加固定问题等待窗口

语音在模型安装完成后本地识别。只有在你主动使用在线课程资料、下载模型、OCR 模型或配置 LLM 等网络功能时，相关网络请求才会发生。

详见 [`docs/PRIVACY.md`](docs/PRIVACY.md)。

## 12. 构建

本地构建环境：Android SDK 36 / Build Tools 36.0.0 / JDK 17。

```bash
chmod +x gradlew
./gradlew :app:fetchSherpaAar
python3 tools/validate_source.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Release：

```bash
./gradlew :app:assembleRelease
```

GitHub Actions 会执行源码回归检查、单元测试、Lint、Debug/Release 构建、APK 签名验证和 SHA-256 生成；主分支成功构建后按 `versionName` 自动创建 GitHub Release。

## 13. 关键目录

```text
app/src/main/java/io/github/paper/classhelper/
├── asr/        # SenseVoice、Silero VAD、本地模型管理
├── audio/      # AudioRecord
├── classroom/  # 前台听课服务、问题/笔记流水线
├── chaoxing/   # 学习通课程/资料读取
├── ketangpai/  # 课堂派课程/资源同步
├── knowledge/  # PDF/OCR/Office/文本索引与检索
├── library/    # 资料库整理
├── llm/        # OpenAI-compatible LLM
├── ocr/        # 高精度 OCR 模型
├── pdf/        # PDF 批注与相关能力
└── ui/         # Reader/Settings/Library/平台资料页面
```

更多实现细节：

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/LOCAL_ASR.md`](docs/LOCAL_ASR.md)
- [`docs/OCR.md`](docs/OCR.md)
- [`docs/LIBRARY.md`](docs/LIBRARY.md)
- [`docs/TEST_CHECKLIST.md`](docs/TEST_CHECKLIST.md)
