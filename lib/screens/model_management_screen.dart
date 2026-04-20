import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:file_picker/file_picker.dart';
import 'package:hive/hive.dart';
import 'package:dio/dio.dart';
import 'dart:io';
import 'package:path_provider/path_provider.dart';
import 'package:logger/logger.dart';

class ModelManagementScreen extends StatefulWidget {
  const ModelManagementScreen({super.key});

  @override
  State<ModelManagementScreen> createState() => _ModelManagementScreenState();
}

class _ModelManagementScreenState extends State<ModelManagementScreen> {
  final Logger _logger = Logger();
  final Dio _dio = Dio();
  
  String? _llmModelPath;
  bool _isDownloadingLlm = false;
  double _downloadProgress = 0.0;
  String _downloadStatus = '';
  late Box _settingsBox;

  static const ModelInfo llmModelInfo = ModelInfo(
    name: 'Qwen2.5-0.5B-Instruct Q4_K_M',
    description: '轻量级本地大语言模型，适合课堂助手场景',
    downloadUrl: 'https://ghproxy.net/https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf',
    fileName: 'qwen2.5-0.5b-instruct-q4_k_m.gguf',
    size: '约 400MB',
    type: 'file',
  );

  @override
  void initState() {
    super.initState();
    _settingsBox = Hive.box('settings');
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    setState(() {
      _llmModelPath = _settingsBox.get('llm_model_path');
    });
  }

  Future<void> _saveLlmModelPath(String? path) async {
    await _settingsBox.put('llm_model_path', path);
    setState(() {
      _llmModelPath = path;
    });
  }

  Future<String> get _modelsDirectory async {
    final appDir = await getApplicationSupportDirectory();
    final modelsDir = Directory('${appDir.path}/models');
    
    if (!await modelsDir.exists()) {
      await modelsDir.create(recursive: true);
    }
    
    return modelsDir.path;
  }

  Future<void> _downloadLlmModel() async {
    setState(() {
      _isDownloadingLlm = true;
      _downloadProgress = 0.0;
      _downloadStatus = '准备下载...';
    });

    try {
      final modelsDir = await _modelsDirectory;
      final savePath = '$modelsDir/${llmModelInfo.fileName}';

      setState(() => _downloadStatus = '正在下载 ${llmModelInfo.name}...');

      await _dio.download(
        llmModelInfo.downloadUrl,
        savePath,
        onReceiveProgress: (received, total) {
          if (total > 0) {
            setState(() {
              _downloadProgress = received / total;
              _downloadStatus = '下载中 ${(received / 1024 / 1024).toStringAsFixed(1)}MB / ${(total / 1024 / 1024).toStringAsFixed(1)}MB';
            });
          }
        },
      );

      await _saveLlmModelPath(savePath);

      setState(() {
        _downloadProgress = 1.0;
        _downloadStatus = '下载完成';
      });

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('LLM模型下载完成')),
        );
      }
    } catch (e) {
      _logger.e('Failed to download LLM model: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('下载失败: $e')),
        );
      }
    } finally {
      setState(() {
        _isDownloadingLlm = false;
      });
    }
  }

  Future<void> _selectLlmModelPath() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.any,
        allowCompression: false,
        dialogTitle: '选择LLM模型文件',
      );
      
      if (result != null && result.files.single.path != null) {
        final path = result.files.single.path!;
        if (!path.toLowerCase().endsWith('.gguf')) {
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('请选择.gguf格式的模型文件')),
            );
          }
          return;
        }
        await _saveLlmModelPath(path);
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('LLM模型路径已设置')),
          );
        }
      }
    } catch (e) {
      _logger.e('Failed to select LLM model: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('选择文件失败: $e')),
        );
      }
    }
  }

  Future<void> _clearLlmModelPath() async {
    await _saveLlmModelPath(null);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('模型管理'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _buildInfoCard(),
          const SizedBox(height: 16),
          _buildLlmModelCard(),
          if (_isDownloadingLlm) ...[
            const SizedBox(height: 16),
            _buildDownloadProgress(),
          ],
        ],
      ),
    );
  }

  Widget _buildInfoCard() {
    return Card(
      color: Theme.of(context).colorScheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  Icons.info_outline,
                  color: Theme.of(context).colorScheme.onPrimaryContainer,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'ASR语音识别模型已内置',
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.onPrimaryContainer,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'LLM模型文件较大，建议在WiFi环境下下载。也可以手动下载后指定本地路径。',
              style: TextStyle(
                color: Theme.of(context).colorScheme.onPrimaryContainer,
                fontSize: 12,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildLlmModelCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.secondaryContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Icon(
                    Icons.psychology,
                    color: Theme.of(context).colorScheme.onSecondaryContainer,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '大语言模型 (LLM)',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      Text(
                        llmModelInfo.name,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              llmModelInfo.description,
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            const SizedBox(height: 4),
            Text(
              '大小: ${llmModelInfo.size}',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 12),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                children: [
                  Icon(
                    _llmModelPath != null ? Icons.check_circle : Icons.error_outline,
                    size: 20,
                    color: _llmModelPath != null 
                        ? Colors.green 
                        : Theme.of(context).colorScheme.error,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _llmModelPath ?? '未配置模型路径',
                      style: Theme.of(context).textTheme.bodySmall,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  if (_llmModelPath != null)
                    IconButton(
                      icon: const Icon(Icons.close, size: 18),
                      onPressed: _clearLlmModelPath,
                      tooltip: '清除',
                      visualDensity: VisualDensity.compact,
                    ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _isDownloadingLlm ? null : _downloadLlmModel,
                    icon: _isDownloadingLlm 
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.download, size: 18),
                    label: const Text('下载模型'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _selectLlmModelPath,
                    icon: const Icon(Icons.folder_open, size: 18),
                    label: const Text('选择文件'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    ).animate().fadeIn().slideX(begin: 0.1);
  }

  Widget _buildDownloadProgress() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    _downloadStatus,
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: _downloadProgress,
                minHeight: 6,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '${(_downloadProgress * 100).toStringAsFixed(1)}%',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    ).animate().fadeIn();
  }

  @override
  void dispose() {
    _dio.close();
    super.dispose();
  }
}

class ModelInfo {
  final String name;
  final String description;
  final String downloadUrl;
  final String fileName;
  final String size;
  final String type;

  const ModelInfo({
    required this.name,
    required this.description,
    required this.downloadUrl,
    required this.fileName,
    required this.size,
    this.type = 'file',
  });
}
