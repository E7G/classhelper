import 'dart:async';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:logger/logger.dart';
import '../models/asr_result.dart';

enum RemoteASRStatus {
  disconnected,
  connected,
  listening,
  notListening,
  error,
}

class RemoteASRService {
  final Logger _logger = Logger();
  final Dio _dio = Dio();
  
  String _apiUrl = '';
  String _apiKey = '';
  bool _isConnected = false;
  bool _isListening = false;
  
  final StreamController<ASRResult> _resultController = 
      StreamController<ASRResult>.broadcast();
  final StreamController<RemoteASRStatus> _statusController = 
      StreamController<RemoteASRStatus>.broadcast();
  final StreamController<String> _errorController = 
      StreamController<String>.broadcast();
  
  Stream<ASRResult> get resultStream => _resultController.stream;
  Stream<RemoteASRStatus> get statusStream => _statusController.stream;
  Stream<String> get errorStream => _errorController.stream;
  RemoteASRStatus get status => _isConnected ? RemoteASRStatus.connected : RemoteASRStatus.disconnected;
  bool get isAvailable => _isConnected;
  bool get isListening => _isListening;

  void configure({required String apiUrl, String? apiKey}) {
    _apiUrl = apiUrl;
    _apiKey = apiKey ?? '';
    _logger.i('Remote ASR configured: $apiUrl');
  }

  Future<bool> testConnection() async {
    try {
      final response = await _dio.get(
        '$_apiUrl/health',
        options: Options(
          headers: _apiKey.isNotEmpty ? {'Authorization': 'Bearer $_apiKey'} : null,
        ),
      );
      
      _isConnected = response.statusCode == 200;
      _updateStatus(_isConnected ? RemoteASRStatus.connected : RemoteASRStatus.disconnected);
      return _isConnected;
    } catch (e) {
      _logger.e('Failed to connect to remote ASR: $e');
      _isConnected = false;
      _updateStatus(RemoteASRStatus.error);
      return false;
    }
  }

  Future<void> connect() async {
    await testConnection();
  }

  Future<void> startListening() async {
    if (!_isConnected) {
      _errorController.add('未连接到远程 ASR 服务');
      return;
    }
    
    _isListening = true;
    _updateStatus(RemoteASRStatus.listening);
    _logger.i('Started listening to remote ASR');
  }

  Future<void> stopListening() async {
    _isListening = false;
    _updateStatus(RemoteASRStatus.notListening);
    _logger.i('Stopped listening to remote ASR');
  }

  Future<void> processAudioData(Uint8List audioData) async {
    if (!_isConnected || !_isListening) {
      return;
    }

    try {
      final response = await _dio.post(
        '$_apiUrl/transcribe',
        options: Options(
          headers: {
            'Content-Type': 'application/octet-stream',
            if (_apiKey.isNotEmpty) 'Authorization': 'Bearer $_apiKey',
          },
        ),
        data: Stream.fromIterable([audioData]),
      );

      final data = response.data as Map<String, dynamic>;
      final text = data['text'] as String? ?? '';
      final isFinal = data['is_final'] as bool? ?? false;

      if (text.isNotEmpty) {
        final result = ASRResult(
          text: text,
          confidence: (data['confidence'] as num?)?.toDouble() ?? 1.0,
          isFinal: isFinal,
          timestamp: DateTime.now(),
        );
        
        _resultController.add(result);
        
        if (isFinal) {
          _logger.i('Final result: $text');
        }
      }
    } catch (e) {
      _logger.e('Error processing audio with remote ASR: $e');
      _errorController.add('远程 ASR 处理失败: $e');
    }
  }

  void _updateStatus(RemoteASRStatus newStatus) {
    _statusController.add(newStatus);
  }

  void dispose() {
    _resultController.close();
    _statusController.close();
    _errorController.close();
    _dio.close();
  }
}
