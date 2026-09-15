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
