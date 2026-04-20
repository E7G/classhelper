import 'dart:async';
import 'dart:io';
import 'package:llamadart/llamadart.dart';
import 'package:path_provider/path_provider.dart';
import 'package:hive/hive.dart';
import 'package:logger/logger.dart';

enum LLMStatus {
  notLoaded,
  loadingModel,
  loaded,
  error,
}

class LocalLLMService {
  final Logger _logger = Logger();
  
  LlamaEngine? _engine;
  String? _modelPath;
  bool _isLoaded = false;
  LLMStatus _status = LLMStatus.notLoaded;
  late Box _settingsBox;
  
  final StreamController<String> _streamController = 
      StreamController<String>.broadcast();
  final StreamController<double> _loadProgressController = 
      StreamController<double>.broadcast();
  final StreamController<LLMStatus> _statusController = 
      StreamController<LLMStatus>.broadcast();
  
  Stream<String> get stream => _streamController.stream;
  Stream<double> get loadProgress => _loadProgressController.stream;
  Stream<LLMStatus> get statusStream => _statusController.stream;
  bool get isLoaded => _isLoaded;
  String? get modelPath => _modelPath;
  LLMStatus get status => _status;

  LocalLLMService() {
    _settingsBox = Hive.box('settings');
  }

  Future<String> get modelsDirectory async {
    final appDir = await getApplicationSupportDirectory();
    final modelsDir = Directory('${appDir.path}/models');
    
    if (!await modelsDir.exists()) {
      await modelsDir.create(recursive: true);
    }
    
    return modelsDir.path;
  }

  Future<String?> _getSavedModelPath() async {
    return _settingsBox.get('llm_model_path');
  }

  Future<bool> checkModelExists(String? modelPath) async {
    if (modelPath == null) return false;
    final file = File(modelPath);
    return await file.exists();
  }

  void setModelPath(String path) {
    _modelPath = path;
    _logger.i('Model path set to: $path');
  }

  Future<List<String>> getAvailableModels() async {
    try {
      final modelsDir = await modelsDirectory;
      final dir = Directory(modelsDir);
      
      if (!await dir.exists()) {
        return [];
      }
      
      final files = await dir.list().toList();
      
      return files
          .where((f) => f.path.endsWith('.gguf'))
          .map((f) => f.path)
          .toList();
    } catch (e) {
      _logger.e('Failed to get available models: $e');
      return [];
    }
  }

  Future<bool> loadModel(String? customModelPath) async {
    if (_isLoaded) return true;
    
    try {
      _updateStatus(LLMStatus.loadingModel);
      _loadProgressController.add(0.0);
      
      if (customModelPath != null && customModelPath.isNotEmpty) {
        _modelPath = customModelPath;
      } else {
        _modelPath = await _getSavedModelPath();
        
        if (_modelPath == null) {
          throw Exception('未配置LLM模型路径\n请先在"模型管理"中下载或指定模型路径');
        }
      }
      
      final file = File(_modelPath!);
      if (!await file.exists()) {
        throw Exception('模型文件不存在: $_modelPath\n请先在"模型管理"中下载或指定模型路径');
      }
      
      _loadProgressController.add(0.5);
      
      _engine = LlamaEngine(LlamaBackend());
      await _engine!.loadModel(_modelPath!);
      
      _isLoaded = true;
      _loadProgressController.add(1.0);
      _updateStatus(LLMStatus.loaded);
      
      _logger.i('Model loaded successfully: $_modelPath');
      return true;
    } catch (e) {
      _logger.e('Failed to load model: $e');
      _isLoaded = false;
      _updateStatus(LLMStatus.error);
      return false;
    }
  }

  Future<String> generate(
    String prompt, {
    String? systemPrompt,
    int maxTokens = 512,
    double temperature = 0.7,
  }) async {
    if (!_isLoaded || _engine == null) {
      throw Exception('模型未加载');
    }

    try {
      _logger.i('Generating response for prompt: ${prompt.length > 50 ? prompt.substring(0, 50) : prompt}...');
      
      final tokens = <String>[];
      
      await for (final chunk in _engine!.generate(prompt)) {
        tokens.add(chunk);
        _streamController.add(chunk);
        
        if (tokens.length >= maxTokens) {
          break;
        }
      }
      
      var response = tokens.join('');
      response = _removeThinkTags(response);
      _logger.i('Generated ${tokens.length} tokens');
      
      return response;
    } catch (e) {
      _logger.e('Failed to generate response: $e');
      rethrow;
    }
  }

  Stream<String> generateStream(
    String prompt, {
    String? systemPrompt,
    int maxTokens = 512,
    double temperature = 0.7,
  }) async* {
    if (!_isLoaded || _engine == null) {
      throw Exception('模型未加载');
    }

    try {
      _logger.i('Streaming response for prompt: ${prompt.length > 50 ? prompt.substring(0, 50) : prompt}...');
      
      var tokenCount = 0;
      var buffer = '';
      var inThink = false;
      
      await for (final chunk in _engine!.generate(prompt)) {
        tokenCount++;
        
        if (tokenCount > maxTokens) {
          break;
        }
        
        buffer += chunk;
        
        if (inThink) {
          final endIndex = buffer.indexOf('</think');
          if (endIndex != -1) {
            final closeBracket = buffer.indexOf('>', endIndex);
            if (closeBracket != -1) {
              buffer = buffer.substring(closeBracket + 1);
              inThink = false;
            }
          } else {
            buffer = '';
            continue;
          }
        }
        
        while (buffer.contains('<think')) {
          final startIndex = buffer.indexOf('<think');
          final closeBracket = buffer.indexOf('>', startIndex);
          
          if (closeBracket != -1) {
            final endIndex = buffer.indexOf('</think');
            if (endIndex != -1 && endIndex > closeBracket) {
              final endCloseBracket = buffer.indexOf('>', endIndex);
              if (endCloseBracket != -1) {
                final before = buffer.substring(0, startIndex);
                final after = buffer.substring(endCloseBracket + 1);
                buffer = before + after;
              } else {
                buffer = buffer.substring(0, startIndex);
                inThink = true;
                break;
              }
            } else {
              buffer = buffer.substring(0, startIndex);
              inThink = true;
              break;
            }
          } else {
            break;
          }
        }
        
        if (buffer.isNotEmpty && !inThink) {
          final output = buffer;
          buffer = '';
          yield output;
        }
      }
      
      if (buffer.isNotEmpty && !inThink) {
        yield buffer;
      }
      
      _logger.i('Streamed $tokenCount tokens');
    } catch (e) {
      _logger.e('Failed to stream response: $e');
      rethrow;
    }
  }

  String _removeThinkTags(String text) {
    var result = text;
    while (result.contains('<think')) {
      final startIndex = result.indexOf('<think');
      final startClose = result.indexOf('>', startIndex);
      if (startClose == -1) {
        result = result.substring(0, startIndex);
        break;
      }
      
      final endIndex = result.indexOf('</think');
      if (endIndex == -1 || endIndex < startClose) {
        result = result.substring(0, startIndex);
        break;
      }
      
      final endClose = result.indexOf('>', endIndex);
      if (endClose == -1) {
        result = result.substring(0, startIndex);
        break;
      }
      
      result = result.substring(0, startIndex) + result.substring(endClose + 1);
    }
    return result.trim();
  }

  void _updateStatus(LLMStatus newStatus) {
    if (_status != newStatus) {
      _status = newStatus;
      _statusController.add(_status);
    }
  }

  void unloadModel() {
    _engine?.dispose();
    _engine = null;
    _isLoaded = false;
    _updateStatus(LLMStatus.notLoaded);
    _logger.i('Model unloaded');
  }

  Future<bool> deleteModelFile(String path) async {
    try {
      final file = File(path);
      if (await file.exists()) {
        await file.delete();
        _logger.i('Model file deleted: $path');
        return true;
      }
      return false;
    } catch (e) {
      _logger.e('Failed to delete model file: $e');
      return false;
    }
  }

  void dispose() {
    unloadModel();
    _streamController.close();
    _loadProgressController.close();
    _statusController.close();
  }
}
