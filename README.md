# 智能课堂助手

[![Flutter](https://img.shields.io/badge/Flutter-3.0+-02569B?logo=flutter)](https://flutter.dev)
[![Dart](https://img.shields.io/badge/Dart-3.0+-0175C2?logo=dart)](https://dart.dev)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Android-lightgrey)](https://github.com)

一款基于 Flutter + FunASR + LLM 的智能课堂助手应用，支持实时语音识别、智能问答和笔记管理。

## ✨ 功能特性

### 🎤 实时语音识别
- 基于 FunASR/Sherpa-onnx 的高精度中文语音识别
- 支持本地离线识别，保护隐私
- 实时显示识别结果

### 🤖 智能问答
- 自动检测课堂中的提问
- 支持多种 LLM 后端：
  - **本地模型** - 内置 Qwen3.5-0.8B，开箱即用
  - **Ollama** - 支持各种开源大模型
  - **OpenAI API** - 支持 GPT-4 等模型
  - **自定义 API** - 兼容 OpenAI 格式的任意 API

### 📝 笔记管理
- 自动记录课堂内容
- 支持手动添加笔记
- 笔记搜索、筛选、编辑功能
- 支持导出和分享

### 📖 PDF 阅读
- 内置 PDF 阅读器
- 支持书签和标注
- OCR 文字识别

### ✏️ 手写笔记
- 支持手写绘制
- 多种画笔和颜色
- 可与语音识别结合

### 🔒 隐私优先
- 完全本地运行模式
- 数据不上传云端
- 支持 100% 离线使用

## 📸 应用截图

> 截图将在后续版本中添加

## 🛠️ 技术栈

| 类别 | 技术 |
|------|------|
| 前端框架 | Flutter 3.0+ |
| 语音识别 | FunASR / Sherpa-onnx |
| 大语言模型 | OpenAI API / Ollama / llama.cpp |
| 状态管理 | Provider / Riverpod |
| 本地存储 | Hive |
| PDF 渲染 | pdfrx |
| OCR | Google ML Kit |

## 📋 环境要求

- Flutter SDK >= 3.0.0
- Dart SDK >= 3.0.0
- Windows 10+ 或 Android 5.0+

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/e7g/classhelper.git
cd classhelper
```

### 2. 安装依赖

```bash
flutter pub get
```

### 3. 配置应用

复制配置模板文件：

```bash
cp assets/config/config.json.example assets/config/config.json
```

根据需要修改 `config.json` 中的配置。

### 4. 运行应用

```bash
# 开发模式
flutter run

# 构建 Windows 应用
flutter build windows

# 构建 Android 应用
flutter build apk
```

## 📖 详细文档

- [安装配置指南](SETUP_GUIDE.md) - 详细的安装和配置说明
- [本地 LLM 指南](LOCAL_LLM_GUIDE.md) - 本地大模型使用说明
- [Android 说明](README_ANDROID.md) - Android 平台特定说明

## 🎯 推荐配置

### 低配置设备 (4GB RAM)
- ASR: 本地模式
- LLM: Qwen3.5-0.8B (内置)

### 中等配置设备 (8GB RAM)
- ASR: 本地模式
- LLM: Qwen2.5 1.5B 或 Ollama

### 高配置设备 (16GB+ RAM)
- ASR: 本地模式
- LLM: Qwen2 7B 或更大的模型

## 📁 项目结构

```
lib/
├── main.dart              # 应用入口
├── app.dart               # 应用配置
├── config/
│   └── app_config.dart    # 配置常量
├── models/                # 数据模型
├── services/              # 业务服务
│   ├── asr_service.dart   # ASR 服务
│   ├── llm_service.dart   # LLM 服务
│   └── ...
├── providers/             # 状态管理
├── screens/               # 页面
└── widgets/               # 组件
```

## 🔧 开发指南

### 代码规范

```bash
# 运行代码分析
flutter analyze
```

### 代码生成

如果修改了数据模型，需要重新生成 Hive 适配器：

```bash
flutter packages pub run build_runner build
```

## 🤝 贡献指南

欢迎贡献代码、报告问题或提出建议！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

### 贡献者

感谢所有贡献者的付出！

## 📄 许可证

本项目采用 GNU General Public License v3.0 许可证 - 详见 [LICENSE](LICENSE) 文件。

## 🙏 致谢

本项目使用了以下开源项目：

- [Flutter](https://flutter.dev/) - Google UI 框架
- [FunASR](https://github.com/alibaba-damo-academy/FunASR) - 阿里巴巴开源语音识别
- [Sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) - 离线语音识别框架
- [Ollama](https://ollama.ai/) - 本地大模型运行环境
- [llama.cpp](https://github.com/ggerganov/llama.cpp) - LLM 推理引擎
- [Qwen](https://github.com/QwenLM/Qwen) - 通义千问大模型

## 📮 联系方式

如有问题或建议，欢迎：

- 提交 [Issue](https://github.com/e7g/classhelper/issues)
- 发起 [Discussion](https://github.com/e7g/classhelper/discussions)

---

⭐ 如果这个项目对你有帮助，欢迎给个 Star！
