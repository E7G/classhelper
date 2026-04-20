import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa;
import 'package:path_provider/path_provider.dart';
import 'package:flutter/services.dart';
import 'package:logger/logger.dart';
import 'package:record/record.dart';
import '../models/asr_result.dart';

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

  Future<String> get _modelDirectory async {
    final appDir = await getApplicationSupportDirectory();
    return '${appDir.path}/sensevoice_model';
  }

  Future<bool> initialize() async {
    if (_isInitialized) return true;
    
    try {
      _updateStatus(SherpaASRStatus.loadingModel);
      _progressController.add(0.0);
      
      sherpa.initBindings();
      
      final modelDir = await _modelDirectory;
      final modelPath = '$modelDir/model.int8.onnx';
      final tokensPath = '$modelDir/tokens.txt';
      final vadPath = '$modelDir/silero_vad.onnx';
      
      if (!await File(modelPath).exists()) {
        _progressController.add(0.1);
        await _copyModelFromAssets(modelDir);
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
        senseVoice: sherpa.OfflineSenseVoiceModelConfig(
          model: modelPath,
          language: 'auto',
          useInverseTextNormalization: true,
        ),
        tokens: tokensPath,
        numThreads: 4,
        provider: 'cpu',
        debug: false,
      );
      
      final config = sherpa.OfflineRecognizerConfig(
        model: modelConfig,
        decodingMethod: 'greedy_search',
      );
      
      _recognizer = sherpa.OfflineRecognizer(config);
      
      _progressController.add(1.0);
      _isInitialized = true;
      _updateStatus(SherpaASRStatus.initialized);
      _logger.i('SenseVoice initialized successfully');
      
      return true;
    } catch (e) {
      _logger.e('Failed to initialize SenseVoice: $e');
      _updateStatus(SherpaASRStatus.error);
      _errorController.add('初始化失败: $e');
      return false;
    }
  }

  Future<void> _copyModelFromAssets(String targetDir) async {
    final dir = Directory(targetDir);
    if (!await dir.exists()) {
      await dir.create(recursive: true);
    }

    final files = [
      'model.int8.onnx',
      'tokens.txt',
      'silero_vad.onnx',
    ];

    for (final fileName in files) {
      try {
        final data = await rootBundle.load('assets/models/sensevoice/$fileName');
        final bytes = data.buffer.asUint8List();
        final file = File('$targetDir/$fileName');
        await file.writeAsBytes(bytes);
        _logger.i('Copied $fileName to $targetDir');
      } catch (e) {
        _logger.e('Failed to copy $fileName: $e');
        rethrow;
      }
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
      _errorController.add('ASR未初始化');
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
        _logger.i('Started listening with SenseVoice + VAD');
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
