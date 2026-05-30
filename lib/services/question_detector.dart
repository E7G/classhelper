import '../config/app_config.dart';
import '../models/question.dart';

class QuestionDetector {
  final List<RegExp> _questionPatterns =
      AppConfig.questionPatterns.map((p) => RegExp(p)).toList();

  final List<String> _strongQuestionPhrases = AppConfig.strongQuestionPhrases;

  final List<RegExp> _lecturePatterns = [
    RegExp(r'我们(来|先|再|就|现在|接下来)?(看|讲|说|讨论|学习|复习|介绍)'),
    RegExp(r'(接下来|然后|下面|首先|其次|最后|第一|第二)(我们|大家|同学们)?'),
    RegExp(r'(注意|记住|强调|总结|回顾|复习)一下'),
    RegExp(r'(也就是说|换句话说|简单来说|具体来说|由此可见)'),
    RegExp(r'(来讲|来说|去看|解释一下|说明一下|阐述一下).*(什么|怎么|如何|为什么)'),
    RegExp(r'(这个|那个|这里|这一)(概念|知识点|内容|方法|部分)(是|就是|指的是)'),
    RegExp(r'(定义为|指的是|就是说|也就是)'),
    RegExp(r'^(So|Yeah|But|Yes|Oh|To|No|I|We|He|She|It|They|And|Or|If|Of|At|In|On|Up|Go|Do|Be|Is|Am|Are|Was|Were)\b'),
  ];

  final List<String> _contextBuffer = [];
  static const int _maxContextLength = 10;

  Question? detect(
    String text, {
    double confidenceThreshold = 0.65,
    bool strict = false,
  }) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return null;

    if (_isNoise(trimmed)) return null;
    if (_isFillerQuestion(trimmed)) return null;
    if (_isLectureStatement(trimmed)) return null;

    final minLength = strict ? 10 : 8;
    if (trimmed.length < minLength) return null;

    if (!_hasStrongQuestionSignal(trimmed, strict: strict)) return null;

    final confidence = _calculateConfidence(trimmed, strict: strict);
    final effectiveThreshold = strict
        ? confidenceThreshold.clamp(0.7, 1.0)
        : confidenceThreshold;

    if (confidence < effectiveThreshold) {
      return null;
    }

    final questionType = _classifyQuestion(trimmed);
    final context = _buildContext();

    _addToContext(trimmed);

    return Question(
      id: DateTime.now().millisecondsSinceEpoch.toString(),
      content: trimmed,
      createdAt: DateTime.now(),
      type: questionType,
      status: QuestionStatus.pending,
      confidence: confidence,
      context: context,
    );
  }

  bool isLikelyQuestion(String text, {bool strict = false}) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return false;
    if (_isNoise(trimmed) || _isFillerQuestion(trimmed) || _isLectureStatement(trimmed)) {
      return false;
    }
    final minLength = strict ? 10 : 8;
    if (trimmed.length < minLength) return false;
    return _hasStrongQuestionSignal(trimmed, strict: strict);
  }

  bool _isNoise(String text) {
    if (RegExp(r'^[.。,，!！?？;；:：\s]+$').hasMatch(text)) return true;
    if (RegExp(r'^[a-zA-Z\s.!?]+$').hasMatch(text) && text.length < 20) {
      return true;
    }
    if (RegExp(r'^(.)\1{3,}$').hasMatch(text.replaceAll(RegExp(r'\s'), ''))) {
      return true;
    }
    return false;
  }

  bool _isFillerQuestion(String text) {
    final fillerPatterns = [
      RegExp(r'^.{0,4}(对吧|是吧|好吧|行吧|是吗|对吗|不是吗|对不对|是不是|行不行|好不好|可以吧|应该吧|可能吧|大概吧|也许吧)[？?]?$'),
      RegExp(r'^.{0,2}(吧|呢|嘛)[？?]?$'),
      RegExp(r'^(嗯|啊|哦|哈|哎|唉|嘿|呃|额)[？?]?$'),
      RegExp(r'^(然后呢|所以呢|接下来呢|那又怎样|还有呢|后来呢)$'),
      RegExp(r'^.{0,3}(对吧|是吧).{0,2}[？?]?$'),
      RegExp(r'^(是不是|对不对|好不好|行不行)[？?]?$'),
      RegExp(r'^(OK|ok|Okay|okay|Right|right)[？?]?$'),
    ];

    for (final pattern in fillerPatterns) {
      if (pattern.hasMatch(text)) return true;
    }

    final fillerExact = {
      '对吧', '是吧', '好吧', '行吧', '是吗', '对吗', '不是吗',
      '对不对', '是不是', '行不行', '好不好', '可以吧', '应该吧',
      '可能吧', '大概吧', '也许吧', '然后呢', '所以呢',
      '对吧？', '是吧？', '对吧?', '是吧?', '明白了吗', '听懂了吗',
      '清楚了吗', '懂了吗', '知道吗',
    };
    if (fillerExact.contains(text)) return true;

    return false;
  }

  bool _isLectureStatement(String text) {
    for (final pattern in _lecturePatterns) {
      if (pattern.hasMatch(text)) return true;
    }

    // 陈述句口吻："...是..." 且没有明确疑问结构
    if (!text.endsWith('？') &&
        !text.endsWith('?') &&
        RegExp(r'.+(是|为|叫做|称为).+的[。.]?$').hasMatch(text) &&
        !_containsStrongPhrase(text)) {
      return true;
    }

    return false;
  }

  bool _hasStrongQuestionSignal(String text, {bool strict = false}) {
    if (text.endsWith('？') || text.endsWith('?')) {
      return !_isFillerQuestion(text);
    }

    const questionStarters = ['请问', '想问', '问一下', '请教', '请问一下', '我想问'];
    for (final starter in questionStarters) {
      if (text.startsWith(starter)) return true;
    }

    if (!_containsStrongPhrase(text)) return false;

    if (strict) {
      // 自动检测：强疑问词必须出现在句末附近，或整句较短
      final hasQuestionPattern = _questionPatterns.any((p) => p.hasMatch(text));
      if (!hasQuestionPattern) {
        final endsWithQuestionPhrase = _strongQuestionPhrases.any((phrase) {
          final index = text.lastIndexOf(phrase);
          if (index == -1) return false;
          return index + phrase.length >= text.length - 4;
        });
        if (!endsWithQuestionPhrase && text.length > 20) {
          return false;
        }
      }
    }

    return true;
  }

  bool _containsStrongPhrase(String text) {
    return _strongQuestionPhrases.any(text.contains);
  }

  double _calculateConfidence(String text, {bool strict = false}) {
    double score = 0.55;

    for (final pattern in _questionPatterns) {
      if (pattern.hasMatch(text)) {
        score += 0.15;
        break;
      }
    }

    if (text.endsWith('？') || text.endsWith('?')) {
      score += 0.15;
    }

    for (final phrase in _strongQuestionPhrases) {
      if (text.contains(phrase)) {
        score += 0.08;
        break;
      }
    }

    const questionStarters = ['请问', '想问', '问一下', '请教'];
    for (final starter in questionStarters) {
      if (text.startsWith(starter)) {
        score += 0.1;
        break;
      }
    }

    final length = text.length;
    if (length >= 8 && length <= 80) {
      score += 0.05;
    } else if (length > 120) {
      score -= 0.1;
    }

    if (strict && !text.endsWith('？') && !text.endsWith('?')) {
      score -= 0.05;
    }

    return score.clamp(0.0, 1.0);
  }

  QuestionType _classifyQuestion(String text) {
    const factualKeywords = ['是什么', '什么是', '有哪些', '叫什么', '是谁'];
    const conceptualKeywords = ['为什么', '原因', '原理', '意义', '作用'];
    const proceduralKeywords = ['怎么', '如何', '怎样', '步骤', '方法'];
    const analyticalKeywords = ['分析', '比较', '评价', '优缺点', '区别'];

    for (final keyword in factualKeywords) {
      if (text.contains(keyword)) return QuestionType.factual;
    }
    for (final keyword in conceptualKeywords) {
      if (text.contains(keyword)) return QuestionType.conceptual;
    }
    for (final keyword in proceduralKeywords) {
      if (text.contains(keyword)) return QuestionType.procedural;
    }
    for (final keyword in analyticalKeywords) {
      if (text.contains(keyword)) return QuestionType.analytical;
    }

    return QuestionType.unknown;
  }

  void _addToContext(String text) {
    _contextBuffer.add(text);
    if (_contextBuffer.length > _maxContextLength) {
      _contextBuffer.removeAt(0);
    }
  }

  String _buildContext() => _contextBuffer.join('\n');

  void clearContext() => _contextBuffer.clear();

  List<String> getContext() => List.unmodifiable(_contextBuffer);
}
