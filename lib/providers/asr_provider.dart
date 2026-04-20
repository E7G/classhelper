import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:intl/intl.dart';
import 'package:wakelock_plus/wakelock_plus.dart';
import '../models/asr_result.dart';
import '../services/unified_asr_service.dart';
import '../services/background_recording_service.dart';

class ASRProvider extends ChangeNotifier {
  final UnifiedASRService _unifiedASR = UnifiedASRService();
  final BackgroundRecordingService _backgroundService = BackgroundRecordingService();

  ASRStatus _status = ASRStatus.disconnected;
  String _currentText = '';
  final List<ASRResult> _results = [];
  String? _errorMessage;
  bool _isRecording = false;
  double _loadProgress = 0.0;
  bool _backgroundMode = false;

  ASRStatus get status => _status;
  String get currentText => _currentText;
  List<ASRResult> get results => List.unmodifiable(_results);
  String? get errorMessage => _errorMessage;
  bool get isRecording => _isRecording;
  double get loadProgress => _loadProgress;
  bool get isConnected => _status == ASRStatus.connected || _status == ASRStatus.listening;
  bool get isListening => _status == ASRStatus.listening;
  bool get isLoading => _status == ASRStatus.connecting;
  bool get backgroundMode => _backgroundMode;

  ASRProvider() {
    _init();
  }

  void _init() {
    _unifiedASR.statusStream.listen((status) {
      _status = status;
      notifyListeners();
    });

    _unifiedASR.resultStream.listen((result) {
      if (result.isFinal) {
        if (_isValidResult(result.text)) {
          _results.add(result);
          if (_backgroundMode) {
            _backgroundService.updateNotification(
              title: '智能课堂助手',
              content: '已识别 ${_results.length} 条',
            );
          }
        }
        _currentText = '';
      } else {
        _currentText = result.text;
      }
      notifyListeners();
    });

    _unifiedASR.errorStream.listen((error) {
      _errorMessage = error;
      notifyListeners();
    });

    _unifiedASR.progressStream.listen((progress) {
      _loadProgress = progress;
      notifyListeners();
    });
  }

  bool _isValidResult(String text) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return false;
    if (trimmed.length < 2) return false;
    
    final shortIgnorePatterns = [
      RegExp(r'^[.。,，!！?？;；:：]+$'),
      RegExp(r'^(So|Yeah|But|Yes|Oh|To|No|I|We|He|She|It|They|And|Or|But|If|Of|At|In|On|Up|Go|Do|Be|Is|Am|Are|Was|Were)[.!?]?$'),
      RegExp(r'^[.。]$'),
    ];
    
    for (final pattern in shortIgnorePatterns) {
      if (pattern.hasMatch(trimmed)) {
        return false;
      }
    }
    
    return true;
  }

  Future<void> connect({String localeId = 'zh_CN'}) async {
    try {
      _errorMessage = null;
      _unifiedASR.configureLocal(localeId);
      await _unifiedASR.connect();
    } catch (e) {
      _errorMessage = '连接失败: $e';
      notifyListeners();
      rethrow;
    }
  }

  void configureRemoteASR({required String apiUrl, String? apiKey}) {
    _unifiedASR.configureRemote(apiUrl: apiUrl, apiKey: apiKey);
    _unifiedASR.setMode(ASRMode.remote);
  }

  void configureLocalASR({String localeId = 'zh_CN'}) {
    _unifiedASR.configureLocal(localeId);
    _unifiedASR.setMode(ASRMode.local);
  }

  void setBackgroundMode(bool enabled) {
    _backgroundMode = enabled;
    notifyListeners();
  }

  Future<void> startRecording() async {
    if (_status != ASRStatus.connected) {
      _errorMessage = '未连接到ASR服务';
      notifyListeners();
      return;
    }

    try {
      final hasPermission = await Permission.microphone.request();
      if (!hasPermission.isGranted) {
        _errorMessage = '需要麦克风权限';
        notifyListeners();
        return;
      }

      if (_backgroundMode && Platform.isAndroid) {
        await Permission.notification.request();
        await _backgroundService.initialize();
        await _backgroundService.startBackgroundRecording();
      }

      _isRecording = true;
      notifyListeners();

      await WakelockPlus.enable();
      await _unifiedASR.startListening();
    } catch (e) {
      _errorMessage = '启动录音失败: $e';
      _isRecording = false;
      await WakelockPlus.disable();
      if (_backgroundMode) {
        await _backgroundService.stopBackgroundRecording();
      }
      notifyListeners();
      rethrow;
    }
  }

  Future<void> stopRecording() async {
    if (!_isRecording) return;

    try {
      await _unifiedASR.stopListening();
      _isRecording = false;
      
      if (_backgroundMode && Platform.isAndroid) {
        await _backgroundService.stopBackgroundRecording();
      }
      
      await WakelockPlus.disable();
      notifyListeners();
    } catch (e) {
      _errorMessage = '停止录音失败: $e';
      notifyListeners();
      rethrow;
    }
  }

  Future<void> disconnect() async {
    await stopRecording();
    await _unifiedASR.disconnect();
    _currentText = '';
    notifyListeners();
  }

  void clearResults() {
    _results.clear();
    _currentText = '';
    notifyListeners();
  }

  void deleteResult(int index) {
    if (index >= 0 && index < _results.length) {
      _results.removeAt(index);
      notifyListeners();
    }
  }

  void deleteResultByTimestamp(DateTime timestamp) {
    _results.removeWhere((r) => r.timestamp == timestamp);
    notifyListeners();
  }

  void editResult(int index, String newText) {
    if (index >= 0 && index < _results.length) {
      final oldResult = _results[index];
      _results[index] = ASRResult(
        text: newText,
        confidence: oldResult.confidence,
        isFinal: oldResult.isFinal,
        timestamp: oldResult.timestamp,
      );
      notifyListeners();
    }
  }

  void editResultByTimestamp(DateTime timestamp, String newText) {
    final index = _results.indexWhere((r) => r.timestamp == timestamp);
    if (index != -1) {
      editResult(index, newText);
    }
  }

  String getFullText() {
    final texts = _results.map((r) => r.text).toList();
    if (_currentText.isNotEmpty) {
      texts.add(_currentText);
    }
    return texts.join(' ');
  }

  Map<String, dynamic> getExportData() {
    return {
      'export_time': DateTime.now().toIso8601String(),
      'total_results': _results.length,
      'results': _results.map((r) {
        return {
          'text': r.text,
          'confidence': r.confidence,
          'timestamp': r.timestamp.toIso8601String(),
          'is_final': r.isFinal,
          'start_time': r.startTime,
          'end_time': r.endTime,
          'speaker': r.speaker,
        };
      }).toList(),
      'full_text': getFullText(),
    };
  }

  String getExportText() {
    final buffer = StringBuffer();
    final dateFormat = DateFormat('yyyy-MM-dd HH:mm:ss');
    
    buffer.writeln('ASR识别结果导出');
    buffer.writeln('导出时间: ${dateFormat.format(DateTime.now())}');
    buffer.writeln('识别结果数: ${_results.length}');
    buffer.writeln('');
    buffer.writeln('--- 完整文本 ---');
    buffer.writeln(getFullText());
    buffer.writeln('');
    buffer.writeln('--- 详细结果 ---');
    
    for (int i = 0; i < _results.length; i++) {
      final r = _results[i];
      buffer.writeln('');
      buffer.writeln('[${i + 1}] ${dateFormat.format(r.timestamp)}');
      buffer.writeln('文本: ${r.text}');
      buffer.writeln('置信度: ${(r.confidence * 100).toStringAsFixed(1)}%');
      if (r.startTime != null && r.endTime != null) {
        buffer.writeln('时间: ${r.startTime}ms - ${r.endTime}ms');
      }
      if (r.speaker != null) {
        buffer.writeln('说话人: ${r.speaker}');
      }
    }
    
    return buffer.toString();
  }

  Future<String?> exportToText() async {
    if (_results.isEmpty) return null;
    
    try {
      final directory = await getApplicationDocumentsDirectory();
      final timestamp = DateFormat('yyyyMMdd_HHmmss').format(DateTime.now());
      final fileName = 'asr_export_$timestamp.txt';
      final file = File('${directory.path}/$fileName');
      
      await file.writeAsString(getExportText());
      
      return file.path;
    } catch (e) {
      debugPrint('Export to text failed: $e');
      return null;
    }
  }

  Future<String?> exportToJson() async {
    if (_results.isEmpty) return null;
    
    try {
      final directory = await getApplicationDocumentsDirectory();
      final timestamp = DateFormat('yyyyMMdd_HHmmss').format(DateTime.now());
      final fileName = 'asr_export_$timestamp.json';
      final file = File('${directory.path}/$fileName');
      
      final jsonData = getExportData();
      await file.writeAsString(
        JsonEncoder.withIndent('  ').convert(jsonData)
      );
      
      return file.path;
    } catch (e) {
      debugPrint('Export to JSON failed: $e');
      return null;
    }
  }

  Future<void> shareAsText() async {
    if (_results.isEmpty) return;
    
    try {
      final path = await exportToText();
      if (path != null) {
        await Share.shareXFiles(
          [XFile(path)],
          subject: 'ASR识别结果',
        );
      }
    } catch (e) {
      debugPrint('Share failed: $e');
    }
  }

  Future<void> shareAsJson() async {
    if (_results.isEmpty) return;
    
    try {
      final path = await exportToJson();
      if (path != null) {
        await Share.shareXFiles(
          [XFile(path)],
          subject: 'ASR识别结果 (JSON)',
        );
      }
    } catch (e) {
      debugPrint('Share failed: $e');
    }
  }

  Future<List<String>> getAvailableLocales() async {
    return await _unifiedASR.getAvailableLocales();
  }

  @override
  void dispose() {
    _unifiedASR.dispose();
    super.dispose();
  }
}
