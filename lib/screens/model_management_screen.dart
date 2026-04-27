import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:file_picker/file_picker.dart';
import 'package:hive/hive.dart';
import 'package:provider/provider.dart';
import 'package:dio/dio.dart';
import 'dart:io';
import 'package:path_provider/path_provider.dart';
import 'package:logger/logger.dart';
import 'package:archive/archive.dart';
import '../providers/question_provider.dart';
import '../models/asr_model_config.dart';

class ModelManagementScreen extends StatefulWidget {
  const ModelManagementScreen({super.key});

  @override
  State<ModelManagementScreen> createState() => _ModelManagementScreenState();
}

class _ModelManagementScreenState extends State<ModelManagementScreen> {
  final Logger _logger = Logger();
  final Dio _dio = Dio();
  CancelToken? _downloadCancelToken;

  String? _llmModelPath;
  bool _isDownloadingLlm = false;
  double _llmDownloadProgress = 0.0;
  String _llmDownloadStatus = '';
  late Box _settingsBox;

  ASRModelConfig _asrConfig = ASRModelConfig();
  bool _isDownloadingAsr = false;
  double _asrDownloadProgress = 0.0;
  String _asrDownloadStatus = '';
  bool _isDownloadingVad = false;
  double _vadDownloadProgress = 0.0;
  String _vadDownloadStatus = '';

  final _githubProxyController = TextEditingController();

  static const ModelInfo llmModelInfo = ModelInfo(
    name: 'Qwen3.5-0.8B Q4_K_M',
    description: '轻量级本地大语言模型，适合课堂助手场景',
    downloadUrl:
        'https://www.modelscope.cn/models/unsloth/Qwen3.5-0.8B-GGUF/resolve/master/Qwen3.5-0.8B-Q4_K_M.gguf',
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
    final asrConfigJson = _settingsBox.get('asr_model_config');
    setState(() {
      _llmModelPath = _settingsBox.get('llm_model_path');
      if (asrConfigJson != null) {
        try {
          _asrConfig = ASRModelConfig.fromJson(
              Map<String, dynamic>.from(asrConfigJson as Map));
          _githubProxyController.text = _asrConfig.githubProxyUrl;
        } catch (e) {
          _logger.e('Failed to parse ASR config: $e');
        }
      }
    });
  }

  Future<void> _saveLlmModelPath(String? path) async {
    await _settingsBox.put('llm_model_path', path);
    setState(() {
      _llmModelPath = path;
    });
  }

  Future<void> _saveAsrConfig(ASRModelConfig config) async {
    await _settingsBox.put('asr_model_config', config.toJson());
    setState(() {
      _asrConfig = config;
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

  Future<String> get _asrModelDirectory async {
    final appDir = await getApplicationSupportDirectory();
    final dir = Directory('${appDir.path}/qwen3_asr_model');
    if (!await dir.exists()) {
      await dir.create(recursive: true);
    }
    return dir.path;
  }

  Future<void> _downloadLlmModel() async {
    _downloadCancelToken = CancelToken();

    setState(() {
      _isDownloadingLlm = true;
      _llmDownloadProgress = 0.0;
      _llmDownloadStatus = '准备下载...';
    });

    try {
      final modelsDir = await _modelsDirectory;
      final savePath = '$modelsDir/${llmModelInfo.fileName}';

      setState(() => _llmDownloadStatus = '正在下载 ${llmModelInfo.name}...');

      await _dio.download(
        llmModelInfo.downloadUrl,
        savePath,
        cancelToken: _downloadCancelToken,
        onReceiveProgress: (received, total) {
          if (mounted && total > 0) {
            setState(() {
              _llmDownloadProgress = received / total;
              _llmDownloadStatus =
                  '下载中 ${(received / 1024 / 1024).toStringAsFixed(1)}MB / ${(total / 1024 / 1024).toStringAsFixed(1)}MB';
            });
          }
        },
      );

      await _saveLlmModelPath(savePath);

      if (mounted) {
        setState(() {
          _llmDownloadProgress = 1.0;
          _llmDownloadStatus = '下载完成';
        });
      }

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('LLM模型下载完成')),
        );
      }
    } on DioException catch (e) {
      if (e.type == DioExceptionType.cancel) {
        _logger.i('Download cancelled by user');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('下载已取消')),
          );
        }
      } else {
        _logger.e('Failed to download LLM model: $e');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('下载失败: $e')),
          );
        }
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
    _downloadCancelToken?.cancel('user_cancelled');
    setState(() {
      _isDownloadingLlm = false;
      _llmDownloadStatus = '下载已取消';
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

  Future<void> _downloadAsrModel() async {
    _downloadCancelToken = CancelToken();

    setState(() {
      _isDownloadingAsr = true;
      _asrDownloadProgress = 0.0;
      _asrDownloadStatus = '准备下载...';
    });

    try {
      final targetDir = await _asrModelDirectory;
      final config = _asrConfig;

      if (config.source == ASRModelSource.modelscope) {
        await _downloadAsrFromModelscope(targetDir, config);
      } else {
        await _downloadAsrFromGithub(targetDir, config);
      }

      final newConfig = ASRModelConfig(
        source: config.source,
        size: config.size,
        githubProxyUrl: config.githubProxyUrl,
        modelDir: targetDir,
        vadModelPath: config.vadModelPath,
      );
      await _saveAsrConfig(newConfig);

      if (mounted) {
        setState(() {
          _asrDownloadProgress = 1.0;
          _asrDownloadStatus = '下载完成';
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('ASR模型下载完成')),
        );
      }
    } on DioException catch (e) {
      if (e.type == DioExceptionType.cancel) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('下载已取消')),
          );
        }
      } else {
        _logger.e('Failed to download ASR model: $e');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('下载失败: $e')),
          );
        }
      }
    } catch (e) {
      _logger.e('Failed to download ASR model: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('下载失败: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isDownloadingAsr = false;
        });
      }
    }
  }

  Future<void> _downloadAsrFromModelscope(
      String targetDir, ASRModelConfig config) async {
    final allFiles = [...config.modelscopeAsrFiles, ...config.modelscopeTokenizerFiles];
    final totalFiles = allFiles.length;
    final tokenizerDir = Directory('$targetDir/tokenizer');
    if (!await tokenizerDir.exists()) {
      await tokenizerDir.create(recursive: true);
    }

    for (var i = 0; i < allFiles.length; i++) {
      final filePath = allFiles[i];
      final url = config.modelscopeFileUrl(filePath);
      final fileName = filePath.split('/').last;

      String savePath;
      if (filePath.startsWith('tokenizer/')) {
        savePath = '$targetDir/tokenizer/$fileName';
      } else {
        savePath = '$targetDir/$fileName';
      }

      setState(() {
        _asrDownloadStatus = '下载 $fileName (${i + 1}/$totalFiles)...';
        _asrDownloadProgress = i / totalFiles;
      });

      await _dio.download(
        url,
        savePath,
        cancelToken: _downloadCancelToken,
        onReceiveProgress: (received, total) {
          if (mounted && total > 0) {
            setState(() {
              final fileProgress = received / total;
              _asrDownloadProgress =
                  (i + fileProgress) / totalFiles;
              _asrDownloadStatus =
                  '下载 $fileName ${(received / 1024 / 1024).toStringAsFixed(1)}MB / ${(total / 1024 / 1024).toStringAsFixed(1)}MB';
            });
          }
        },
      );
    }
  }

  Future<void> _downloadAsrFromGithub(
      String targetDir, ASRModelConfig config) async {
    final modelsDir = await _modelsDirectory;
    final archiveName = '${config.asrModelArchiveName}.tar.bz2';
    final archivePath = '$modelsDir/$archiveName';

    setState(() {
      _asrDownloadStatus = '正在下载 $archiveName...';
      _asrDownloadProgress = 0.0;
    });

    await _dio.download(
      config.githubAsrModelUrl,
      archivePath,
      cancelToken: _downloadCancelToken,
      onReceiveProgress: (received, total) {
        if (mounted && total > 0) {
          setState(() {
            _asrDownloadProgress = received / total * 0.9;
            _asrDownloadStatus =
                '下载中 ${(received / 1024 / 1024).toStringAsFixed(1)}MB / ${(total / 1024 / 1024).toStringAsFixed(1)}MB';
          });
        }
      },
    );

    setState(() {
      _asrDownloadStatus = '正在解压...';
      _asrDownloadProgress = 0.9;
    });

    final archiveBytes = await File(archivePath).readAsBytes();
    final bz2Data = BZip2Decoder().decodeBytes(archiveBytes);
    final tarArchive = TarDecoder().decodeBytes(bz2Data);

    final extractDirName = config.asrModelArchiveName;

    for (final file in tarArchive) {
      final relativePath = file.name;
      if (relativePath.startsWith('$extractDirName/')) {
        final subPath = relativePath.substring(extractDirName.length + 1);
        if (subPath.isEmpty) continue;

        final outputPath = '$targetDir/$subPath';
        final outputFile = File(outputPath);
        final outputParent = outputFile.parent;
        if (!await outputParent.exists()) {
          await outputParent.create(recursive: true);
        }

        if (file.isFile) {
          await outputFile.writeAsBytes(file.content as List<int>);
        }
      }
    }

    try {
      await File(archivePath).delete();
    } catch (_) {}

    setState(() {
      _asrDownloadProgress = 0.95;
    });
  }

  Future<void> _downloadVadModel() async {
    _downloadCancelToken = CancelToken();

    setState(() {
      _isDownloadingVad = true;
      _vadDownloadProgress = 0.0;
      _vadDownloadStatus = '准备下载...';
    });

    try {
      final targetDir = await _asrModelDirectory;
      final savePath = '$targetDir/silero_vad.onnx';
      final config = _asrConfig;

      setState(() => _vadDownloadStatus = '正在下载 silero_vad.onnx...');

      await _dio.download(
        config.vadModelUrl,
        savePath,
        cancelToken: _downloadCancelToken,
        onReceiveProgress: (received, total) {
          if (mounted && total > 0) {
            setState(() {
              _vadDownloadProgress = received / total;
              _vadDownloadStatus =
                  '下载中 ${(received / 1024 / 1024).toStringAsFixed(1)}MB / ${(total / 1024 / 1024).toStringAsFixed(1)}MB';
            });
          }
        },
      );

      final newConfig = ASRModelConfig(
        source: config.source,
        size: config.size,
        githubProxyUrl: config.githubProxyUrl,
        modelDir: config.modelDir,
        vadModelPath: savePath,
      );
      await _saveAsrConfig(newConfig);

      if (mounted) {
        setState(() {
          _vadDownloadProgress = 1.0;
          _vadDownloadStatus = '下载完成';
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('VAD模型下载完成')),
        );
      }
    } on DioException catch (e) {
      if (e.type == DioExceptionType.cancel) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('下载已取消')),
          );
        }
      } else {
        _logger.e('Failed to download VAD model: $e');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('下载失败: $e')),
          );
        }
      }
    } catch (e) {
      _logger.e('Failed to download VAD model: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('下载失败: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isDownloadingVad = false;
        });
      }
    }
  }

  void _stopAsrDownload() {
    _downloadCancelToken?.cancel('user_cancelled');
    setState(() {
      _isDownloadingAsr = false;
      _asrDownloadStatus = '下载已取消';
    });
  }

  void _stopVadDownload() {
    _downloadCancelToken?.cancel('user_cancelled');
    setState(() {
      _isDownloadingVad = false;
      _vadDownloadStatus = '下载已取消';
    });
  }

  Future<void> _deleteVadModel() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认删除'),
        content: const Text('确定要删除VAD模型文件吗？'),
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
      try {
        final vadPath = _asrConfig.vadModelPath;
        if (vadPath != null) {
          final file = File(vadPath);
          if (await file.exists()) {
            await file.delete();
          }
        }
        final newConfig = ASRModelConfig(
          source: _asrConfig.source,
          size: _asrConfig.size,
          githubProxyUrl: _asrConfig.githubProxyUrl,
          modelDir: _asrConfig.modelDir,
          vadModelPath: null,
        );
        await _saveAsrConfig(newConfig);
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('VAD模型已删除')),
          );
        }
      } catch (e) {
        _logger.e('Failed to delete VAD model: $e');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('删除失败: $e')),
          );
        }
      }
    }
  }

  Future<void> _deleteAsrModel() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认删除'),
        content: const Text('确定要删除ASR模型文件吗？'),
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
      try {
        final modelDir = _asrConfig.modelDir;
        final vadPath = _asrConfig.vadModelPath;
        bool vadWasInModelDir = false;
        if (vadPath != null && modelDir != null) {
          vadWasInModelDir = vadPath.startsWith(modelDir);
        }
        if (modelDir != null) {
          final dir = Directory(modelDir);
          if (await dir.exists()) {
            await dir.delete(recursive: true);
          }
        }
        final newConfig = ASRModelConfig(
          source: _asrConfig.source,
          size: _asrConfig.size,
          githubProxyUrl: _asrConfig.githubProxyUrl,
          modelDir: null,
          vadModelPath: vadWasInModelDir ? null : vadPath,
        );
        await _saveAsrConfig(newConfig);
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('ASR模型已删除')),
          );
        }
      } catch (e) {
        _logger.e('Failed to delete ASR model: $e');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('删除失败: $e')),
          );
        }
      }
    }
  }

  Future<void> _selectAsrModelDir() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.any,
        allowCompression: false,
        dialogTitle: '选择ASR模型目录中的conv_frontend.onnx',
      );

      if (result != null && result.files.single.path != null) {
        final filePath = result.files.single.path!;
        if (!filePath.endsWith('conv_frontend.onnx')) {
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('请选择conv_frontend.onnx文件来定位模型目录')),
            );
          }
          return;
        }
        final modelDir = File(filePath).parent.path;
        final newConfig = ASRModelConfig(
          source: _asrConfig.source,
          size: _asrConfig.size,
          githubProxyUrl: _asrConfig.githubProxyUrl,
          modelDir: modelDir,
          vadModelPath: _asrConfig.vadModelPath,
        );
        await _saveAsrConfig(newConfig);
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('ASR模型目录已设置')),
          );
        }
      }
    } catch (e) {
      _logger.e('Failed to select ASR model dir: $e');
    }
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
          _buildAsrModelCard(),
          if (_isDownloadingAsr) ...[
            const SizedBox(height: 16),
            _buildAsrDownloadProgress(),
          ],
          const SizedBox(height: 16),
          _buildVadModelCard(),
          if (_isDownloadingVad) ...[
            const SizedBox(height: 16),
            _buildVadDownloadProgress(),
          ],
          const SizedBox(height: 16),
          _buildLlmModelCard(),
          if (_isDownloadingLlm) ...[
            const SizedBox(height: 16),
            _buildLlmDownloadProgress(),
          ],
        ],
      ),
    );
  }

  Widget _buildAsrModelCard() {
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
                    Icons.mic,
                    color: Theme.of(context).colorScheme.onSecondaryContainer,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '语音识别模型 (ASR)',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      Text(
                        'Qwen3-ASR 离线语音识别',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              '用于本地离线语音转文字，需配合VAD模型使用。',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            const SizedBox(height: 12),
            _buildSourceSelector(),
            const SizedBox(height: 12),
            _buildSizeSelector(),
            if (_asrConfig.source == ASRModelSource.github) ...[
              const SizedBox(height: 12),
              TextField(
                controller: _githubProxyController,
                decoration: const InputDecoration(
                  labelText: 'GitHub下载加速站',
                  border: OutlineInputBorder(),
                  hintText: 'https://ghproxy.com/',
                  helperText: '留空则直连GitHub，国内网络建议配置加速站',
                  isDense: true,
                ),
                onChanged: (value) {
                  final newConfig = ASRModelConfig(
                    source: _asrConfig.source,
                    size: _asrConfig.size,
                    githubProxyUrl: value,
                    modelDir: _asrConfig.modelDir,
                    vadModelPath: _asrConfig.vadModelPath,
                  );
                  _saveAsrConfig(newConfig);
                },
              ),
            ],
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
                    _asrConfig.isModelReady
                        ? Icons.check_circle
                        : Icons.error_outline,
                    size: 20,
                    color: _asrConfig.isModelReady
                        ? Colors.green
                        : Theme.of(context).colorScheme.error,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _asrConfig.modelDir ?? '未下载ASR模型',
                      style: Theme.of(context).textTheme.bodySmall,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            if (!_asrConfig.isModelReady)
              Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: _isDownloadingAsr
                          ? _stopAsrDownload
                          : _downloadAsrModel,
                      icon: _isDownloadingAsr
                          ? const Icon(Icons.stop, size: 18)
                          : const Icon(Icons.download, size: 18),
                      label: Text(_isDownloadingAsr ? '停止下载' : '下载模型'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: _selectAsrModelDir,
                      icon: const Icon(Icons.folder_open, size: 18),
                      label: const Text('选择目录'),
                    ),
                  ),
                ],
              )
            else ...[
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: _deleteAsrModel,
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

  Widget _buildSourceSelector() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '下载来源',
          style: Theme.of(context).textTheme.bodyMedium,
        ),
        const SizedBox(height: 8),
        SegmentedButton<ASRModelSource>(
          segments: const [
            ButtonSegment(
              value: ASRModelSource.modelscope,
              label: Text('ModelScope'),
              icon: Icon(Icons.cloud, size: 16),
            ),
            ButtonSegment(
              value: ASRModelSource.github,
              label: Text('GitHub'),
              icon: Icon(Icons.code, size: 16),
            ),
          ],
          selected: {_asrConfig.source},
          onSelectionChanged: (Set<ASRModelSource> selection) {
            final newConfig = ASRModelConfig(
              source: selection.first,
              size: _asrConfig.size,
              githubProxyUrl: _asrConfig.githubProxyUrl,
              modelDir: _asrConfig.modelDir,
              vadModelPath: _asrConfig.vadModelPath,
            );
            _saveAsrConfig(newConfig);
          },
        ),
      ],
    );
  }

  Widget _buildSizeSelector() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '模型大小',
          style: Theme.of(context).textTheme.bodyMedium,
        ),
        const SizedBox(height: 8),
        SegmentedButton<ASRModelSize>(
          segments: const [
            ButtonSegment(
              value: ASRModelSize.size06B,
              label: Text('0.6B'),
              tooltip: '约940MB，速度快',
            ),
            ButtonSegment(
              value: ASRModelSize.size17B,
              label: Text('1.7B'),
              tooltip: '约2GB，精度高',
            ),
          ],
          selected: {_asrConfig.size},
          onSelectionChanged: (Set<ASRModelSize> selection) {
            final newConfig = ASRModelConfig(
              source: _asrConfig.source,
              size: selection.first,
              githubProxyUrl: _asrConfig.githubProxyUrl,
              modelDir: _asrConfig.modelDir,
              vadModelPath: _asrConfig.vadModelPath,
            );
            _saveAsrConfig(newConfig);
          },
        ),
      ],
    );
  }

  Widget _buildVadModelCard() {
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
                    color: Theme.of(context).colorScheme.tertiaryContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Icon(
                    Icons.graphic_eq,
                    color: Theme.of(context).colorScheme.onTertiaryContainer,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '语音活动检测 (VAD)',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      Text(
                        'Silero VAD',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              '用于检测语音片段，ASR模型必需。将根据ASR来源自动选择下载源。',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            const SizedBox(height: 4),
            Text(
              _asrConfig.source == ASRModelSource.modelscope
                  ? '当前将从 ModelScope 下载'
                  : _asrConfig.githubProxyUrl.isNotEmpty
                      ? '当前将从 GitHub（使用加速站）下载'
                      : '当前将从 GitHub 直连下载',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.primary,
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
                    _asrConfig.isVadReady
                        ? Icons.check_circle
                        : Icons.error_outline,
                    size: 20,
                    color: _asrConfig.isVadReady
                        ? Colors.green
                        : Theme.of(context).colorScheme.error,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _asrConfig.vadModelPath ?? '未下载VAD模型',
                      style: Theme.of(context).textTheme.bodySmall,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            if (!_asrConfig.isVadReady)
              FilledButton.icon(
                onPressed: _isDownloadingVad ? _stopVadDownload : _downloadVadModel,
                icon: _isDownloadingVad
                    ? const Icon(Icons.stop, size: 18)
                    : const Icon(Icons.download, size: 18),
                label: Text(_isDownloadingVad ? '停止下载' : '下载VAD模型'),
              )
            else
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: _deleteVadModel,
                  icon: const Icon(Icons.delete, size: 18),
                  label: const Text('删除VAD模型'),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: Theme.of(context).colorScheme.error,
                  ),
                ),
              ),
          ],
        ),
      ),
    ).animate().fadeIn().slideX(begin: 0.1);
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
                    _llmModelPath != null
                        ? Icons.check_circle
                        : Icons.error_outline,
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
            if (_llmModelPath == null)
              Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      onPressed:
                          _isDownloadingLlm ? _stopLlmDownload : _downloadLlmModel,
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
              )
            else
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
        ),
      ),
    ).animate().fadeIn().slideX(begin: 0.1);
  }

  Widget _buildAsrDownloadProgress() {
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
                    'ASR: $_asrDownloadStatus',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: _asrDownloadProgress,
                minHeight: 6,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '${(_asrDownloadProgress * 100).toStringAsFixed(1)}%',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    ).animate().fadeIn();
  }

  Widget _buildVadDownloadProgress() {
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
                    'VAD: $_vadDownloadStatus',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: _vadDownloadProgress,
                minHeight: 6,
              ),
            ),
          ],
        ),
      ),
    ).animate().fadeIn();
  }

  Widget _buildLlmDownloadProgress() {
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
                    'LLM: $_llmDownloadStatus',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: _llmDownloadProgress,
                minHeight: 6,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '${(_llmDownloadProgress * 100).toStringAsFixed(1)}%',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    ).animate().fadeIn();
  }

  @override
  void dispose() {
    _githubProxyController.dispose();
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
