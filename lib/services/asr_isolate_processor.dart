import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';
import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa;
import '../models/asr_model_config.dart';

/// Commands sent from the main isolate to the ASR worker isolate.
sealed class AsrIsolateCommand {}

class AsrInitCommand extends AsrIsolateCommand {
  final String modelDir;
  final String vadPath;
  final ASRModelType modelType;

  AsrInitCommand({
    required this.modelDir,
    required this.vadPath,
    required this.modelType,
  });
}

class AsrAudioCommand extends AsrIsolateCommand {
  final Uint8List data;

  AsrAudioCommand(this.data);
}

class AsrFlushCommand extends AsrIsolateCommand {}

class AsrResetCommand extends AsrIsolateCommand {}

class AsrDisposeCommand extends AsrIsolateCommand {}

/// Events sent from the ASR worker isolate to the main isolate.
sealed class AsrIsolateEvent {}

class AsrProgressEvent extends AsrIsolateEvent {
  final double progress;

  AsrProgressEvent(this.progress);
}

class AsrInitResultEvent extends AsrIsolateEvent {
  final bool success;
  final String? error;

  AsrInitResultEvent({required this.success, this.error});
}

class AsrResultEvent extends AsrIsolateEvent {
  final String text;

  AsrResultEvent(this.text);
}

class AsrErrorEvent extends AsrIsolateEvent {
  final String message;

  AsrErrorEvent(this.message);
}

class AsrFlushDoneEvent extends AsrIsolateEvent {}

class AsrIsolateProcessor {
  Isolate? _isolate;
  SendPort? _workerSendPort;
  ReceivePort? _receivePort;
  StreamSubscription? _subscription;

  final StreamController<AsrIsolateEvent> _eventController =
      StreamController<AsrIsolateEvent>.broadcast();

  Stream<AsrIsolateEvent> get events => _eventController.stream;

  bool get isRunning => _isolate != null;

  Future<bool> start({
    required String modelDir,
    required String vadPath,
    required ASRModelType modelType,
  }) async {
    if (_isolate != null) {
      return true;
    }

    _receivePort = ReceivePort();
    final initCompleter = Completer<bool>();

    _subscription = _receivePort!.listen((message) {
      if (message is SendPort) {
        _workerSendPort = message;
        _workerSendPort!.send(
          AsrInitCommand(
            modelDir: modelDir,
            vadPath: vadPath,
            modelType: modelType,
          ),
        );
        return;
      }

      if (message is! AsrIsolateEvent) return;

      _eventController.add(message);

      if (message is AsrInitResultEvent && !initCompleter.isCompleted) {
        initCompleter.complete(message.success);
      }
    });

    _isolate = await Isolate.spawn(
      _asrIsolateEntry,
      _receivePort!.sendPort,
      debugName: 'asr_worker',
    );

    return initCompleter.future.timeout(
      const Duration(minutes: 2),
      onTimeout: () {
        _eventController.add(AsrErrorEvent('ASR初始化超时'));
        return false;
      },
    );
  }

  void sendAudio(Uint8List data) {
    _workerSendPort?.send(AsrAudioCommand(data));
  }

  void reset() {
    _workerSendPort?.send(AsrResetCommand());
  }

  Future<void> flush() async {
    if (_workerSendPort == null) return;

    final completer = Completer<void>();
    late StreamSubscription<AsrIsolateEvent> sub;

    sub = events.listen((event) {
      if (event is AsrFlushDoneEvent) {
        sub.cancel();
        if (!completer.isCompleted) {
          completer.complete();
        }
      }
    });

    _workerSendPort!.send(AsrFlushCommand());
    await completer.future.timeout(
      const Duration(seconds: 10),
      onTimeout: () {},
    );
    await sub.cancel();
  }

  Future<void> dispose() async {
    _workerSendPort?.send(AsrDisposeCommand());
    await _subscription?.cancel();
    _receivePort?.close();
    _isolate?.kill(priority: Isolate.immediate);
    _isolate = null;
    _workerSendPort = null;
    await _eventController.close();
  }
}

void _asrIsolateEntry(SendPort mainSendPort) {
  final receivePort = ReceivePort();
  mainSendPort.send(receivePort.sendPort);

  final worker = _AsrWorker(mainSendPort);
  receivePort.listen((message) {
    if (message is! AsrIsolateCommand) return;

    switch (message) {
      case AsrInitCommand():
        worker.initialize(message);
      case AsrAudioCommand():
        worker.processAudio(message.data);
      case AsrFlushCommand():
        worker.flush();
      case AsrResetCommand():
        worker.reset();
      case AsrDisposeCommand():
        worker.dispose();
        receivePort.close();
        Isolate.exit();
    }
  });
}

class _AsrWorker {
  final SendPort _mainSendPort;

  sherpa.OfflineRecognizer? _recognizer;
  sherpa.VoiceActivityDetector? _vad;
  sherpa.CircularBuffer? _buffer;

  static const int _sampleRate = 16000;
  static const int _vadWindowSize = 512;

  _AsrWorker(this._mainSendPort);

  void initialize(AsrInitCommand command) {
    try {
      sherpa.initBindings();
      _mainSendPort.send(AsrProgressEvent(0.0));

      final vadConfig = sherpa.VadModelConfig(
        sileroVad: sherpa.SileroVadModelConfig(
          model: command.vadPath,
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
      _mainSendPort.send(AsrProgressEvent(0.5));

      final modelConfig = _buildModelConfig(command);
      if (modelConfig == null) {
        return;
      }

      _recognizer = sherpa.OfflineRecognizer(
        sherpa.OfflineRecognizerConfig(
          model: modelConfig,
          decodingMethod: 'greedy_search',
        ),
      );

      _mainSendPort.send(AsrProgressEvent(1.0));
      _mainSendPort.send(AsrInitResultEvent(success: true));
    } catch (e) {
      _mainSendPort.send(
        AsrInitResultEvent(success: false, error: '初始化失败: $e'),
      );
    }
  }

  sherpa.OfflineModelConfig? _buildModelConfig(AsrInitCommand command) {
    final modelDir = command.modelDir;

    switch (command.modelType) {
      case ASRModelType.senseVoice:
        final modelPathQ8 = '$modelDir/model_q8.onnx';
        final modelPathInt8 = '$modelDir/model.int8.onnx';
        String? modelPath;

        if (File(modelPathQ8).existsSync()) {
          modelPath = modelPathQ8;
        } else if (File(modelPathInt8).existsSync()) {
          modelPath = modelPathInt8;
        }

        if (modelPath == null) {
          _mainSendPort.send(
            AsrInitResultEvent(
              success: false,
              error: 'ASR模型文件缺失: model_q8.onnx 或 model.int8.onnx',
            ),
          );
          return null;
        }

        final tokensPath = '$modelDir/tokens.txt';
        if (!File(tokensPath).existsSync()) {
          _mainSendPort.send(
            AsrInitResultEvent(
              success: false,
              error: 'ASR模型文件缺失: tokens.txt',
            ),
          );
          return null;
        }

        return sherpa.OfflineModelConfig(
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

      case ASRModelType.qwen3Asr:
        final convFrontendPath = '$modelDir/conv_frontend.onnx';
        final encoderPath = '$modelDir/encoder.int8.onnx';
        final decoderPath = '$modelDir/decoder.int8.onnx';
        final tokenizerPath = '$modelDir/tokenizer';

        final missingFiles = <String>[];
        if (!File(convFrontendPath).existsSync()) {
          missingFiles.add('conv_frontend.onnx');
        }
        if (!File(encoderPath).existsSync()) {
          missingFiles.add('encoder.int8.onnx');
        }
        if (!File(decoderPath).existsSync()) {
          missingFiles.add('decoder.int8.onnx');
        }
        if (!File('$tokenizerPath/merges.txt').existsSync()) {
          missingFiles.add('tokenizer/merges.txt');
        }
        if (!File('$tokenizerPath/vocab.json').existsSync()) {
          missingFiles.add('tokenizer/vocab.json');
        }

        if (missingFiles.isNotEmpty) {
          _mainSendPort.send(
            AsrInitResultEvent(
              success: false,
              error: 'ASR模型文件缺失: ${missingFiles.join(', ')}',
            ),
          );
          return null;
        }

        return sherpa.OfflineModelConfig(
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
    }
  }

  void reset() {
    _vad?.reset();
    _buffer?.reset();
  }

  void processAudio(Uint8List audioData) {
    if (_vad == null || _buffer == null || _recognizer == null) return;

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
        _decodeVadSegments();
      }
    } catch (e) {
      _mainSendPort.send(AsrErrorEvent('处理音频失败: $e'));
    }
  }

  void flush() {
    if (_vad == null || _recognizer == null) {
      _mainSendPort.send(AsrFlushDoneEvent());
      return;
    }

    try {
      _vad!.flush();
      _decodeVadSegments();
    } catch (e) {
      _mainSendPort.send(AsrErrorEvent('刷新音频失败: $e'));
    } finally {
      _mainSendPort.send(AsrFlushDoneEvent());
    }
  }

  void _decodeVadSegments() {
    while (!_vad!.isEmpty()) {
      final segment = _vad!.front();
      final segmentSamples = segment.samples;

      if (segmentSamples.isNotEmpty) {
        final stream = _recognizer!.createStream();
        stream.acceptWaveform(
          samples: segmentSamples,
          sampleRate: _sampleRate,
        );

        _recognizer!.decode(stream);
        final text = _recognizer!.getResult(stream).text;
        stream.free();

        if (text.isNotEmpty) {
          _mainSendPort.send(AsrResultEvent(text));
        }
      }

      _vad!.pop();
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

  void dispose() {
    _vad?.free();
    _buffer?.free();
    _recognizer?.free();
    _vad = null;
    _buffer = null;
    _recognizer = null;
  }
}
