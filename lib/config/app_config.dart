class AppConfig {
  static const String appName = '智能课堂助手';
  static const String version = '1.0.0';
  
  static const String defaultFunASRHost = 'localhost';
  static const int defaultFunASRPort = 10095;
  
  static const String defaultLLMApiUrl = 'https://api.openai.com/v1';
  static const String defaultLLMModel = 'gpt-4';
  
  static const Duration asrReconnectDelay = Duration(seconds: 3);
  static const Duration questionDetectionDebounce = Duration(milliseconds: 500);
  
  static const List<String> questionKeywords = [
    '？', '?', '吗', '呢', '什么', '怎么', '如何', '为什么',
    '哪', '谁', '几', '多少', '怎样', '何时', '何地',
  ];
  
  static const List<String> questionPatterns = [
    r'.*[？?]$',
    r'.*(是什么|是什么意思|有什么|有哪些).*$',
    r'.*(如何|怎么|怎样).*$',
    r'.*(为什么|为何).*$',
    r'.*(谁|哪位).*$',
    r'.*(多少|几个|几次).*$',
  ];
  
  static String getFunASRUrl(String host, int port) {
    return 'ws://$host:$port';
  }
  
  static Map<String, dynamic> getFunASRConfig() {
    return {
      'mode': '2pass',
      'chunk_size': [5, 10, 5],
      'chunk_interval': 10,
      'wav_format': 'pcm',
      'is_speaking': true,
    };
  }
}
