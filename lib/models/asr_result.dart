class ASRResult {
  final String text;
  final double confidence;
  final DateTime timestamp;
  final bool isFinal;
  final int? startTime;
  final int? endTime;
  final String? speaker;

  ASRResult({
    required this.text,
    this.confidence = 1.0,
    DateTime? timestamp,
    this.isFinal = false,
    this.startTime,
    this.endTime,
    this.speaker,
  }) : timestamp = timestamp ?? DateTime.now();

  factory ASRResult.fromJson(Map<String, dynamic> json) {
    return ASRResult(
      text: json['text'] ?? '',
      confidence: (json['confidence'] ?? 1.0).toDouble(),
      timestamp: DateTime.now(),
      isFinal: json['is_final'] ?? false,
      startTime: json['start_time'],
      endTime: json['end_time'],
      speaker: json['speaker'],
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'text': text,
      'confidence': confidence,
      'is_final': isFinal,
      'start_time': startTime,
      'end_time': endTime,
      'speaker': speaker,
    };
  }
}

enum ASRStatus {
  disconnected,
  connecting,
  connected,
  listening,
  error,
}

class ASRConfig {
  final String host;
  final int port;
  final String mode;
  final List<int> chunkSize;
  final int chunkInterval;
  final String wavFormat;

  ASRConfig({
    this.host = 'localhost',
    this.port = 10095,
    this.mode = '2pass',
    this.chunkSize = const [5, 10, 5],
    this.chunkInterval = 10,
    this.wavFormat = 'pcm',
  });

  String get wsUrl => 'ws://$host:$port';

  Map<String, dynamic> toJson() {
    return {
      'mode': mode,
      'chunk_size': chunkSize,
      'chunk_interval': chunkInterval,
      'wav_format': wavFormat,
    };
  }
}
