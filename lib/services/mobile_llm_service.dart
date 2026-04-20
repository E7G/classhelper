import 'dart:async';
import 'dart:io';
import 'package:dio/dio.dart';
import 'package:path_provider/path_provider.dart';
import 'package:logger/logger.dart';
import '../models/llm_config.dart';

class MobileLLMService {
  final Logger _logger = Logger();
  final Dio _dio = Dio();
  
  LLMConfig _config = const LLMConfig();
  bool _isModelLoaded = false;
  String? _modelPath;
  
  final StreamController<String> _streamController = 
      StreamController<String>.broadcast();
  final StreamController<double> _downloadProgressController = 
      StreamController<double>.broadcast();
  
  Stream<String> get stream => _streamController.stream;
  Stream<double> get downloadProgress => _downloadProgressController.stream;
  LLMConfig get config => _config;
  bool get isModelLoaded => _isModelLoaded;
  String? get modelPath => _modelPath;

  void configure(LLMConfig config) {
    _config = config;
    _logger.i('Mobile LLM configured: ${config.providerType}, model: ${config.model}');
  }

  Future<String> get modelsDirectory async {
    final appDir = await getApplicationSupportDirectory();
    final modelsDir = Directory('${appDir.path}/models');
    
    if (!await modelsDir.exists()) {
      await modelsDir.create(recursive: true);
    }
    
    return modelsDir.path;
  }

  Future<List<String>> getDownloadedModels() async {
    try {
      final modelsDir = await modelsDirectory;
      final dir = Directory(modelsDir);
      final files = await dir.list().toList();
      
      return files
          .where((f) => f.path.endsWith('.gguf') || f.path.endsWith('.bin'))
          .map((f) => f.path.split('/').last)
          .toList();
    } catch (e) {
      _logger.e('Failed to get downloaded models: $e');
      return [];
    }
  }

  Future<void> downloadModel({
    required String url,
    required String modelName,
    Function(double)? onProgress,
  }) async {
    try {
      final modelsDir = await modelsDirectory;
      final savePath = '$modelsDir/$modelName';
      
      await _dio.download(
        url,
        savePath,
        onReceiveProgress: (received, total) {
          if (total != -1) {
            final progress = received / total;
            _downloadProgressController.add(progress);
            onProgress?.call(progress);
          }
        },
      );
      
      _logger.i('Model downloaded: $modelName');
    } catch (e) {
      _logger.e('Failed to download model: $e');
      rethrow;
    }
  }

  Future<bool> loadModel(String modelPath) async {
    try {
      final modelsDir = await modelsDirectory;
      _modelPath = '$modelsDir/$modelPath';
      
      final file = File(_modelPath!);
      if (!await file.exists()) {
        throw Exception('Model file not found: $_modelPath');
      }
      
      _isModelLoaded = true;
      _logger.i('Model loaded: $modelPath');
      return true;
    } catch (e) {
      _logger.e('Failed to load model: $e');
      _isModelLoaded = false;
      return false;
    }
  }

  Future<void> deleteModel(String modelName) async {
    try {
      final modelsDir = await modelsDirectory;
      final file = File('$modelsDir/$modelName');
      
      if (await file.exists()) {
        await file.delete();
        _logger.i('Model deleted: $modelName');
      }
    } catch (e) {
      _logger.e('Failed to delete model: $e');
      rethrow;
    }
  }

  void dispose() {
    _streamController.close();
    _downloadProgressController.close();
    _dio.close();
  }
}
