# 智能课堂助手 - Android平板版

基于 Flutter + FunASR + LLM 的智能课堂助手应用，专为Android平板优化。

## 🎯 核心特性

### 高度集成
- ✅ **本地语音识别**: 使用 speech_to_text，无需外部服务
- ✅ **本地LLM**: 支持 Ollama 本地模型，完全离线运行
- ✅ **一键部署**: 内置模型管理，简化安装流程
- ✅ **平板优化**: 响应式布局，适配各种屏幕尺寸

### 主要功能
- **实时语音识别**: 本地/远程ASR双模式
- **智能问题检测**: 自动检测课堂提问
- **答案生成**: 本地LLM实时生成答案
- **笔记管理**: 自动记录+手动笔记
- **模型管理**: 内置模型下载和管理

## 📱 Android平板部署

### 方式一：快速开始（推荐）

1. **安装依赖**
   ```bash
   flutter pub get
   ```

2. **连接设备**
   ```bash
   # 确保USB调试已开启
   flutter devices
   ```

3. **运行应用**
   ```bash
   flutter run
   ```

4. **构建APK**
   ```bash
   flutter build apk --release
   ```

### 方式二：完整部署（离线使用）

#### 1. 安装 Ollama (Android)

由于Android限制，推荐以下方案：

**方案A：使用Termux**
```bash
# 安装Termux
# 下载: https://termux.com/

# 在Termux中安装Ollama
pkg install ollama

# 下载模型
ollama pull qwen2:7b

# 启动服务
ollama serve
```

**方案B：使用云端API**
- 在设置页面配置OpenAI或其他API
- 需要网络连接

#### 2. 配置应用

1. 打开应用，进入"模型管理"
2. 选择"使用本地模型"
3. 配置Ollama地址（默认 localhost:11434）
4. 测试连接

### 方式三：使用云端服务

如果设备性能不足，可以使用云端服务：

1. **OpenAI API**
   - 获取API Key
   - 在设置页面配置
   - 需要稳定网络

2. **其他兼容API**
   - 支持任何OpenAI兼容的API
   - 在设置页面自定义配置

## 🔧 技术架构

### 本地ASR
```
speech_to_text (Android内置)
    ↓
实时识别结果
    ↓
问题检测器
    ↓
本地LLM生成答案
```

### 支持的ASR模式
| 模式 | 优点 | 缺点 | 推荐场景 |
|------|------|------|----------|
| 本地ASR | 无需网络，速度快 | 需要下载语言包 | 离线使用 |
| 远程FunASR | 准确率高 | 需要服务器 | 高精度需求 |

### 支持的LLM模式
| 模式 | 优点 | 缺点 | 推荐场景 |
|------|------|------|----------|
| Ollama本地 | 完全离线，隐私保护 | 需要较高性能 | 隐私优先 |
| OpenAI API | 效果最好 | 需要网络和费用 | 追求质量 |
| 自定义API | 灵活配置 | 需要自行部署 | 企业部署 |

## 📋 系统要求

### Android设备
- **最低版本**: Android 5.0 (API 21)
- **推荐版本**: Android 10+ (API 29+)
- **内存**: 至少 4GB RAM
- **存储**: 至少 8GB 可用空间（用于模型）

### 推荐设备配置
- **CPU**: 8核处理器
- **RAM**: 6GB+
- **存储**: 16GB+
- **屏幕**: 8英寸+ 平板

## 🚀 性能优化

### 1. 模型选择建议

| 设备配置 | 推荐模型 | 预期性能 |
|----------|----------|----------|
| 高端 (8GB+ RAM) | qwen2:7b | 流畅 |
| 中端 (6GB RAM) | mistral:7b | 良好 |
| 低端 (4GB RAM) | gemma:7b | 可用 |

### 2. 内存优化
- 及时清理识别历史
- 定期删除不需要的问题
- 关闭不使用的功能

### 3. 电池优化
- 使用本地模型时注意发热
- 长时间使用建议连接电源
- 适当降低识别频率

## 📖 使用指南

### 快速开始
1. **启动应用** → 自动初始化本地ASR
2. **配置模型** → 模型管理页面选择模型
3. **开始录音** → 点击麦克风按钮
4. **查看结果** → 实时识别+自动问答

### 高级功能
- **手动标记问题**: 点击识别结果的问号图标
- **重新生成答案**: 点击问题的刷新按钮
- **导出笔记**: 笔记页面支持导出

## 🔒 隐私与安全

### 本地模式
- 所有数据存储在本地
- 语音识别在设备端完成
- LLM推理在本地运行
- 完全离线可用

### 云端模式
- API Key本地加密存储
- 仅发送必要的文本数据
- 不存储对话历史到云端

## 🐛 常见问题

### Q: 本地ASR无法使用？
A: 
1. 检查麦克风权限
2. 下载对应语言包
3. 重启应用

### Q: Ollama连接失败？
A: 
1. 确认Termux中Ollama正在运行
2. 检查端口配置（默认11434）
3. 确认模型已下载

### Q: 应用卡顿？
A: 
1. 使用更小的模型
2. 清理历史数据
3. 关闭后台应用

### Q: 如何完全离线使用？
A: 
1. 使用本地ASR模式
2. 安装Ollama并下载模型
3. 无需网络即可使用

## 📦 项目结构

```
classhelper/
├── android/              # Android平台配置
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml
│   │   │   └── res/xml/file_paths.xml
│   │   └── build.gradle
│   ├── settings.gradle
│   └── gradle.properties
├── lib/
│   ├── main.dart
│   ├── services/
│   │   ├── local_asr_service.dart      # 本地ASR
│   │   ├── unified_asr_service.dart    # 统一ASR管理
│   │   ├── mobile_llm_service.dart     # 移动端LLM
│   │   └── llm_service.dart            # LLM服务
│   ├── screens/
│   │   ├── home_screen.dart            # 响应式主页面
│   │   ├── model_management_screen.dart # 模型管理
│   │   └── ...
│   └── ...
└── pubspec.yaml
```

## 🎨 UI特性

### 响应式布局
- **手机**: Tab切换视图
- **平板**: 双栏并排视图
- **自适应**: 根据屏幕宽度自动调整

### Material Design 3
- 现代化设计语言
- 流畅的动画效果
- 深色模式支持

## 📄 许可证

MIT License

## 🤝 贡献

欢迎提交 Issue 和 Pull Request!

## 🙏 致谢

- [speech_to_text](https://pub.dev/packages/speech_to_text) - Flutter语音识别
- [Ollama](https://ollama.ai/) - 本地LLM运行环境
- [FunASR](https://github.com/alibaba-damo-academy/FunASR) - 阿里巴巴语音识别
- [Flutter](https://flutter.dev/) - Google UI框架
