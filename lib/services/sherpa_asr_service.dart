import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa;
import 'package:path_provider/path_provider.dart';
import 'package:hive/hive.dart';
import 'package:logger/logger.dart';
import 'package:record/record.dart';
import '../models/asr_result.dart';
import '../models/asr_model_config.dart';

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

  sherpa.OfflineRecognizer? _recognizer;
  sherpa.VoiceActivityDetector? _vad;
  sherpa.CircularBuffer? _buffer;

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

  static const int _sampleRate = 16000;
  static const int _vadWindowSize = 512;

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
    return '${appDir.path}/qwen3_asr_model';
  }

  Future<bool> initialize() async {
    if (_isInitialized) return true;

    try {
      _updateStatus(SherpaASRStatus.loadingModel);
      _progressController.add(0.0);

      sherpa.initBindings();

      final config = _getConfig();
      final modelDir = config.modelDir ?? await _modelDirectory;

      final convFrontendPath = '$modelDir/conv_frontend.onnx';
      final encoderPath = '$modelDir/encoder.int8.onnx';
      final decoderPath = '$modelDir/decoder.int8.onnx';
      final tokenizerPath = '$modelDir/tokenizer';

      final missingFiles = <String>[];
      if (!await File(convFrontendPath).exists()) {
        missingFiles.add('conv_frontend.onnx');
      }
      if (!await File(encoderPath).exists()) {
        missingFiles.add('encoder.int8.onnx');
      }
      if (!await File(decoderPath).exists()) {
        missingFiles.add('decoder.int8.onnx');
      }
      if (!await File('$tokenizerPath/merges.txt').exists()) {
        missingFiles.add('tokenizer/merges.txt');
      }
      if (!await File('$tokenizerPath/vocab.json').exists()) {
        missingFiles.add('tokenizer/vocab.json');
      }

      if (missingFiles.isNotEmpty) {
        _updateStatus(SherpaASRStatus.error);
        _errorController.add(
          'ASR模型文件缺失: ${missingFiles.join(', ')}。请在"模型管理"中下载ASR模型。',
        );
        return false;
      }

      _progressController.add(0.3);

      final vadPath = config.vadModelPath ?? '$modelDir/silero_vad.onnx';

      if (!await File(vadPath).exists()) {
        _updateStatus(SherpaASRStatus.error);
        _errorController.add(
          'VAD模型文件缺失: silero_vad.onnx。请在"模型管理"中下载VAD模型。',
        );
        return false;
      }

      _progressController.add(0.5);

      final vadConfig = sherpa.VadModelConfig(
        sileroVad: sherpa.SileroVadModelConfig(
          model: vadPath,
          threshold: 0.5,
          minSilenceDuration: 0.5,
          minSpeechDuration: 0.3,
          maxSpeechDuration: 20,
          windowSize: _vadWindowSize,
        ),
        sampleRate: _sampleRate,
        numThreads: 2,
        provider: 'cpu',
        debug: false,
      );

      _vad = sherpa.VoiceActivityDetector(
        config: vadConfig,
        bufferSizeInSeconds: 30,
      );

      _buffer = sherpa.CircularBuffer(capacity: 30 * _sampleRate);

      final modelConfig = sherpa.OfflineModelConfig(
        qwen3Asr: sherpa.OfflineQwen3AsrModelConfig(
          convFrontend: convFrontendPath,
          encoder: encoderPath,
          decoder: decoderPath,
          tokenizer: tokenizerPath,
          maxNewTokens: 512,
        ),
        tokens: '',
        numThreads: 4,
        provider: 'cpu',
        debug: false,
      );

      final recognizerConfig = sherpa.OfflineRecognizerConfig(
        model: modelConfig,
        decodingMethod: 'greedy_search',
      );

      _recognizer = sherpa.OfflineRecognizer(recognizerConfig);

      _progressController.add(1.0);
      _isInitialized = true;
      _updateStatus(SherpaASRStatus.initialized);
      _logger.i('Qwen3-ASR initialized successfully from $modelDir');

      return true;
    } catch (e) {
      _logger.e('Failed to initialize Qwen3-ASR: $e');
      _updateStatus(SherpaASRStatus.error);
      _errorController.add('初始化失败: $e');
      return false;
    }
  }

  Float32List _convertToFloat32(Uint8List audioData) {
    final samples = Float32List(audioData.length ~/ 2);
    for (var i = 0; i < samples.length; i++) {
      final low = audioData[i * 2];
      final high = audioData[i * 2 + 1];
      final sample = (low | (high << 8)).toSigned(16);
      samples[i] = sample / 32768.0;
    }
    return samples;
  }

  void processAudioData(Uint8List audioData) {
    if (!_isInitialized || _vad == null || _buffer == null || _recognizer == null) {
      return;
    }

    try {
      final samples = _convertToFloat32(audioData);

      _buffer!.push(samples);

      while (_buffer!.size >= _vadWindowSize) {
        final windowSamples = _buffer!.get(
          startIndex: _buffer!.head,
          n: _vadWindowSize,
        );
        _buffer!.pop(_vadWindowSize);

        _vad!.acceptWaveform(windowSamples);

        while (!_vad!.isEmpty()) {
          final segment = _vad!.front();
          final segmentSamples = segment.samples;

          if (segmentSamples.isNotEmpty) {
            final stream = _recognizer!.createStream();
            stream.acceptWaveform(samples: segmentSamples, sampleRate: _sampleRate);

            _recognizer!.decode(stream);
            final result = _recognizer!.getResult(stream);
            final text = result.text;

            if (text.isNotEmpty) {
              final asrResult = ASRResult(
                text: text,
                confidence: 1.0,
                isFinal: true,
                timestamp: DateTime.now(),
              );

              _resultController.add(asrResult);
              _logger.i('Recognized: $text');
            }

            stream.free();
          }

          _vad!.pop();
        }
      }
    } catch (e) {
      _logger.e('Error processing audio: $e');
      _errorController.add('处理音频失败: $e');
    }
  }

  Future<void> startListening() async {
    if (!_isInitialized) {
      _updateStatus(SherpaASRStatus.error);
      _errorController.add('ASR未初始化，请先在"设置"中连接ASR服务，确保已下载模型');
      return;
    }

    try {
      if (await _audioRecorder.hasPermission()) {
        _vad?.reset();
        _buffer?.reset();

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
        _logger.i('Started listening with Qwen3-ASR + VAD');
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

      await _audioStreamSubscription?.cancel();
      _audioStreamSubscription = null;

      if (await _audioRecorder.isRecording()) {
        await _audioRecorder.stop();
      }

      if (_vad != null && _recognizer != null) {
        _vad!.flush();

        while (!_vad!.isEmpty()) {
          final segment = _vad!.front();
          final segmentSamples = segment.samples;

          if (segmentSamples.isNotEmpty) {
            final stream = _recognizer!.createStream();
            stream.acceptWaveform(samples: segmentSamples, sampleRate: _sampleRate);

            _recognizer!.decode(stream);
            final result = _recognizer!.getResult(stream);
            final text = result.text;

            if (text.isNotEmpty) {
              final asrResult = ASRResult(
                text: text,
                confidence: 1.0,
                isFinal: true,
                timestamp: DateTime.now(),
              );

              _resultController.add(asrResult);
              _logger.i('Final recognized: $text');
            }

            stream.free();
          }

          _vad!.pop();
        }
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
    _audioRecorder.dispose();
    _vad?.free();
    _buffer?.free();
    _recognizer?.free();
    _resultController.close();
    _statusController.close();
    _errorController.close();
    _progressController.close();
  }
}
