import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'package:path_provider/path_provider.dart';
import 'package:hive/hive.dart';
import 'package:logger/logger.dart';
import 'package:record/record.dart';
import '../models/asr_result.dart';
import '../models/asr_model_config.dart';
import 'asr_isolate_processor.dart';

enum SherpaASRStatus {
  notInitialized,
  loadingModel,
  initialized,
  listening,
  notListening,
  error,
}

class SherpaASRService {
  final Logger _logger = Logger();

  final AsrIsolateProcessor _processor = AsrIsolateProcessor();
  final AudioRecorder _audioRecorder = AudioRecorder();

  final StreamController<ASRResult> _resultController =
      StreamController<ASRResult>.broadcast();
  final StreamController<SherpaASRStatus> _statusController =
      StreamController<SherpaASRStatus>.broadcast();
  final StreamController<String> _errorController =
      StreamController<String>.broadcast();
  final StreamController<double> _progressController =
      StreamController<double>.broadcast();

  SherpaASRStatus _status = SherpaASRStatus.notInitialized;
  bool _isInitialized = false;
  bool _isListening = false;
  StreamSubscription<Uint8List>? _audioStreamSubscription;
  StreamSubscription<AsrIsolateEvent>? _processorSubscription;

  static const int _sampleRate = 16000;
  static const int _maxPendingAudioChunks = 8;

  final List<Uint8List> _pendingAudio = [];
  bool _isDrainingAudio = false;

  Stream<ASRResult> get resultStream => _resultController.stream;
  Stream<SherpaASRStatus> get statusStream => _statusController.stream;
  Stream<String> get errorStream => _errorController.stream;
  Stream<double> get progressStream => _progressController.stream;
  SherpaASRStatus get status => _status;
  bool get isAvailable => _isInitialized;
  bool get isListening => _isListening;

  SherpaASRService();

  ASRModelConfig _getConfig() {
    final settingsBox = Hive.box('settings');
    final configJson = settingsBox.get('asr_model_config');
    if (configJson != null) {
      try {
        return ASRModelConfig.fromJson(
            Map<String, dynamic>.from(configJson as Map));
      } catch (e) {
        _logger.e('Failed to parse ASR model config: $e');
      }
    }
    return ASRModelConfig();
  }

  Future<String> get _modelDirectory async {
    final appDir = await getApplicationSupportDirectory();
    return '${appDir.path}/asr_model';
  }

  void _listenToProcessor() {
    _processorSubscription?.cancel();
    _processorSubscription = _processor.events.listen((event) {
      switch (event) {
        case AsrProgressEvent():
          _progressController.add(event.progress);
        case AsrResultEvent():
          final asrResult = ASRResult(
            text: event.text,
            confidence: 1.0,
            isFinal: true,
            timestamp: DateTime.now(),
          );
          _resultController.add(asrResult);
          _logger.i('Recognized: ${event.text}');
        case AsrErrorEvent():
          _errorController.add(event.message);
        case AsrInitResultEvent():
          if (!event.success && event.error != null) {
            _errorController.add(event.error!);
          }
        case AsrFlushDoneEvent():
          break;
      }
    });
  }

  Future<bool> initialize() async {
    if (_isInitialized) return true;

    try {
      _updateStatus(SherpaASRStatus.loadingModel);
      _progressController.add(0.0);

      final config = _getConfig();
      final modelDir = config.modelDir ?? await _modelDirectory;
      final vadPath = config.vadModelPath ?? '$modelDir/silero_vad.onnx';

      if (!await File(vadPath).exists()) {
        _updateStatus(SherpaASRStatus.error);
        _errorController.add(
          'VAD模型文件缺失: silero_vad.onnx。请在"模型管理"中下载VAD模型。',
        );
        return false;
      }

      _listenToProcessor();

      final success = await _processor.start(
        modelDir: modelDir,
        vadPath: vadPath,
        modelType: config.modelType,
      );

      if (!success) {
        _updateStatus(SherpaASRStatus.error);
        return false;
      }

      _isInitialized = true;
      _updateStatus(SherpaASRStatus.initialized);
      _logger.i('${config.modelTypeLabel} initialized successfully from $modelDir');
      return true;
    } catch (e) {
      _logger.e('Failed to initialize ASR: $e');
      _updateStatus(SherpaASRStatus.error);
      _errorController.add('初始化失败: $e');
      return false;
    }
  }

  void processAudioData(Uint8List audioData) {
    if (!_isInitialized || !_isListening) return;

    if (_pendingAudio.length >= _maxPendingAudioChunks) {
      _pendingAudio.removeAt(0);
    }
    _pendingAudio.add(audioData);
    _drainAudioQueue();
  }

  void _drainAudioQueue() {
    if (_isDrainingAudio || _pendingAudio.isEmpty) return;

    _isDrainingAudio = true;
    Future<void>(() async {
      while (_pendingAudio.isNotEmpty && _isListening) {
        final chunk = _pendingAudio.removeAt(0);
        _processor.sendAudio(chunk);
        await Future<void>.delayed(Duration.zero);
      }
      _isDrainingAudio = false;

      if (_pendingAudio.isNotEmpty) {
        _drainAudioQueue();
      }
    });
  }

  Future<void> startListening() async {
    if (!_isInitialized) {
      _updateStatus(SherpaASRStatus.error);
      _errorController.add('ASR未初始化，请先在"设置"中连接ASR服务，确保已下载模型');
      return;
    }

    try {
      if (await _audioRecorder.hasPermission()) {
        _processor.reset();
        _pendingAudio.clear();

        final stream = await _audioRecorder.startStream(const RecordConfig(
          encoder: AudioEncoder.pcm16bits,
          sampleRate: _sampleRate,
          numChannels: 1,
        ));

        _audioStreamSubscription = stream.listen((audioData) {
          processAudioData(audioData);
        }, onError: (error) {
          _logger.e('Audio stream error: $error');
          _errorController.add('音频流错误: $error');
        });

        _isListening = true;
        _updateStatus(SherpaASRStatus.listening);
        _logger.i('Started listening with ASR + VAD');
      } else {
        _errorController.add('没有麦克风权限');
        _logger.e('Microphone permission not granted');
      }
    } catch (e) {
      _logger.e('Failed to start recording: $e');
      _errorController.add('启动录音失败: $e');
    }
  }

  Future<void> stopListening() async {
    try {
      _isListening = false;
      _pendingAudio.clear();

      await _audioStreamSubscription?.cancel();
      _audioStreamSubscription = null;

      if (await _audioRecorder.isRecording()) {
        await _audioRecorder.stop();
      }

      if (_isInitialized) {
        await _processor.flush();
      }

      _updateStatus(SherpaASRStatus.notListening);
      _logger.i('Stopped listening');
    } catch (e) {
      _logger.e('Failed to stop recording: $e');
      _errorController.add('停止录音失败: $e');
    }
  }

  void _updateStatus(SherpaASRStatus newStatus) {
    if (_status != newStatus) {
      _status = newStatus;
      _statusController.add(_status);
    }
  }

  void dispose() {
    _audioStreamSubscription?.cancel();
    _processorSubscription?.cancel();
    _audioRecorder.dispose();
    _processor.dispose();
    _resultController.close();
    _statusController.close();
    _errorController.close();
    _progressController.close();
  }
}
