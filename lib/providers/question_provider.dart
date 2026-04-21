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
  String? _errorMessage;
  LLMConfig _llmConfig = const LLMConfig();
  String _currentCategory = 'default';

  List<Question> get questions => List.unmodifiable(_questions);
  Question? get currentQuestion => _currentQuestion;
  String get currentAnswer => _currentAnswer;
  bool get isGenerating => _isGenerating;
  String? get errorMessage => _errorMessage;
  LLMConfig get llmConfig => _llmConfig;
  LLMService get llmService => _llmService;
  String get currentCategory => _currentCategory;

  QuestionProvider() {
    _init();
  }

  void _init() {
    _questionBox = Hive.box<Question>('questions');
    _settingsBox = Hive.box('settings');
    _loadCategories();
    _loadQuestions();
    _loadLLMConfig();
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

  void detectQuestion(String text, {String? context}) {
    final question = _detector.detect(text);

    if (question != null) {
      _addQuestion(question.copyWith(context: context));
    }
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

    _generateAnswer(question);
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

  void _addQuestion(Question question) async {
    await _questionBox.put(question.id, question);
    _questions.insert(0, question);
    _currentQuestion = question;
    notifyListeners();

    _generateAnswer(question);
  }

  Future<void> _generateAnswer(Question question) async {
    if (_isGenerating) return;

    _isGenerating = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final updatedQuestion = question.copyWith(
        status: QuestionStatus.answering,
      );
      await _questionBox.put(question.id, updatedQuestion);

      final answer = await _llmService.generateAnswer(
        question.content,
        context: question.context,
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
