import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:file_picker/file_picker.dart';
import 'package:hive/hive.dart';
import 'package:provider/provider.dart';
import 'package:dio/dio.dart';
import 'dart:io';
import 'package:path_provider/path_provider.dart';
import 'package:logger/logger.dart';
import '../providers/question_provider.dart';

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
    name: 'Qwen3.5-0.8B Q4_K_M',
    description: '轻量级本地大语言模型，适合课堂助手场景',
    downloadUrl: 'https://www.modelscope.cn/models/unsloth/Qwen3.5-0.8B-GGUF/resolve/master/Qwen3.5-0.8B-Q4_K_M.gguf',
    fileName: 'Qwen3.5-0.8B-Q4_K_M.gguf',
    size: '约 500MB',
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
          if (mounted && total > 0) {
            setState(() {
              _downloadProgress = received / total;
              _downloadStatus = '下载中 ${(received / 1024 / 1024).toStringAsFixed(1)}MB / ${(total / 1024 / 1024).toStringAsFixed(1)}MB';
            });
          }
        },
      );

      await _saveLlmModelPath(savePath);

      if (mounted) {
        setState(() {
          _downloadProgress = 1.0;
          _downloadStatus = '下载完成';
        });
      }

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
      if (mounted) {
        setState(() {
          _isDownloadingLlm = false;
        });
      }
    }
  }

  void _stopLlmDownload() {
    _dio.close();
    setState(() {
      _isDownloadingLlm = false;
      _downloadStatus = '下载已取消';
    });
  }

  Future<void> _deleteLlmModel() async {
    if (_llmModelPath == null) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认删除'),
        content: Text('确定要删除模型文件吗？\n$_llmModelPath'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );

    if (confirmed == true && mounted) {
      final questionProvider = context.read<QuestionProvider>();
      questionProvider.stopLLM();
      final success = await questionProvider.deleteLLMModel(_llmModelPath!);
      if (success) {
        await _saveLlmModelPath(null);
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('模型文件已删除')),
          );
        }
      } else {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('删除失败')),
          );
        }
      }
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
                    onPressed: _isDownloadingLlm ? _stopLlmDownload : _downloadLlmModel,
                    icon: _isDownloadingLlm
                        ? const Icon(Icons.stop, size: 18)
                        : const Icon(Icons.download, size: 18),
                    label: Text(_isDownloadingLlm ? '停止下载' : '下载模型'),
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
            if (_llmModelPath != null) ...[
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: _deleteLlmModel,
                  icon: const Icon(Icons.delete, size: 18),
                  label: const Text('删除本地模型'),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: Theme.of(context).colorScheme.error,
                  ),
                ),
              ),
            ],
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
