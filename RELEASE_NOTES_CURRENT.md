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
