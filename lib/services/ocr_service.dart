import 'dart:io';
import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:hive/hive.dart';
import 'package:logger/logger.dart';
import '../models/ocr_config.dart';
import 'ocr_local.dart' if (dart.library.io) 'ocr_local_mobile.dart' as ocr_local;

class OCRService {
  final Logger _logger = Logger();
  final Dio _dio = Dio();
  OCRConfig _config = const OCRConfig();
  bool _isLocalAvailable = false;

  OCRConfig get config => _config;
  bool get isLocalAvailable => _isLocalAvailable;

  Future<void> init() async {
    final settingsBox = Hive.box('settings');
    final configJson = settingsBox.get('ocr_config') as Map?;
    if (configJson != null) {
      _config = OCRConfig.fromJson(Map<String, dynamic>.from(configJson));
    }

    _isLocalAvailable = await ocr_local.isAvailable();
  }

  void configure(OCRConfig config) {
    _config = config;
    Hive.box('settings').put('ocr_config', config.toJson());
  }

  Future<String?> recognizeText(String imagePath) async {
    if (_config.mode == OCRMode.local && _isLocalAvailable) {
      return _recognizeLocal(imagePath);
    } else {
      return _recognizeAPI(imagePath);
    }
  }

  Future<String?> _recognizeLocal(String imagePath) async {
    if (!_isLocalAvailable) {
      _logger.w('Local OCR not available on this platform');
      return null;
    }

    try {
      final file = File(imagePath);
      if (!await file.exists()) {
        _logger.e('Image file not found: $imagePath');
        return null;
      }

      final text = await ocr_local.recognizeText(imagePath, _config.language);
      if (text != null && text.isNotEmpty) {
        _logger.i('OCR recognized ${text.length} characters');
        return text;
      }

      return null;
    } catch (e) {
      _logger.e('Local OCR failed: $e');
      return null;
    }
  }

  Future<String?> _recognizeAPI(String imagePath) async {
    if (_config.apiUrl == null || _config.apiUrl!.isEmpty) {
      _logger.e('OCR API URL not configured');
      return null;
    }

    try {
      final file = File(imagePath);
      if (!await file.exists()) {
        _logger.e('Image file not found: $imagePath');
        return null;
      }

      final bytes = await file.readAsBytes();
      final base64Image = base64Encode(bytes);

      final response = await _dio.post(
        _config.apiUrl!,
        data: {
          'image': base64Image,
          'language': _config.language.name,
        },
        options: Options(
          headers: {
            if (_config.apiKey != null && _config.apiKey!.isNotEmpty)
              'Authorization': 'Bearer ${_config.apiKey}',
            'Content-Type': 'application/json',
          },
        ),
      );

      if (response.statusCode == 200) {
        final data = response.data;

        if (data is Map && data.containsKey('text')) {
          return data['text'] as String;
        } else if (data is Map && data.containsKey('data')) {
          final dataContent = data['data'];
          if (dataContent is Map && dataContent.containsKey('text')) {
            return dataContent['text'] as String;
          } else if (dataContent is String) {
            return dataContent;
          }
        } else if (data is String) {
          return data;
        } else if (data is List) {
          final texts = <String>[];
          for (final item in data) {
            if (item is Map && item.containsKey('text')) {
              texts.add(item['text'].toString());
            } else if (item is String) {
              texts.add(item);
            }
          }
          if (texts.isNotEmpty) {
            return texts.join('\n');
          }
        }

        _logger.w('Unexpected API response format: $data');
        return response.data.toString();
      }

      _logger.e('OCR API failed: ${response.statusCode}');
      return null;
    } catch (e) {
      _logger.e('OCR API error: $e');
      return null;
    }
  }

  void dispose() {
    _dio.close();
  }
}
