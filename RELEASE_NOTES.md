# v1.7.9-sensevoice

- 重写批注持久化事务：新增笔迹与擦除统一使用标准 `PDDocument.save(temp)`，移除不可靠的 selected-object incremental save 与“完整保存 + 二次重开校验”分叉。
- 每个受影响页面先复制 `/Annots` 为独立可变列表，按 journal 顺序应用新增/删除，再显式写回 `page.annotations`，避免间接 COS Array 的 remove 行为差异。
- journal 仅在标准保存、PDF 头检查和工作副本替换全部成功后才清除；失败时保留原工作 PDF 与 journal，可安全重试。
- 工作副本替换优先使用 Android 8+ `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`，失败时降级，不再先删除原 PDF。

# v1.7.8-sensevoice — annotation toolbar content-width fix

- Annotation toolbar now measures to its actual button content instead of filling the entire screen width.
- Removed `fillViewport=true`, which was stretching the scroll viewport and leaving a large empty area.
- Toolbar remains centered; on narrow screens it is constrained by the parent and scrolls horizontally only when necessary.
- Annotation tool labels remain single-line with no wrapping.
- No drawing, eraser, PDF persistence, or touch-routing behavior changed in this UI-only revision.

# v1.7.7-sensevoice — compile compatibility fixes

- Resolve duplicate `libonnxruntime.so` from sherpa-onnx and onnxruntime-android with `jniLibs.pickFirsts`.
- Add local `attrs.xml` declarations for Material button inset attributes.
- Add the missing `ShapeAppearanceOverlay.ClassHelper` base style and explicit child parents.
- Use AppCompat's `colorPrimary` / `colorError` attrs in Kotlin under non-transitive R classes.
- Strengthen PDFViewer/PDFBox-Android R8 keep/dontwarn rules.
- Preserve v1.7.6 eraser persistence and annotation toolbar fixes.

## v1.7.6-sensevoice

- 修复橡皮擦预览正常但点击“完成批注”后被擦笔画重新出现：删除批注时改用完整 PDF 保存，并在清除删除日志前重新打开输出文件核验 annotation ID 已真正消失。
- 新增/普通批注仍使用增量保存，避免整体性能退化。
- 批注工具栏改为接近屏幕宽度的响应式浮动栏，按钮加宽，所有工具文字强制单行；窄屏时横向滚动。

# 1.7.5-sensevoice

- 重做 PDF 批注工具条宽度：外层由全宽改为内容宽度，画笔/橡皮/荧光笔/撤销/重做/便签使用紧凑按钮，移除 MaterialButton 默认最小宽度造成的超长横条。
- 当前画笔/橡皮/荧光笔增加明确选中态，切到橡皮后按钮本身会高亮，不再只依赖底部文字提示。
- 修复橡皮擦“后台删了但屏幕看起来没变化”：连续擦除合并写入后，保留当前页、缩放和平移位置受控刷新 PDFView，已写入 PDF 的旧批注会立即从画面消失。
- 修复批注坐标在进入编辑后由临时几何切换到真实 CropBox 导致橡皮命中坐标系变化；现在优先使用预热的真实 CropBox/旋转，缓存未就绪时短暂锁定落笔，避免生成之后擦不掉的笔迹。
- 橡皮命中增加小范围容错，并支持 PDF Ink annotation 的多段 inkList，改善非标准 CropBox/旋转 PDF 上的擦除可靠性。
- 长按橡皮清空当前页后同样保留当前视口刷新，结果立即可见。
- 保留 v1.7.4 的 SizeF 编译绕过和此前 ONNX/资源兼容修复。

# 1.7.4-sensevoice

- 修复 Ahmer PDFViewer `SizeF` 类型不可访问导致的 Kotlin 编译阻塞。
- ReaderActivity 不再调用 `PDFView/PdfFile.getPageSize()`；适宽改用官方 `fitToWidth()`，批注视口改用纯 Float API 计算。
- 保持批注页面矩形计算与 Ahmer PDFViewer 内部垂直布局公式一致。
- 移除旧的 `enableDoubletap` 调用，避免不同 Ahmer PDFViewer API 版本产生编译错误。

# v1.7.3-sensevoice

- 修复批注模式完全无法落笔：批注不再等待 PDFBox 几何解析，立即使用当前 PDFView 页面几何开始书写，后台再精确校正 CropBox/旋转。
- 删除错误的 `PDFView.setOnTouchListener` 外部拦截器，恢复 AhmerPdfViewer 原生缩放/拖动手势。
- 完成批注时立即移除批注触摸层，PDF 无需等待后台保存即可马上恢复缩放和平移。
- 橡皮按钮在批注工具栏中强制可见；橡皮拖动继续显示圆形擦头与轨迹，长按清空当前页。
- 擦除/全清过程中不再 recycle/reload PDFView，避免第一次擦除后批注手势失效；统一在完成批注后刷新 PDF。
- 保留 v1.7.2 OCR 设置页 MD3E 配色重设计及 v1.7.0 PP-OCRv6 / LLM API 测试功能。

# v1.7.2-sensevoice

- 重新设计设置页“高精度 PDF OCR”区域：移除整块 tertiary/pink 强色背景，统一为 MD3E 中性 Surface 容器 + Primary 小面积强调。
- OCR 模型状态、下载进度、高精度模式、说明和模型管理重新分层，信息层级更清晰。
- 高精度模式由复选框改为 Material 3 Switch；删除模型改为低干扰的 error-color text action。
- OCR 进度条明确使用主题 Primary / Surface 色阶，完整适配 Dynamic Colors。

# v1.7.1-sensevoice — 批注触摸锁定修复

- 修复进入批注后一落笔 PDF 就跟着移动：批注模式下单指触摸由 InkOverlayView 独占，底层 PDFView 不再直接接收触摸。
- 修复当前页 CropBox/旋转信息预热期间批注层被临时 disabled 导致触摸穿透；现在批注层保持启用并锁定单指，几何准备完成后直接开始书写。
- 修复笔尖落在页面边缘/页外时 `return false` 导致整次手势转交 PDFView 的问题；批注层可见时始终消费手势。
- 批注模式交互明确为：单指=画笔/荧光笔/橡皮；双指=平移/缩放。第二根手指落下会取消未完成的单指笔画，避免缩放时误画。
- 退出批注后恢复 PDFView 原有单指翻页/拖动行为。

# v1.7.0-sensevoice

- Added optional PP-OCRv6 Small / ONNX Runtime high-accuracy OCR for scanned PDFs.
- Added SHA-256 verified OCR model download, resume, delete and model status UI.
- PDF text layer remains first choice; PP-OCRv6 is used only for scan-like pages during automatic indexing.
- Explicit OCR/re-index now OCRs all pages. ML Kit remains an automatic fallback.
- Added normal/high-accuracy OCR render profiles with a 12 MP memory guard.
- Added a real LLM API availability test using the current unsaved Base URL, API key and model fields.
- LLM test reports latency/model/preview and actionable HTTP/auth/path/rate-limit errors.
- Storage manager now reports the OCR model separately.

# v1.6.9-sensevoice — 即时批注 / 苹果式橡皮 / 整页缩放 / MD3E 弹窗

- 批注模式不再为了绘制重新适配或跳转 PDF；直接沿用进入批注前的当前缩放、平移和页码，批注层按 PDFView 实际页面矩形实时换算坐标。
- 单指写字/荧光，双指在批注状态下直接缩放和平移；未完成的单指笔画在第二根手指落下时取消，避免捏合缩放产生误点。
- 新笔画使用增量二次曲线路径，移动过程中不再反复重建整条 Path；停笔后的 SQLite journal/history 写入改为后台 write-behind，不阻塞触摸线程。
- 新增真正可见的圆形橡皮擦头与扫掠轨迹；快速拖动按整段轨迹与笔迹线段距离做命中，不再按矩形包围框粗略判断。
- 新写笔迹被擦中时当前帧直接消失；连续橡皮手势 180ms 合并一次 PDF 提交/刷新，减少连续擦除时反复重载。
- 长按“橡皮”继续支持一键清空当前页 ClassHelper 批注。
- 新绘制的预览笔迹改为 PDF 坐标保存，双指缩放/移动后会随页面同步变换，不再漂移。
- PDF 最小缩放明确为 1× + `FitPolicy.BOTH`，可缩回完整一页；“更多”新增“整页”按钮，长按页码按钮也可一键回到完整页视图。
- 删除阅读页“更多 → 资料库”入口，仅保留“设置 → 资料库与存储”。
- 所有应用内列表、确认、输入、模型管理、崩溃日志等弹窗统一经过 MD3E 自定义内容层，不再使用 AlertDialog 默认 `setItems` / `setMessage` 老式排版。
- 资料库的 AI 批量整理、删除记录、编辑标注、删除模型等确认/编辑弹窗同步改为 MD3E；危险操作使用错误色强调。
- 系统权限申请与 Android 系统文件选择器仍由系统绘制，不做伪装替换。

# v1.6.8-sensevoice — 资料库与存储管理

- 新增独立“资料库”页面；当前版本统一从“设置 → 资料库与存储”进入，避免阅读页“更多”菜单臃肿。
- 统一展示 PDF 工作副本、导入参考资料、课堂会话，以及转写/问答/笔记/批注/书签数量和关联关系。
- 新增细粒度资料元数据：一级分类、二级分类、课程/学科、主题、标签、备注、状态、收藏、整理来源、AI 置信度、分类依据和最后更新时间。
- 一级分类：课程资料 / 参考资料 / 课堂记录 / 作业与复习 / 研究资料 / 个人整理 / 其他；二级分类提供课堂 PDF、课件 PPT、教材、讲义、论文、报告、政策规范、习题、错题、课堂总结等细分并允许自定义。
- 支持逐项手动编辑分类；支持收藏、归档/恢复、搜索，以及“全部 / 文档资料 / 课堂记录 / 已收藏 / 已归档”过滤。
- 支持逐项 AI 整理和批量整理未分类内容。配置可用 LLM 时，仅发送标题和有限正文/课堂记录摘样用于分类；未配置 AI 时自动退化为不联网的本地规则初分。
- AI 只写分类元数据，不移动、不改名、不删除原文件；手动编辑可随时覆盖 AI 结果。
- 新增存储概览：PDF 工作副本、SenseVoice 模型、数据库/全文索引、临时缓存分别统计空间占用；转写、问答、笔记、批注、书签分别计数。
- 文档“移出资料库”只删除 ClassHelper 索引、批注记录和应用私有 PDF 工作副本，不删除系统文件中的原 PDF/原参考资料。
- 课堂记录删除会先显示将删除的转写/问答/笔记数量；进行中的听课会话禁止误删。
- 数据库升级至 v7，新增 `library_meta` 表；旧资料无需迁移原文件，升级后自动显示为“未整理”。

# v1.6.6-sensevoice

- 全部应用内 AlertDialog 切换为 MaterialAlertDialogBuilder，统一使用 MD3E 动态配色与圆角。
- 弹窗圆角提升到 32dp，增强 Material 3 Expressive 的卡片感，同时保持墨水屏清晰边界。
- PDF 便签、PDF 搜索、跳转页码改为 Material TextInputLayout/TextInputEditText，修复旧原生输入框与 MD3E 外壳风格割裂。
- 保留 v1.6.5 的 SenseVoice、页码、听课停止、自动隐藏与按钮宽度修复。
- 系统权限弹窗与系统文件选择器仍由 Android 系统控制，应用无法替换其视觉样式。

# v1.6.4-sensevoice

- 修复“结束听课”点击后看起来无反应：点击后立即进入“正在结束…”状态并禁用按钮，先停麦克风，再做最后一句识别和笔记收尾。
- 给 ASR finish 增加 6 秒超时兜底；即使底层遗漏 finish 回调，也不会让前台服务和按钮永久卡住。
- 页码跳转按钮直接显示“当前页 / 总页数”（例如 `12 / 86`），打开、翻页、搜索/书签跳转后实时更新。
- 优化阅读工具栏自动隐藏：常规自动隐藏由 2.4 秒延长到 6 秒；轻微滚动不再瞬间隐藏，手指离开后再延迟 2.8 秒收起。
- 操作顶栏/底栏时暂停倒计时，松手后重新计时，避免连续操作时工具栏突然消失。

# v1.6.3-sensevoice

- 修复设置页左上角浮动提示与输入内容重叠：课程术语输入框改为单一 Hint，并增加稳定的顶部/左右内边距。
- Base URL、API Key、模型名改用 TextInputLayout placeholder，消除外层标签与 EditText Hint 双重绘制，在大字体和显示缩放下也不会叠字。

# v1.6.2-sensevoice

- 修复 SenseVoice `tokens.txt` 下载后始终被误判为损坏的问题：词表中的 `<...>` 特殊 token 现在被正确接受。
- 固定校验官方 2024-07-17 `tokens.txt` 的精确大小（315,894 bytes）和 SHA-256，避免 HTML/错误页伪装成词表。
- 固定主模型精确大小，并改进 HTTP 416 断点失效处理；已完成的 `.part` 词表可直接恢复，不必重下 239 MB 主模型。

# ClassHelper Native v1.6.1 — SenseVoiceSmall

## ASR model switch

- Replaced Qwen3-ASR 0.6B with **SenseVoiceSmall INT8 (2024-07-17)**.
- Model payload drops from about 1.0 GB to about **230 MB** including `tokens.txt` and Silero VAD.
- Uses `OfflineSenseVoiceModelConfig(language="auto", useInverseTextNormalization=true)` through sherpa-onnx v1.13.5.
- Supports Mandarin, Cantonese, English, Japanese and Korean.
- Uses Silero VAD to turn the offline model into low-latency classroom utterance recognition.
- Keeps AudioRecord running continuously and buffers up to 20 seconds while the model initializes.
- Qwen3 model directories are treated as obsolete and cleaned during migration to avoid wasting about 1 GB of storage.

## Why the 2024-07-17 INT8 checkpoint

The later 2025-09-09 sherpa SenseVoice checkpoint is primarily a Cantonese fine-tune and does not support punctuation. The 2024-07-17 checkpoint supports ITN/punctuation and is a better default for Mandarin classroom question detection and note generation.

## Unchanged

- Material 3 Expressive UI
- immersive PDF reader / toolbar wake handle
- PDF annotations saved back to PDF
- unified classroom sidebar
- background model downloader and resume logic
- LLM API answer / notes pipeline
- build baseline: AGP 8.9.1, compileSdk 36, Kotlin 2.2.0

## v1.6.7-sensevoice

- Fixed eraser responsiveness: annotation hit testing is cached per page instead of reopening the whole PDF for every drag sample.
- Eraser strokes are queued in memory/SQLite and committed once at gesture end; newly drawn preview strokes disappear immediately.
- Long-press **橡皮** now clears all ClassHelper annotations on the current page in one batch transaction.
- Annotation journal/history writes now share a single SQLite transaction, reducing flash fsync pressure while writing.
- Repeated dirty-state writes are suppressed once a document is already dirty.
- PDF commit prefers PDFBox incremental save for changed page/annotation dictionaries, with automatic fallback to the previous full rewrite path if incremental save throws.
- Removed duplicate annotation-history writes during PDF flush and reused page objects while applying a batch.
- PDF reading explicitly enables pinch/double-tap zoom with 1× / 2× / 4× levels.
