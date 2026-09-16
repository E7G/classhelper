## ClassHelper 1.12.10-question-fastpath

- 移除 stable ASR final 之后额外的 `1.2 s` 问题“思考停顿”窗口；当前 SenseVoice + Silero VAD 本身已经要求约 `1.8 s` 尾静音才输出 final，因此不再重复等待。
- 老师问题一旦形成 stable final，立即进入 `QuestionDetector`；命中后马上启动资料检索与可选 LLM 回答，正常路径相较上一版减少约 `1.2 s` 的固定等待。
- 保留 `QuestionDetector` 的问句评分、跨段上下文与重复问题抑制，不通过降低判定阈值来换速度。
- 增加源码回归检查，禁止重新引入 `QUESTION_THINK_PAUSE_MS`、`questionPauseJob`、`speechRevision` 这套二次等待状态。
- 版本提升到 `1.12.10-question-fastpath`。

## ClassHelper 1.12.9-no-live-subtitles

- 移除 Reader 中的实时字幕浮层以及 `LiveSubtitleController` 全局挂载。
- SenseVoice + Silero VAD 仍继续用于课堂语音转写；稳定 final 仍会进入课堂记录、问题检测、PDF 匹配与自动笔记，不影响现有课堂处理链路。
- 原因是当前主 ASR 为完整 VAD 语段结束后再进行离线解码，不产生真正实时的 partial；继续保留“实时字幕”会造成体验和语义上的误导。
- 版本提升到 `1.12.9-no-live-subtitles`，避免覆盖已发布的 `1.12.8` Release。

## ClassHelper 1.12.8-asr-low-latency

- 将 SenseVoice + Silero VAD 最短静音由 `2.0 s` 调至 `1.8 s`：本地样本对照中字符准确率约 `94.72%`（与原设置基本持平），final 最多提前约 200 ms。
- 其余长语段设置不变：阈值 `0.48`、最短语音 `0.2 s`、最长 `30 s`；仍使用中文提示和 ITN。
- 明确记录：该样本对照配套 VTT 尚未达到 95%，且 VTT 未经人工逐字校订；不把小幅参数波动宣传为准确率突破。
- 升级到 `1.12.8-asr-low-latency`，避免覆盖已发布标签；同步更新设置页、ASR/架构文档和回归检查。

## ClassHelper 1.12.7-asr-accuracy

- 将主课堂识别链路切换为 SenseVoiceSmall INT8 + Silero VAD，基于完整语段进行离线解码，减少流式短块上下文不足造成的漏识别和碎片化。
- 针对课堂讲解调优 VAD：阈值 `0.48`、最短静音 `2.0 s`、最短语音 `0.2 s`、单段最长 `30 s`；中文提示并启用 ITN。
- 保留连续录音、最多 20 秒模型初始化缓冲、串行 VAD/解码队列和停课 flush；模型下载继续支持断点续传、完整性校验与备用镜像。
- 在所提供的宋浩课程样本上以配套 VTT 作参考，字符准确率从旧 Zipformer 参数的 `84.91%` 提升至 `94.71%`（忽略空格与标点）。此为单条视频对字幕的对照，不是人工校订基准，未达到 95% 目标。
- 取舍：SenseVoice 只在 VAD 语段结束后给出 final，约需 2 秒尾静音；不提供实时 partial，也不使用 Zipformer 课程热词偏置。Settings 已说明此差异。
- 升级到 `1.12.7-asr-accuracy`，避免复用已发布版本标签。
- README、ASR/架构文档和测试清单同步到当前实现。

## ClassHelper 1.12.6-asr-stability

- 修复 Zipformer endpoint 生命周期问题：native endpoint 一旦成立就完成当前段并 `reset` stream，不再继续复用已经 endpointed 的识别流，避免后续句子粘连、长时间不再出 final 等问题。
- 新增 `StreamingTranscriptState` 管理单段 partial/final：重复 partial 不重复发布；endpoint 的 native final 为空时回退到本段最后有效 partial；提交后立即清空，避免上一段文字泄漏到下一段。
- 补充 JVM 单元测试，覆盖短句 endpoint、final 覆盖 partial、重复 partial 抑制和跨段不串文本。
- 保留课堂完整度优先的 Zipformer 参数：有声尾静音约 1.60 秒、无有效语音约 3.0 秒、单段最长 30 秒、modified beam search / 4 active paths。
- 修复 GitHub Actions Release 阻塞：`android-actions/setup-android` 默认会请求 Google 已移除的旧 SDK `tools` 包，导致新 runner 在 Gradle 启动前直接失败；现在只由 setup action 准备 `platform-tools`，SDK 36 / Build Tools 36.0.0 继续显式安装。
- 版本提升到 `1.12.6-asr-stability`，避免覆盖已经发布且指向旧提交的 `v1.12.5-chaoxing-asr` 标签。
- README 按当前代码重写：主 ASR 已明确为 Streaming Zipformer INT8，补充 endpoint/reset、模型下载、后台课堂服务、问题流水线、学习通/课堂派和构建说明。
- 同步更新 `docs/LOCAL_ASR.md`、`docs/ARCHITECTURE.md`、`docs/TEST_CHECKLIST.md`，移除已经过时的 SenseVoice + Silero VAD 主链路描述。
- 学习通继续保持只读；课堂派继续遵守资源权限标志，不增加签到、刷课、提交作业或绕过禁止下载限制的行为。
