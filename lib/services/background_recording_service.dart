import 'dart:async';
import 'dart:io';
import 'dart:ui';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

class BackgroundRecordingService {
  static final BackgroundRecordingService _instance = BackgroundRecordingService._internal();
  factory BackgroundRecordingService() => _instance;
  BackgroundRecordingService._internal();

  static const String _notificationChannelId = 'classhelper_recording';
  static const int _notificationId = 888;

  final FlutterLocalNotificationsPlugin _notifications = FlutterLocalNotificationsPlugin();
  final StreamController<String> _resultController = StreamController<String>.broadcast();
  final StreamController<bool> _recordingStateController = StreamController<bool>.broadcast();

  Stream<String> get resultStream => _resultController.stream;
  Stream<bool> get recordingStateStream => _recordingStateController.stream;

  bool _isInitialized = false;
  bool _isRecording = false;

  bool get isRecording => _isRecording;

  Future<void> initialize() async {
    if (_isInitialized) return;

    if (Platform.isAndroid) {
      await _requestPermissions();
      await _initializeNotifications();
      await _configureBackgroundService();
    }

    _isInitialized = true;
  }

  Future<void> _requestPermissions() async {
    await Permission.microphone.request();
    await Permission.notification.request();
    
    if (Platform.isAndroid) {
      await Permission.ignoreBatteryOptimizations.request();
    }
  }

  Future<void> _initializeNotifications() async {
    const androidSettings = AndroidInitializationSettings('@mipmap/ic_launcher');
    const iosSettings = DarwinInitializationSettings();
    
    const initSettings = InitializationSettings(
      android: androidSettings,
      iOS: iosSettings,
    );

    await _notifications.initialize(initSettings);

    const channel = AndroidNotificationChannel(
      _notificationChannelId,
      '录音服务',
      description: '后台录音服务通知',
      importance: Importance.low,
      playSound: false,
    );

    await _notifications.resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(channel);
  }

  Future<void> _configureBackgroundService() async {
    final service = FlutterBackgroundService();

    await service.configure(
      androidConfiguration: AndroidConfiguration(
        onStart: _onStart,
        autoStart: false,
        isForegroundMode: true,
        foregroundServiceNotificationId: _notificationId,
        notificationChannelId: _notificationChannelId,
        initialNotificationTitle: '智能课堂助手',
        initialNotificationContent: '正在录音中...',
      ),
      iosConfiguration: IosConfiguration(
        autoStart: false,
        onForeground: _onStart,
        onBackground: _onIosBackground,
      ),
    );
  }

  @pragma('vm:entry-point')
  static Future<void> _onStart(ServiceInstance service) async {
    DartPluginRegistrant.ensureInitialized();

    if (service is AndroidServiceInstance) {
      service.on('setAsForeground').listen((event) {
        service.setAsForegroundService();
      });

      service.on('setAsBackground').listen((event) {
        service.setAsBackgroundService();
      });
    }

    service.on('stopService').listen((event) {
      service.stopSelf();
    });

    service.on('startRecording').listen((event) async {
      await WakelockPlus.enable();
    });

    service.on('stopRecording').listen((event) async {
      await WakelockPlus.disable();
    });
  }

  @pragma('vm:entry-point')
  static Future<bool> _onIosBackground(ServiceInstance service) async {
    return true;
  }

  Future<void> startBackgroundRecording() async {
    if (_isRecording) return;

    await initialize();

    final service = FlutterBackgroundService();
    await service.startService();
    
    _isRecording = true;
    _recordingStateController.add(true);

    await WakelockPlus.enable();
  }

  Future<void> stopBackgroundRecording() async {
    if (!_isRecording) return;

    final service = FlutterBackgroundService();
    service.invoke('stopService');
    
    _isRecording = false;
    _recordingStateController.add(false);

    await WakelockPlus.disable();
  }

  void addResult(String text) {
    _resultController.add(text);
  }

  Future<void> updateNotification({required String title, required String content}) async {
    await _notifications.show(
      _notificationId,
      title,
      content,
      const NotificationDetails(
        android: AndroidNotificationDetails(
          _notificationChannelId,
          '录音服务',
          importance: Importance.low,
          priority: Priority.low,
          showProgress: false,
        ),
      ),
    );
  }

  void dispose() {
    _resultController.close();
    _recordingStateController.close();
  }
}
