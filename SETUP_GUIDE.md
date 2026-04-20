# ASR 和 LLM 配置指南

## ASR (语音识别) 配置

### 本地模式 (推荐)

使用本地 Sherpa-onnx Paraformer 模型，无需网络连接。

**特点:**
- ✅ 完全离线运行
- ✅ 隐私保护，数据不上传
- ✅ 低延迟，实时识别
- ✅ 支持中英文双语

**使用方法:**
1. 在设置中选择"本地模型"
2. 点击"连接"按钮
3. 允许麦克风权限
4. 开始录音

### 远程 API 模式

使用外部 ASR API 服务。

**配置步骤:**
1. 在设置中选择"远程API"
2. 输入 API 地址 (例如: `https://api.example.com/asr`)
3. 输入 API Key (如果需要)
4. 点击"连接"按钮

**API 要求:**
- 端点: `/transcribe`
- 方法: POST
- 请求: 音频数据 (PCM 16kHz, mono)
- 响应格式:
  ```json
  {
    "text": "识别的文本",
    "is_final": false,
    "confidence": 0.95
  }
  ```

## LLM (大语言模型) 配置

### Ollama 本地模型 (推荐)

使用 Ollama 运行本地 LLM，无需 API Key。

#### 推荐模型

1. **Qwen2.5 0.5B (推荐内置)** - 398 MB
   - 超轻量级，适合低配置设备
   - 中文能力强
   - 默认内置模型

2. **Qwen2.5 1.5B** - 986 MB
   - 轻量级，平衡性能与速度
   - 中文能力强

3. **Qwen2 7B** - 4.7 GB
   - 中文能力强，适合中文课堂

#### 安装步骤

1. **安装 Ollama**
   ```bash
   # Windows
   # 访问 https://ollama.ai/download 下载安装
   
   # 或使用命令行
   winget install Ollama.Ollama
   ```

2. **下载模型**
   ```bash
   # 下载推荐模型 (398 MB)
   ollama pull qwen2.5:0.5b
   
   # 或下载其他模型
   ollama pull qwen2.5:1.5b
   ollama pull qwen2:7b
   ```

3. **启动 Ollama 服务**
   ```bash
   ollama serve
   ```

4. **配置应用**
   - 打开设置
   - 选择 "Ollama (本地)"
   - 主机地址: `localhost`
   - 端口: `11434`
   - 模型名称: `qwen2.5:0.5b`
   - 点击"测试连接"
   - 保存配置

### OpenAI API

使用 OpenAI 的 GPT 模型。

**配置步骤:**
1. 获取 OpenAI API Key
2. 在设置中选择 "OpenAI"
3. 输入 API Key
4. 选择模型 (gpt-4, gpt-3.5-turbo 等)
5. 保存配置

### 自定义 API

使用其他兼容 OpenAI 格式的 API。

**配置步骤:**
1. 在设置中选择 "自定义"
2. 输入 API 地址
3. 输入 API Key (如果需要)
4. 输入模型名称
5. 保存配置

## 使用流程

### 完整工作流程

1. **启动服务**
   - 启动 Ollama: `ollama serve`
   - 确保模型已下载: `ollama list`

2. **配置应用**
   - 打开设置
   - 配置 ASR (本地/远程)
   - 配置 LLM (Ollama/OpenAI/自定义)
   - 测试连接

3. **开始使用**
   - 返回主界面
   - 点击"连接"按钮
   - 点击"开始"按钮录音
   - 说话，系统自动识别
   - 系统自动检测问题并生成答案

### 性能优化建议

**低配置设备 (4GB RAM):**
- ASR: 本地模式
- LLM: Qwen2.5 0.5B

**中等配置设备 (8GB RAM):**
- ASR: 本地模式
- LLM: Qwen2.5 1.5B 或 Qwen2 7B

**高配置设备 (16GB+ RAM):**
- ASR: 本地模式
- LLM: Qwen2 7B 或更大的模型

## 故障排除

### ASR 问题

**问题: 无法连接本地 ASR**
- 检查模型文件是否已下载
- 运行 `download_model.bat` 下载模型
- 检查麦克风权限

**问题: 远程 ASR 连接失败**
- 检查 API 地址是否正确
- 检查 API Key 是否有效
- 检查网络连接

### LLM 问题

**问题: Ollama 连接失败**
- 确认 Ollama 服务正在运行: `ollama serve`
- 检查端口 11434 是否被占用
- 尝试重启 Ollama 服务

**问题: 模型未找到**
- 下载模型: `ollama pull qwen2.5:0.5b`
- 查看已安装模型: `ollama list`

**问题: 内存不足**
- 使用更小的模型
- 关闭其他应用程序
- 增加系统虚拟内存

## 高级配置

### 自定义 ASR API

如果您有自己的 ASR 服务，可以实现以下接口:

```python
from fastapi import FastAPI, Request
import numpy as np

app = FastAPI()

@app.post("/transcribe")
async def transcribe(request: Request):
    # 读取音频数据
    audio_data = await request.body()
    
    # 转换为 numpy 数组
    samples = np.frombuffer(audio_data, dtype=np.int16)
    
    # 进行语音识别
    text = your_asr_model.transcribe(samples)
    
    return {
        "text": text,
        "is_final": True,
        "confidence": 0.95
    }

@app.get("/health")
async def health():
    return {"status": "ok"}
```

### 自定义 LLM 提示词

在代码中修改系统提示词:

```dart
final systemPrompt = '''
你是一个智能课堂助手，专门帮助学生理解和解答课堂问题。
请用简洁、清晰的语言回答问题，并提供相关的例子和解释。
''';
```

## 相关链接

- [Ollama 官网](https://ollama.ai)
- [Sherpa-onnx 文档](https://github.com/k2-fsa/sherpa-onnx)
- [Qwen 模型](https://github.com/QwenLM/Qwen)
