import 'dart:async';
import 'dart:typed_data';
import 'package:logger/logger.dart';
import '../models/asr_result.dart';
import 'sherpa_asr_service.dart';
import 'remote_asr_service.dart';

export '../models/asr_result.dart' show ASRStatus;

enum ASRMode {
  local,
  remote,
}

class UnifiedASRService {
  final Logger _logger = Logger();
  
  final SherpaASRService _localASR = SherpaASRService();
  final RemoteASRService _remoteASR = RemoteASRService();
  
  ASRMode _mode = ASRMode.local;
  
  final StreamController<ASRResult> _resultController = 
      StreamController<ASRResult>.broadcast();
  final StreamController<ASRStatus> _statusController = 
      StreamController<ASRStatus>.broadcast();
  final StreamController<String> _errorController = 
      StreamController<String>.broadcast();
  final StreamController<double> _progressController = 
      StreamController<double>.broadcast();
  
  ASRMode get mode => _mode;
  ASRStatus get status => _getCurrentStatus();
  Stream<ASRResult> get resultStream => _resultController.stream;
  Stream<ASRStatus> get statusStream => _statusController.stream;
  Stream<String> get errorStream => _errorController.stream;
  Stream<double> get progressStream => _progressController.stream;
  
  bool get isListening => _mode == ASRMode.local 
      ? _localASR.isListening 
      : _remoteASR.isListening;
  bool get isConnected => _mode == ASRMode.local 
      ? _localASR.isAvailable 
      : _remoteASR.isAvailable;

  UnifiedASRService() {
    _init();
    _logger.i('Unified ASR service initialized with local and remote support');
  }

  void _init() {
    _localASR.resultStream.listen((result) {
      if (_mode == ASRMode.local) {
        _resultController.add(result);
      }
    });
    
    _localASR.statusStream.listen((status) {
      if (_mode == ASRMode.local) {
        _statusController.add(_mapLocalStatus(status));
      }
    });
    
    _localASR.errorStream.listen((error) {
      if (_mode == ASRMode.local) {
        _errorController.add(error);
      }
    });
    
    _localASR.progressStream.listen((progress) {
      if (_mode == ASRMode.local) {
        _progressController.add(progress);
      }
    });
    
    _remoteASR.resultStream.listen((result) {
      if (_mode == ASRMode.remote) {
        _resultController.add(result);
      }
    });
    
    _remoteASR.statusStream.listen((status) {
      if (_mode == ASRMode.remote) {
        _statusController.add(_mapRemoteStatus(status));
      }
    });
    
    _remoteASR.errorStream.listen((error) {
      if (_mode == ASRMode.remote) {
        _errorController.add(error);
      }
    });
  }

  ASRStatus _mapLocalStatus(SherpaASRStatus status) {
    switch (status) {
      case SherpaASRStatus.notInitialized:
        return ASRStatus.disconnected;
      case SherpaASRStatus.loadingModel:
        return ASRStatus.connecting;
      case SherpaASRStatus.initialized:
        return ASRStatus.connected;
      case SherpaASRStatus.listening:
        return ASRStatus.listening;
      case SherpaASRStatus.notListening:
        return ASRStatus.connected;
      case SherpaASRStatus.error:
        return ASRStatus.error;
    }
  }

  ASRStatus _mapRemoteStatus(RemoteASRStatus status) {
    switch (status) {
      case RemoteASRStatus.disconnected:
        return ASRStatus.disconnected;
      case RemoteASRStatus.connected:
        return ASRStatus.connected;
      case RemoteASRStatus.listening:
        return ASRStatus.listening;
      case RemoteASRStatus.notListening:
        return ASRStatus.connected;
      case RemoteASRStatus.error:
        return ASRStatus.error;
    }
  }

  ASRStatus _getCurrentStatus() {
    return _mode == ASRMode.local 
        ? _mapLocalStatus(_localASR.status)
        : _mapRemoteStatus(_remoteASR.status);
  }

  void setMode(ASRMode mode) {
    _mode = mode;
    _logger.i('ASR mode changed to: $mode');
  }

  void configureLocal(String localeId) {
  }

  void configureRemote({required String apiUrl, String? apiKey}) {
    _remoteASR.configure(apiUrl: apiUrl, apiKey: apiKey);
  }

  Future<void> connect() async {
    if (_mode == ASRMode.local) {
      await _localASR.initialize();
    } else {
      await _remoteASR.connect();
    }
  }

  Future<void> startListening() async {
    if (_mode == ASRMode.local) {
      await _localASR.startListening();
    } else {
      await _remoteASR.startListening();
    }
  }

  Future<void> stopListening() async {
    if (_mode == ASRMode.local) {
      await _localASR.stopListening();
    } else {
      await _remoteASR.stopListening();
    }
  }

  Future<void> disconnect() async {
    if (_mode == ASRMode.local) {
      await _localASR.stopListening();
    } else {
      await _remoteASR.stopListening();
    }
  }

  Future<List<String>> getAvailableLocales() async {
    return ['中文 (zh_CN)'];
  }

  void processAudioData(List<int> audioData) {
    final uint8Data = Uint8List.fromList(audioData);
    if (_mode == ASRMode.local) {
      _localASR.processAudioData(uint8Data);
    } else {
      _remoteASR.processAudioData(uint8Data);
    }
  }

  void dispose() {
    _localASR.dispose();
    _remoteASR.dispose();
    _resultController.close();
    _statusController.close();
    _errorController.close();
    _progressController.close();
  }
}
