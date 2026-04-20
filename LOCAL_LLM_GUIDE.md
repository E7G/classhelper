# 本地 LLM 使用指南

## 概述

本项目现在支持使用 llama.cpp 直接运行本地 GGUF 模型，无需依赖外部的 Ollama 服务。这种方式更加轻量、快速，适合完全离线使用。

## 特点

✅ **完全离线运行** - 无需网络连接
✅ **无需外部服务** - 不需要安装 Ollama
✅ **低内存占用** - 使用量化模型，内存占用小
✅ **快速启动** - 直接加载模型，启动速度快
✅ **跨平台支持** - 支持 Windows、Linux、macOS
✅ **内置模型** - Qwen3.5-0.8B 已内置，无需下载

## 快速开始

### 内置模型 (推荐)

应用已内置 **Qwen3.5-0.8B-Q4_K_M** 模型，开箱即用！

**模型信息:**
- 名称: Qwen3.5-0.8B-Q4_K_M.gguf
- 大小: 532 MB
- 量化: Q4_K_M (平衡质量和大小)
- 语言: 中文 + 英文
- 来源: [ModelScope](https://www.modelscope.cn/models/unsloth/Qwen3.5-0.8B-GGUF/files)

**使用方法:**
1. 打开应用
2. 进入"设置"
3. 选择"本地模型 (推荐)"
4. 模型文件名已自动填充: `Qwen3.5-0.8B-Q4_K_M.gguf`
5. 点击"保存配置"
6. 开始使用！

### 更换模型

如需使用其他模型，可以下载 GGUF 格式的模型文件：

**下载地址:**
- ModelScope (国内推荐): https://www.modelscope.cn/models/unsloth/Qwen3.5-0.8B-GGUF/files
- Hugging Face: https://huggingface.co/models?search=qwen+gguf

**放置位置:**
```
Windows: C:\Users\<用户名>\Documents\llm_models\
Linux/macOS: ~/Documents/llm_models/
```

或直接替换 `assets/models/Qwen3.5-0.8B-Q4_K_M.gguf` 文件。

## 模型推荐

### 按用途推荐

**轻量级（适合低配置设备，4GB RAM）:**
- Qwen2.5 0.5B - 398 MB
- Phi-3 Mini 3.8B - 2.3 GB

**中等配置（适合中等配置设备，8GB RAM）:**
- Qwen2.5 1.5B - 986 MB
- Qwen2 7B - 4.7 GB

**高配置（适合高配置设备，16GB+ RAM）:**
- Qwen2.5 7B - 4.7 GB
- Llama3 8B - 4.7 GB

### 按语言推荐

**中文场景:**
- Qwen2.5 系列（推荐）
- Yi 系列

**英文场景:**
- Llama3 系列
- Phi-3 系列
- Mistral 系列

## 性能优化

### 量化等级选择

| 量化等级 | 大小 | 质量 | 速度 | 推荐场景 |
|---------|------|------|------|---------|
| Q4_K_M | 小 | 良好 | 快 | 推荐，平衡性能 |
| Q5_K_M | 中 | 很好 | 中 | 追求质量 |
| Q8_0 | 大 | 优秀 | 慢 | 追求最佳质量 |
| Q2_K | 最小 | 一般 | 最快 | 极低配置设备 |

### 上下文长度

模型默认上下文长度为 4096 tokens。如果需要更长的上下文，可以在代码中修改：

```dart
// 在 local_llm_service.dart 中
contextParams.context = 8192;  // 增加到 8192
contextParams.batch = 1024;     // 相应增加 batch
```

注意：更长的上下文需要更多内存。

### 多线程优化

默认使用单线程。如果 CPU 核心较多，可以增加线程数：

```dart
// 在 local_llm_service.dart 中
final modelParams = ModelParams();
modelParams.nThreads = 4;  // 使用 4 个线程
```

## 常见问题

### 1. 模型加载失败

**原因:**
- 模型文件路径不正确
- 模型文件损坏
- 内存不足

**解决方法:**
- 检查模型文件是否存在于正确路径
- 重新下载模型文件
- 使用更小的量化模型

### 2. 推理速度慢

**原因:**
- CPU 性能不足
- 模型太大
- 量化等级太高

**解决方法:**
- 使用更小的模型（如 0.5B 而不是 7B）
- 使用更低量化等级（如 Q4_K_M 而不是 Q8_0）
- 增加线程数（如果 CPU 核心多）

### 3. 内存不足

**原因:**
- 模型太大
- 系统内存不足

**解决方法:**
- 使用更小的模型
- 使用更低量化等级
- 关闭其他应用程序

### 4. 回答质量不佳

**原因:**
- 模型太小
- 量化等级太低
- 提示词不够清晰

**解决方法:**
- 使用更大的模型（如 1.5B 或 7B）
- 使用更高量化等级（如 Q5_K_M 或 Q8_0）
- 优化系统提示词

## 高级配置

### 自定义系统提示词

在 `llm_service.dart` 中修改：

```dart
String _getDefaultSystemPrompt() {
  return '''你是一个专业的课堂助手。
请用简洁、清晰的语言回答问题。
提供相关的例子和解释。
答案应该适合课堂场景。''';
}
```

### 调整生成参数

在 `local_llm_service.dart` 中：

```dart
Future<String> generate(
  String prompt, {
  String? systemPrompt,
  int maxTokens = 512,      // 最大生成 token 数
  double temperature = 0.7,  // 温度（0.1-1.0，越高越随机）
}) async {
  // ...
}
```

**参数说明:**
- `maxTokens`: 生成的最大长度，增加可获得更长回答
- `temperature`: 控制随机性
  - 0.1-0.3: 更确定，适合事实性问题
  - 0.5-0.7: 平衡，适合一般对话
  - 0.8-1.0: 更随机，适合创意生成

### GPU 加速（实验性）

llama.cpp 支持 GPU 加速，但需要额外配置：

1. 安装 CUDA 或 Metal 支持
2. 在代码中启用 GPU layers：

```dart
final modelParams = ModelParams();
modelParams.nGpuLayers = 35;  // 将 35 层放到 GPU
```

注意：GPU 加速需要相应的硬件和驱动支持。

## 模型下载资源

### 官方模型库

**Hugging Face:**
- Qwen: https://huggingface.co/Qwen
- Llama: https://huggingface.co/meta-llama
- Mistral: https://huggingface.co/mistralai

**ModelScope（国内镜像）:**
- Qwen: https://modelscope.cn/models/Qwen
- 其他模型也有对应镜像

### 下载工具

**命令行下载（推荐）:**
```bash
# 使用 huggingface-cli
pip install huggingface-hub
huggingface-cli download Qwen/Qwen2.5-0.5B-Instruct-GGUF qwen2.5-0.5b-instruct-q4_k_m.gguf --local-dir .

# 使用 wget
wget https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf
```

**浏览器下载:**
直接访问模型页面，点击文件下载即可。

## 与 Ollama 对比

| 特性 | 本地 llama.cpp | Ollama |
|------|---------------|--------|
| 安装复杂度 | 简单（仅下载模型） | 中等（需安装服务） |
| 启动速度 | 快（直接加载） | 中等（需启动服务） |
| 内存占用 | 低 | 中 |
| 模型管理 | 手动管理文件 | 自动管理 |
| 多模型切换 | 需重新加载 | 快速切换 |
| 适用场景 | 单一模型，长期使用 | 多模型，频繁切换 |

## 相关链接

- [llama.cpp GitHub](https://github.com/ggerganov/llama.cpp)
- [llama_cpp_dart Package](https://pub.dev/packages/llama_cpp_dart)
- [Qwen Model](https://github.com/QwenLM/Qwen)
- [GGUF Format](https://github.com/ggerganov/ggml/blob/master/docs/gguf.md)
