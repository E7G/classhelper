import '../config/app_config.dart';
import '../models/question.dart';

class QuestionDetector {
  final List<RegExp> _questionPatterns = 
      AppConfig.questionPatterns.map((p) => RegExp(p)).toList();
  
  final List<String> _questionKeywords = AppConfig.questionKeywords;
  
  final List<String> _contextBuffer = [];
  static const int _maxContextLength = 10;

  Question? detect(String text, {double confidenceThreshold = 0.6}) {
    if (text.trim().isEmpty) return null;

    if (_isFillerQuestion(text)) return null;

    final confidence = _calculateConfidence(text);

    if (confidence < confidenceThreshold) {
      return null;
    }

    final questionType = _classifyQuestion(text);
    final context = _buildContext();

    _addToContext(text);

    return Question(
      id: DateTime.now().millisecondsSinceEpoch.toString(),
      content: text,
      createdAt: DateTime.now(),
      type: questionType,
      status: QuestionStatus.pending,
      confidence: confidence,
      context: context,
    );
  }

  bool _isFillerQuestion(String text) {
    final trimmed = text.trim();

    final fillerPatterns = [
      RegExp(r'^.{0,4}(对吧|是吧|好吧|行吧|是吗|对吗|不是吗|对不对|是不是|行不行|好不好|可以吧|应该吧|可能吧|大概吧|也许吧|对吧[？?]?|是吧[？?]?)$'),
      RegExp(r'^.{0,2}(吧|呢|嘛)[？?]?$'),
      RegExp(r'^(嗯|啊|哦|哈|哎|唉|嘿)[？?]?$'),
      RegExp(r'^(然后呢|所以呢|接下来呢|那又怎样)$'),
      RegExp(r'^.{0,3}(对吧|是吧).{0,2}$'),
    ];

    for (final pattern in fillerPatterns) {
      if (pattern.hasMatch(trimmed)) {
        return true;
      }
    }

    final fillerExact = [
      '对吧', '是吧', '好吧', '行吧', '是吗', '对吗', '不是吗',
      '对不对', '是不是', '行不行', '好不好', '可以吧', '应该吧',
      '可能吧', '大概吧', '也许吧', '然后呢', '所以呢',
      '对吧？', '是吧？', '对吧?', '是吧?',
    ];
    if (fillerExact.contains(trimmed)) {
      return true;
    }

    return false;
  }

  double _calculateConfidence(String text) {
    double score = 0.0;
    
    for (final pattern in _questionPatterns) {
      if (pattern.hasMatch(text)) {
        score += 0.3;
      }
    }
    
    for (final keyword in _questionKeywords) {
      if (text.contains(keyword)) {
        score += 0.1;
      }
    }
    
    final length = text.length;
    if (length > 5 && length < 100) {
      score += 0.2;
    } else if (length >= 100 && length < 200) {
      score += 0.1;
    }
    
    if (_hasQuestionStructure(text)) {
      score += 0.2;
    }
    
    return score.clamp(0.0, 1.0);
  }

  bool _hasQuestionStructure(String text) {
    final trimmed = text.trim();
    
    if (trimmed.endsWith('？') || trimmed.endsWith('?')) {
      return true;
    }
    
    final questionStarters = ['请问', '想问', '问一下', '请教'];
    for (final starter in questionStarters) {
      if (trimmed.startsWith(starter)) {
        return true;
      }
    }
    
    return false;
  }

  QuestionType _classifyQuestion(String text) {
    final factualKeywords = ['是什么', '什么是', '有哪些', '叫什么', '是谁'];
    final conceptualKeywords = ['为什么', '原因', '原理', '意义', '作用'];
    final proceduralKeywords = ['怎么', '如何', '怎样', '步骤', '方法'];
    final analyticalKeywords = ['分析', '比较', '评价', '优缺点', '区别'];
    
    for (final keyword in factualKeywords) {
      if (text.contains(keyword)) {
        return QuestionType.factual;
      }
    }
    
    for (final keyword in conceptualKeywords) {
      if (text.contains(keyword)) {
        return QuestionType.conceptual;
      }
    }
    
    for (final keyword in proceduralKeywords) {
      if (text.contains(keyword)) {
        return QuestionType.procedural;
      }
    }
    
    for (final keyword in analyticalKeywords) {
      if (text.contains(keyword)) {
        return QuestionType.analytical;
      }
    }
    
    return QuestionType.unknown;
  }

  void _addToContext(String text) {
    _contextBuffer.add(text);
    
    if (_contextBuffer.length > _maxContextLength) {
      _contextBuffer.removeAt(0);
    }
  }

  String _buildContext() {
    return _contextBuffer.join('\n');
  }

  void clearContext() {
    _contextBuffer.clear();
  }

  List<String> getContext() {
    return List.unmodifiable(_contextBuffer);
  }
}
