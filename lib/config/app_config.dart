class AppConfig {
  static const String appName = '智能课堂助手';
  static const String version = '1.0.0';

  static const String defaultFunASRHost = 'localhost';
  static const int defaultFunASRPort = 10095;

  static const String defaultLLMApiUrl = 'https://api.openai.com/v1';
  static const String defaultLLMModel = 'gpt-4';

  static const Duration asrReconnectDelay = Duration(seconds: 3);
  static const Duration questionDetectionDebounce = Duration(milliseconds: 800);

  /// 强疑问短语 — 单独出现不足以判定，需配合问号或疑问句式
  static const List<String> strongQuestionPhrases = [
    '是什么',
    '什么是',
    '为什么',
    '为何',
    '如何',
    '怎么',
    '怎样',
    '多少',
    '几个',
    '能否',
    '是否',
    '可不可以',
    '能不能',
    '有没有',
    '哪些',
    '哪位',
    '什么时候',
    '什么地方',
    '什么意思',
    '有什么区别',
    '意味着什么',
    '有哪些',
  ];

  /// 保留兼容，检测逻辑以 strongQuestionPhrases 为主
  static const List<String> questionKeywords = strongQuestionPhrases;

  /// 更严格的正则 — 避免匹配讲课陈述句
  static const List<String> questionPatterns = [
    r'.*[？?]$',
    r'^(请问|想问|请教|问一下).+',
    r'.*(是什么|是什么意思|有哪些|是什么东西|什么意思)[？?]?$',
    r'.*(为什么|为何).+[？?]$',
    r'.*(如何|怎么|怎样).+[？?]$',
    r'.*(多少|几个|几次|哪位|哪些).+[？?]$',
    r'.*(能否|是否|可不可以|能不能|有没有)[？?]$',
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
