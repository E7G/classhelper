import 'package:flutter/foundation.dart';
import 'package:hive/hive.dart';
import '../models/question.dart';
import '../models/llm_config.dart';
import '../services/llm_service.dart';
import '../services/question_detector.dart';

class QuestionProvider extends ChangeNotifier {
  final LLMService _llmService = LLMService();
  final QuestionDetector _detector = QuestionDetector();

  late Box<Question> _questionBox;
  late Box _settingsBox;
  List<Question> _questions = [];
  Question? _currentQuestion;
  String _currentAnswer = '';
  bool _isGenerating = false;
  bool _isProcessingAnswerQueue = false;
  final List<Question> _answerQueue = [];
  String? _errorMessage;
  LLMConfig _llmConfig = const LLMConfig();
  String _currentCategory = 'default';

  bool _autoDetectEnabled = false;
  double _autoDetectConfidenceThreshold = 0.75;
  DateTime? _lastAutoDetectTime;
  static const Duration _autoDetectCooldown = Duration(seconds: 15);
  String? _lastDetectedContent;
  static const int _minDetectTextLength = 10;

  List<Question> get questions => List.unmodifiable(_questions);
  Question? get currentQuestion => _currentQuestion;
  String get currentAnswer => _currentAnswer;
  bool get isGenerating => _isGenerating;
  String? get errorMessage => _errorMessage;
  LLMConfig get llmConfig => _llmConfig;
  LLMService get llmService => _llmService;
  String get currentCategory => _currentCategory;
  bool get autoDetectEnabled => _autoDetectEnabled;
  double get autoDetectConfidenceThreshold => _autoDetectConfidenceThreshold;

  QuestionProvider() {
    _init();
  }

  void _init() {
    _questionBox = Hive.box<Question>('questions');
    _settingsBox = Hive.box('settings');
    _loadCategories();
    _loadQuestions();
    _loadLLMConfig();
    _loadAutoDetectConfig();
  }

  List<String> _categories = ['default'];

  List<String> get categories => List.unmodifiable(_categories);

  void _loadCategories() {
    final savedCategories = _settingsBox.get('question_categories') as List?;
    if (savedCategories != null) {
      _categories = List<String>.from(savedCategories);
    }
  }

  Future<void> _saveCategories() async {
    await _settingsBox.put('question_categories', _categories);
  }

  void setCurrentCategory(String category) {
    if (_currentCategory != category) {
      _currentCategory = category;
      _loadQuestions();
      notifyListeners();
    }
  }

  Future<void> createCategory(String name) async {
    if (name.isNotEmpty && !_categories.contains(name)) {
      _categories.add(name);
      await _saveCategories();
      notifyListeners();
    }
  }

  Future<bool> deleteCategory(String name) async {
    if (!_categories.contains(name)) return false;

    if (name == 'default') {
      final toDelete = _questionBox.values.where((q) => q.category == 'default').map((q) => q.id).toList();
      for (final id in toDelete) {
        await _questionBox.delete(id);
      }
      _loadQuestions();
      notifyListeners();
      return true;
    }

    final toDelete = _questionBox.values.where((q) => q.category == name).map((q) => q.id).toList();
    for (final id in toDelete) {
      await _questionBox.delete(id);
    }
    _categories.remove(name);
    if (_currentCategory == name) {
      _currentCategory = 'default';
      if (!_categories.contains('default')) {
        _categories.insert(0, 'default');
      }
      _loadQuestions();
    }
    await _saveCategories();
    notifyListeners();
    return true;
  }

  void _loadQuestions() {
    _questions = _questionBox.values
        .where((q) => q.category == _currentCategory)
        .toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
    notifyListeners();
  }

  void _loadLLMConfig() {
    final configJson = _settingsBox.get('llm_config') as Map?;

    if (configJson != null) {
      final savedConfig = LLMConfig.fromJson(Map<String, dynamic>.from(configJson));

      if (savedConfig.providerType == LLMProviderType.local) {
        if (savedConfig.model != 'Qwen3.5-0.8B-Q4_K_M.gguf') {
          _llmConfig = LLMConfig.local(
            model: 'Qwen3.5-0.8B-Q4_K_M.gguf',
          );
          _llmService.configure(_llmConfig);
          _saveLLMConfig();
          return;
        }
      }

      _llmConfig = savedConfig;
      _llmService.configure(_llmConfig);
    } else {
      _llmConfig = LLMConfig.local(
        model: 'Qwen3.5-0.8B-Q4_K_M.gguf',
      );
      _llmService.configure(_llmConfig);
      _saveLLMConfig();
    }
  }

  void _saveLLMConfig() {
    _settingsBox.put('llm_config', _llmConfig.toJson());
  }

  void configureLLM(LLMConfig config) {
    _llmConfig = config;
    _llmService.configure(config);

    _settingsBox.put('llm_config', config.toJson());

    notifyListeners();
  }

  void _loadAutoDetectConfig() {
    _autoDetectEnabled = _settingsBox.get('auto_detect_enabled') as bool? ?? false;
    _autoDetectConfidenceThreshold =
        (_settingsBox.get('auto_detect_confidence_threshold') as double? ?? 0.75)
            .clamp(0.65, 1.0);
  }

  void _saveAutoDetectConfig() {
    _settingsBox.put('auto_detect_enabled', _autoDetectEnabled);
    _settingsBox.put('auto_detect_confidence_threshold', _autoDetectConfidenceThreshold);
  }

  void setAutoDetectEnabled(bool enabled) {
    if (_autoDetectEnabled != enabled) {
      _autoDetectEnabled = enabled;
      _saveAutoDetectConfig();
      notifyListeners();
    }
  }

  void setAutoDetectConfidenceThreshold(double threshold) {
    threshold = threshold.clamp(0.65, 1.0);
    if (_autoDetectConfidenceThreshold != threshold) {
      _autoDetectConfidenceThreshold = threshold;
      _saveAutoDetectConfig();
      notifyListeners();
    }
  }

  void detectQuestion(String text, {String? context}) {
    final question = _detector.detect(text, strict: false);

    if (question != null && !_isDuplicateQuestion(text)) {
      _enqueueQuestion(question.copyWith(context: context));
    }
  }

  Future<bool> detectQuestionAsync(String text, {String? context}) async {
    final question = _detector.detect(text, strict: false);

    if (question != null && !_isDuplicateQuestion(text)) {
      await _enqueueQuestion(question.copyWith(context: context));
      return true;
    }
    return false;
  }

  /// 快速检测（不加载 PDF），用于自动检测的第一阶段
  Question? tryDetectFromASR(String text) {
    if (!_autoDetectEnabled) return null;
    if (text.trim().isEmpty) return null;
    if (text.trim().length < _minDetectTextLength) return null;

    final now = DateTime.now();
    if (_lastAutoDetectTime != null &&
        now.difference(_lastAutoDetectTime!) < _autoDetectCooldown) {
      return null;
    }

    if (_lastDetectedContent != null &&
        _isSimilarText(text, _lastDetectedContent!)) {
      return null;
    }

    if (_isDuplicateQuestion(text)) return null;

    if (!_detector.isLikelyQuestion(text, strict: true)) return null;

    final question = _detector.detect(
      text,
      confidenceThreshold: _autoDetectConfidenceThreshold,
      strict: true,
    );

    return question;
  }

  /// 确认自动检测并加入回答队列（可附带 PDF 上下文）
  Future<Question?> confirmAutoDetectedQuestion(
    Question question, {
    String? asrContext,
    String? pdfContent,
    String? pdfFileName,
  }) async {
    _lastAutoDetectTime = DateTime.now();
    _lastDetectedContent = question.content;

    final richContext = _buildRichContext(
      asrContext: asrContext,
      pdfContent: pdfContent,
      pdfFileName: pdfFileName,
    );

    final enrichedQuestion = question.copyWith(
      context: richContext,
      category: pdfFileName ?? _currentCategory,
    );

    await _addQuestionToAutoDetectQueue(enrichedQuestion);
    return enrichedQuestion;
  }

  bool _isDuplicateQuestion(String text) {
    final normalized = _normalizeText(text);
    if (normalized.length < 4) return false;

    for (final question in _questions.take(15)) {
      if (_normalizeText(question.content) == normalized) return true;
      if (_isSimilarText(text, question.content)) return true;
    }
    return false;
  }

  String _normalizeText(String text) {
    return text
        .trim()
        .replaceAll(RegExp(r'[？?。，,\.!！\s]'), '')
        .toLowerCase();
  }

  bool _isSimilarText(String a, String b) {
    final aNorm = _normalizeText(a);
    final bNorm = _normalizeText(b);
    if (aNorm.isEmpty || bNorm.isEmpty) return false;
    if (aNorm == bNorm) return true;
    if (aNorm.contains(bNorm) || bNorm.contains(aNorm)) return true;

    final shorter = aNorm.length < bNorm.length ? aNorm : bNorm;
    final longer = aNorm.length < bNorm.length ? bNorm : aNorm;
    if (shorter.length >= 6 && longer.startsWith(shorter)) return true;

    return false;
  }

  String _buildRichContext({
    String? asrContext,
    String? pdfContent,
    String? pdfFileName,
  }) {
    final parts = <String>[];

    if (pdfFileName != null && pdfFileName.isNotEmpty) {
      parts.add('当前课程资料：$pdfFileName');
    }

    if (pdfContent != null && pdfContent.isNotEmpty) {
      parts.add('课程资料内容：\n$pdfContent');
    }

    if (asrContext != null && asrContext.isNotEmpty) {
      parts.add('课堂语音上下文：\n$asrContext');
    }

    return parts.join('\n\n');
  }

  Future<void> _addQuestionToAutoDetectQueue(Question question) async {
    await _enqueueQuestion(question);
  }

  Future<Question> createQuestion(String content, {String? context, QuestionType type = QuestionType.unknown, String? category}) async {
    final question = Question(
      id: DateTime.now().millisecondsSinceEpoch.toString(),
      content: content,
      createdAt: DateTime.now(),
      type: type,
      status: QuestionStatus.pending,
      context: context,
      category: category ?? _currentCategory,
    );

    await _questionBox.put(question.id, question);
    _questions.insert(0, question);
    _currentQuestion = question;
    notifyListeners();

    _scheduleAnswerGeneration(question);
    return question;
  }

  Future<Question> addQuestionWithAnswer(String content, String answer, {String? context, QuestionType type = QuestionType.unknown, String? category}) async {
    final question = Question(
      id: DateTime.now().millisecondsSinceEpoch.toString(),
      content: content,
      createdAt: DateTime.now(),
      type: type,
      status: QuestionStatus.answered,
      answer: answer,
      answeredAt: DateTime.now(),
      context: context,
      category: category ?? _currentCategory,
    );

    await _questionBox.put(question.id, question);
    _questions.insert(0, question);
    _currentQuestion = question;
    notifyListeners();

    return question;
  }

  Future<void> _enqueueQuestion(Question question) async {
    await _questionBox.put(question.id, question);
    _questions.insert(0, question);
    _currentQuestion = question;
    notifyListeners();

    _scheduleAnswerGeneration(question);
  }

  void _scheduleAnswerGeneration(Question question) {
    _answerQueue.add(question);
    _processAnswerQueue();
  }

  Future<void> _processAnswerQueue() async {
    if (_isProcessingAnswerQueue) return;

    _isProcessingAnswerQueue = true;

    while (_answerQueue.isNotEmpty) {
      final question = _answerQueue.removeAt(0);
      await _generateAnswer(question);
    }

    _isProcessingAnswerQueue = false;
  }

  Future<void> _generateAnswer(Question question) async {
    _isGenerating = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final updatedQuestion = question.copyWith(
        status: QuestionStatus.answering,
      );
      await _questionBox.put(question.id, updatedQuestion);

      final systemPrompt = _buildSystemPrompt(question.context);

      final answer = await _llmService.generateAnswer(
        question.content,
        context: question.context,
        systemPrompt: systemPrompt,
      );

      final answeredQuestion = updatedQuestion.copyWith(
        answer: answer,
        status: QuestionStatus.answered,
        answeredAt: DateTime.now(),
      );

      await _questionBox.put(question.id, answeredQuestion);

      final index = _questions.indexWhere((q) => q.id == question.id);
      if (index != -1) {
        _questions[index] = answeredQuestion;
      }

      _currentQuestion = answeredQuestion;
      _currentAnswer = answer;
      _isGenerating = false;
      notifyListeners();
    } catch (e) {
      _errorMessage = '生成答案失败: $e';
      _isGenerating = false;

      final failedQuestion = question.copyWith(
        status: QuestionStatus.failed,
      );
      await _questionBox.put(question.id, failedQuestion);

      notifyListeners();
    }
  }

  String _buildSystemPrompt(String? context) {
    final hasPdfContext = context != null && context.contains('课程资料内容');
    final hasAsrContext = context != null && context.contains('课堂语音上下文');

    if (hasPdfContext && hasAsrContext) {
      return '你是课堂助手。根据课程资料内容和课堂语音上下文，简洁准确地回答问题。'
          '优先参考课程资料中的内容，结合语音上下文理解问题的背景。'
          '如果资料中没有相关信息，可以基于语音上下文进行合理推断，但要说明来源。';
    } else if (hasPdfContext) {
      return '你是课堂助手。根据课程资料内容，简洁准确地回答问题。'
          '优先参考资料中的内容，如果资料中没有相关信息，可以基于常识回答，但要说明来源。';
    } else if (hasAsrContext) {
      return '你是课堂助手。根据课堂语音上下文，简洁准确地回答问题。'
          '结合上下文理解问题的背景，给出合理的回答。';
    }

    return '你是课堂助手。简洁回答问题。';
  }

  Future<void> regenerateAnswer(String questionId) async {
    final question = _questionBox.get(questionId);
    if (question == null) return;

    final resetQuestion = question.copyWith(
      answer: null,
      status: QuestionStatus.pending,
      answeredAt: null,
    );

    await _questionBox.put(questionId, resetQuestion);

    final index = _questions.indexWhere((q) => q.id == questionId);
    if (index != -1) {
      _questions[index] = resetQuestion;
    }

    _currentQuestion = resetQuestion;
    notifyListeners();

    await _generateAnswer(resetQuestion);
  }

  Future<void> deleteQuestion(String id) async {
    await _questionBox.delete(id);
    _questions.removeWhere((q) => q.id == id);

    if (_currentQuestion?.id == id) {
      _currentQuestion = null;
      _currentAnswer = '';
    }

    notifyListeners();
  }

  void clearCurrentCategoryQuestions() async {
    final toDelete = _questions.map((q) => q.id).toList();
    for (final id in toDelete) {
      await _questionBox.delete(id);
    }
    _questions.clear();
    _currentQuestion = null;
    _currentAnswer = '';
    notifyListeners();
  }

  void clearAllQuestions() async {
    await _questionBox.clear();
    _questions.clear();
    _currentQuestion = null;
    _currentAnswer = '';
    notifyListeners();
  }

  List<Question> getPendingQuestions() {
    return _questions.where((q) => q.status == QuestionStatus.pending).toList();
  }

  List<Question> getAnsweredQuestions() {
    return _questions.where((q) => q.status == QuestionStatus.answered).toList();
  }

  int getQuestionCountByCategory(String category) {
    return _questionBox.values.where((q) => q.category == category).length;
  }

  Future<bool> testLLMConnection() async {
    return await _llmService.testConnection();
  }

  Future<List<String>> getAvailableModels() async {
    return await _llmService.getAvailableModels();
  }

  bool get isLLMLoaded => _llmService.isModelLoaded;

  void stopLLM() {
    _llmService.pauseModel();
    notifyListeners();
  }

  void resumeLLM() {
    _llmService.resumeModel();
    notifyListeners();
  }

  Future<bool> deleteLLMModel(String path) async {
    return await _llmService.localLLM.deleteModelFile(path);
  }

  @override
  void dispose() {
    _llmService.dispose();
    super.dispose();
  }
}
