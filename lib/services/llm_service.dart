import 'dart:async';
import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:logger/logger.dart';
import '../models/llm_config.dart';
import 'local_llm_service.dart';
import 'bing_search_service.dart';

class LLMService {
  final Logger _logger = Logger();
  final Dio _dio = Dio();
  final LocalLLMService _localLLM = LocalLLMService();
  final BingSearchService _searchService = BingSearchService();
  
  LLMConfig _config = const LLMConfig(
    providerType: LLMProviderType.local,
    isLocal: true,
  );
  bool _isLoadingModel = false;
  Completer<void>? _modelLoadCompleter;
  
  Timer? _unloadTimer;
  static const Duration _unloadAfter = Duration(minutes: 5);
  
  final StreamController<String> _streamController = 
      StreamController<String>.broadcast();
  final StreamController<double> _modelLoadProgress = 
      StreamController<double>.broadcast();
  final StreamController<String> _statusController = 
      StreamController<String>.broadcast();
  
  Stream<String> get stream => _streamController.stream;
  Stream<double> get modelLoadProgress => _modelLoadProgress.stream;
  Stream<String> get statusStream => _statusController.stream;
  LLMConfig get config => _config;
  LocalLLMService get localLLM => _localLLM;
  BingSearchService get searchService => _searchService;
  bool get isModelLoading => _isLoadingModel;
  bool get isModelLoaded => _localLLM.isLoaded;

  LLMService() {
    _autoConfigureLocal();
  }

  void _resetUnloadTimer() {
    _unloadTimer?.cancel();
    _unloadTimer = Timer(_unloadAfter, () {
      if (_localLLM.isLoaded && !_isLoadingModel) {
        _logger.i('Auto-unloading LLM model after ${_unloadAfter.inMinutes} minutes of inactivity');
        _localLLM.unloadModel();
        _statusController.add('LLM模型已自动卸载以节省内存');
      }
    });
  }

  void pauseModel() {
    if (_localLLM.isLoaded) {
      _logger.i('Pausing LLM model to save memory');
      _localLLM.unloadModel();
      _unloadTimer?.cancel();
    }
  }

  void resumeModel() async {
    if (!_localLLM.isLoaded && !_isLoadingModel) {
      await _loadLocalModelAsync();
    }
    _resetUnloadTimer();
  }

  void _autoConfigureLocal() {
    _config = const LLMConfig(
      providerType: LLMProviderType.local,
      isLocal: true,
    );
    _logger.i('LLM auto-configured for local model');
  }

  void configure(LLMConfig config, {bool preloadModel = false}) {
    _config = config;
    _logger.i('LLM configured: ${config.providerType}, model: ${config.model}');
    
    if (preloadModel && config.providerType == LLMProviderType.local && !_localLLM.isLoaded) {
      _loadLocalModelAsync();
    }
  }

  Future<void> preloadModel() async {
    if (_config.providerType == LLMProviderType.local && !_localLLM.isLoaded && !_isLoadingModel) {
      await _loadLocalModelAsync();
    }
  }

  Future<void> _loadLocalModelAsync() async {
    if (_isLoadingModel) {
      await _modelLoadCompleter?.future;
      return;
    }
    
    if (_localLLM.isLoaded) return;
    
    _isLoadingModel = true;
    _modelLoadCompleter = Completer<void>();
    _modelLoadProgress.add(0.0);
    _statusController.add('正在加载LLM模型...');
    
    try {
      _logger.i('Loading local model from saved path');
      final success = await _localLLM.loadModel(null);
      
      if (success) {
        _logger.i('Local model loaded successfully');
        _modelLoadProgress.add(1.0);
        _statusController.add('LLM模型加载成功');
      } else {
        _logger.e('Failed to load local model');
        _statusController.add('LLM模型加载失败，请检查模型配置');
      }
    } catch (e) {
      _logger.e('Failed to load local model: $e');
      _statusController.add('LLM模型加载失败: $e');
    } finally {
      _isLoadingModel = false;
      _modelLoadCompleter?.complete();
      _modelLoadCompleter = null;
    }
  }

  Future<void> _ensureModelLoaded() async {
    if (_config.providerType != LLMProviderType.local) return;
    
    if (_localLLM.isLoaded) {
      _resetUnloadTimer();
      return;
    }
    
    if (_isLoadingModel) {
      await _modelLoadCompleter?.future;
      _resetUnloadTimer();
      return;
    }
    
    await _loadLocalModelAsync();
    
    if (!_localLLM.isLoaded) {
      throw Exception('LLM模型未加载，请先在"设置 → 模型管理"中配置模型路径');
    }
    
    _resetUnloadTimer();
  }

  Future<String> generateAnswer(
    String question, {
    String? context,
    String? systemPrompt,
    bool useSearch = false,
  }) async {
    await _ensureModelLoaded();

    String? enhancedContext = context;
    if (useSearch || _config.searchEnhance) {
      _logger.i('Using search enhancement for question');
      final searchResults = await _searchService.search(question);
      if (searchResults != null && searchResults.isNotEmpty) {
        enhancedContext = '${context ?? ''}\n\n[网络搜索结果]\n$searchResults';
      }
    }

    switch (_config.providerType) {
      case LLMProviderType.openai:
        return _generateOpenAIAnswer(question, context: enhancedContext, systemPrompt: systemPrompt);
      case LLMProviderType.ollama:
        return _generateOllamaAnswer(question, context: enhancedContext, systemPrompt: systemPrompt);
      case LLMProviderType.local:
        return _generateLocalAnswer(question, context: enhancedContext, systemPrompt: systemPrompt);
      case LLMProviderType.custom:
        return _generateCustomAnswer(question, context: enhancedContext, systemPrompt: systemPrompt);
    }
  }

  Future<String> _generateOpenAIAnswer(
    String question, {
    String? context,
    String? systemPrompt,
  }) async {
    if (_config.apiKey?.isEmpty ?? true) {
      throw Exception('OpenAI API Key未配置');
    }

    final messages = _buildMessages(question, context, systemPrompt);

    try {
      final response = await _dio.post(
        '${_config.apiUrl}/chat/completions',
        options: Options(
          headers: {
            'Authorization': 'Bearer ${_config.apiKey}',
            'Content-Type': 'application/json',
          },
        ),
        data: {
          'model': _config.model,
          'messages': messages,
          'max_tokens': _config.maxTokens,
          'temperature': _config.temperature,
        },
      );

      final data = response.data as Map<String, dynamic>;
      final choices = data['choices'] as List;
      
      if (choices.isNotEmpty) {
        return choices[0]['message']['content'] as String;
      }
      
      throw Exception('未获取到有效回答');
    } catch (e) {
      _logger.e('OpenAI API error: $e');
      rethrow;
    }
  }

  Future<String> _generateOllamaAnswer(
    String question, {
    String? context,
    String? systemPrompt,
  }) async {
    final messages = _buildMessages(question, context, systemPrompt);

    try {
      final response = await _dio.post(
        '${_config.apiUrl}/api/chat',
        options: Options(
          headers: {
            'Content-Type': 'application/json',
          },
        ),
        data: {
          'model': _config.model,
          'messages': messages,
          'stream': false,
          'options': {
            'temperature': _config.temperature,
            'num_predict': _config.maxTokens,
          },
        },
      );

      final data = response.data as Map<String, dynamic>;
      final message = data['message'] as Map<String, dynamic>?;
      
      if (message != null) {
        return _removeThinkTags(message['content'] as String);
      }
      
      throw Exception('未获取到有效回答');
    } catch (e) {
      _logger.e('Ollama API error: $e');
      rethrow;
    }
  }

  Future<String> _generateLocalAnswer(
    String question, {
    String? context,
    String? systemPrompt,
  }) async {
    if (!_localLLM.isLoaded) {
      throw Exception('本地模型未加载');
    }

    try {
      final prompt = _buildLocalPrompt(question, context, systemPrompt);
      final response = await _localLLM.generate(
        prompt,
        systemPrompt: systemPrompt,
        maxTokens: _config.maxTokens,
        temperature: _config.temperature,
      );
      
      return response;
    } catch (e) {
      _logger.e('Local LLM error: $e');
      rethrow;
    }
  }

  String _buildLocalPrompt(String question, String? context, String? systemPrompt) {
    final parts = <String>[];
    
    if (context != null && context.isNotEmpty) {
      parts.add('背景信息：$context');
    }
    
    parts.add(question);
    
    return parts.join('\n\n');
  }

  Future<String> _generateCustomAnswer(
    String question, {
    String? context,
    String? systemPrompt,
  }) async {
    final messages = _buildMessages(question, context, systemPrompt);

    try {
      final headers = <String, String>{
        'Content-Type': 'application/json',
      };
      
      if (_config.apiKey != null && _config.apiKey!.isNotEmpty) {
        headers['Authorization'] = 'Bearer ${_config.apiKey}';
      }

      final response = await _dio.post(
        '${_config.apiUrl}/chat/completions',
        options: Options(headers: headers),
        data: {
          'model': _config.model,
          'messages': messages,
          'max_tokens': _config.maxTokens,
          'temperature': _config.temperature,
        },
      );

      final data = response.data as Map<String, dynamic>;
      final choices = data['choices'] as List;
      
      if (choices.isNotEmpty) {
        return choices[0]['message']['content'] as String;
      }
      
      throw Exception('未获取到有效回答');
    } catch (e) {
      _logger.e('Custom API error: $e');
      rethrow;
    }
  }

  Stream<String> generateAnswerStream(
    String question, {
    String? context,
    String? systemPrompt,
  }) async* {
    await _ensureModelLoaded();
    
    switch (_config.providerType) {
      case LLMProviderType.openai:
        yield* _streamOpenAIAnswer(question, context: context, systemPrompt: systemPrompt);
        break;
      case LLMProviderType.ollama:
        yield* _streamOllamaAnswer(question, context: context, systemPrompt: systemPrompt);
        break;
      case LLMProviderType.local:
        yield* _streamLocalAnswer(question, context: context, systemPrompt: systemPrompt);
        break;
      case LLMProviderType.custom:
        yield* _streamCustomAnswer(question, context: context, systemPrompt: systemPrompt);
        break;
    }
  }

  Stream<String> _streamOpenAIAnswer(
    String question, {
    String? context,
    String? systemPrompt,
  }) async* {
    if (_config.apiKey?.isEmpty ?? true) {
      throw Exception('OpenAI API Key未配置');
    }

    final messages = _buildMessages(question, context, systemPrompt);

    try {
      final response = await _dio.post(
        '${_config.apiUrl}/chat/completions',
        options: Options(
          headers: {
            'Authorization': 'Bearer ${_config.apiKey}',
            'Content-Type': 'application/json',
          },
          responseType: ResponseType.stream,
        ),
        data: {
          'model': _config.model,
          'messages': messages,
          'max_tokens': _config.maxTokens,
          'temperature': _config.temperature,
          'stream': true,
        },
      );

      final stream = response.data.stream as Stream<List<int>>;
      
      await for (final chunk in stream) {
        final text = utf8.decode(chunk);
        final lines = text.split('\n');
        
        for (final line in lines) {
          if (line.startsWith('data: ')) {
            final data = line.substring(6);
            if (data == '[DONE]') break;
            
            try {
              final json = jsonDecode(data) as Map<String, dynamic>;
              final choices = json['choices'] as List;
              
              if (choices.isNotEmpty) {
                final delta = choices[0]['delta'] as Map<String, dynamic>?;
                final content = delta?['content'] as String?;
                
                if (content != null) {
                  yield content;
                }
              }
            } catch (_) {}
          }
        }
      }
    } catch (e) {
      _logger.e('OpenAI stream error: $e');
      rethrow;
    }
  }

  Stream<String> _streamOllamaAnswer(
    String question, {
    String? context,
    String? systemPrompt,
  }) async* {
    final messages = _buildMessages(question, context, systemPrompt);

    try {
      final response = await _dio.post(
        '${_config.apiUrl}/api/chat',
        options: Options(
          headers: {
            'Content-Type': 'application/json',
          },
          responseType: ResponseType.stream,
        ),
        data: {
          'model': _config.model,
          'messages': messages,
          'stream': true,
          'options': {
            'temperature': _config.temperature,
            'num_predict': _config.maxTokens,
          },
        },
      );

      final stream = response.data.stream as Stream<List<int>>;
      
      await for (final chunk in stream) {
        final text = utf8.decode(chunk);
        
        try {
          final json = jsonDecode(text) as Map<String, dynamic>;
          final message = json['message'] as Map<String, dynamic>?;
          final content = message?['content'] as String?;
          
          if (content != null) {
            yield content;
          }
        } catch (_) {}
      }
    } catch (e) {
      _logger.e('Ollama stream error: $e');
      rethrow;
    }
  }

  Stream<String> _streamLocalAnswer(
    String question, {
    String? context,
    String? systemPrompt,
  }) async* {
    if (!_localLLM.isLoaded) {
      throw Exception('本地模型未加载');
    }

    try {
      final prompt = _buildLocalPrompt(question, context, systemPrompt);
      yield* _localLLM.generateStream(
        prompt,
        systemPrompt: systemPrompt,
        maxTokens: _config.maxTokens,
        temperature: _config.temperature,
      );
    } catch (e) {
      _logger.e('Local LLM stream error: $e');
      rethrow;
    }
  }

  Stream<String> _streamCustomAnswer(
    String question, {
    String? context,
    String? systemPrompt,
  }) async* {
    final messages = _buildMessages(question, context, systemPrompt);

    try {
      final headers = <String, String>{
        'Content-Type': 'application/json',
      };
      
      if (_config.apiKey != null && _config.apiKey!.isNotEmpty) {
        headers['Authorization'] = 'Bearer ${_config.apiKey}';
      }

      final response = await _dio.post(
        '${_config.apiUrl}/chat/completions',
        options: Options(
          headers: headers,
          responseType: ResponseType.stream,
        ),
        data: {
          'model': _config.model,
          'messages': messages,
          'max_tokens': _config.maxTokens,
          'temperature': _config.temperature,
          'stream': true,
        },
      );

      final stream = response.data.stream as Stream<List<int>>;
      
      await for (final chunk in stream) {
        final text = utf8.decode(chunk);
        final lines = text.split('\n');
        
        for (final line in lines) {
          if (line.startsWith('data: ')) {
            final data = line.substring(6);
            if (data == '[DONE]') break;
            
            try {
              final json = jsonDecode(data) as Map<String, dynamic>;
              final choices = json['choices'] as List;
              
              if (choices.isNotEmpty) {
                final delta = choices[0]['delta'] as Map<String, dynamic>?;
                final content = delta?['content'] as String?;
                
                if (content != null) {
                  yield content;
                }
              }
            } catch (_) {}
          }
        }
      }
    } catch (e) {
      _logger.e('Custom stream error: $e');
      rethrow;
    }
  }

  Future<String> summarizeNotes(List<String> notes) async {
    await _ensureModelLoaded();
    
    final notesText = notes.join('\n');
    
    final messages = [
      {
        'role': 'system',
        'content': '整理笔记，列出要点。简洁输出。',
      },
      {
        'role': 'user',
        'content': notesText,
      },
    ];

    try {
      switch (_config.providerType) {
        case LLMProviderType.openai:
          return await _openAISummarize(messages);
        case LLMProviderType.ollama:
          return await _ollamaSummarize(messages);
        case LLMProviderType.local:
          return await _localSummarize(messages);
        case LLMProviderType.custom:
          return await _customSummarize(messages);
      }
    } catch (e) {
      _logger.e('Summarization error: $e');
      rethrow;
    }
  }

  Future<String> _openAISummarize(List<Map<String, String>> messages) async {
    if (_config.apiKey?.isEmpty ?? true) {
      throw Exception('OpenAI API Key未配置');
    }

    final response = await _dio.post(
      '${_config.apiUrl}/chat/completions',
      options: Options(
        headers: {
          'Authorization': 'Bearer ${_config.apiKey}',
          'Content-Type': 'application/json',
        },
      ),
      data: {
        'model': _config.model,
        'messages': messages,
        'max_tokens': _config.maxTokens,
        'temperature': 0.5,
      },
    );

    final data = response.data as Map<String, dynamic>;
    final choices = data['choices'] as List;
    
    if (choices.isNotEmpty) {
      return choices[0]['message']['content'] as String;
    }
    
    throw Exception('未获取到有效摘要');
  }
  
  Future<String> _localSummarize(List<Map<String, String>> messages) async {
    if (!_localLLM.isLoaded) {
      throw Exception('本地模型未加载');
    }

    try {
      final prompt = messages.map((m) => '${m['role']}: ${m['content']}').join('\n\n');
      final response = await _localLLM.generate(
        prompt,
        maxTokens: _config.maxTokens,
        temperature: 0.5,
      );
      
      return response;
    } catch (e) {
      _logger.e('Local LLM summarize error: $e');
      rethrow;
    }
  }

  Future<String> _ollamaSummarize(List<Map<String, String>> messages) async {
    final response = await _dio.post(
      '${_config.apiUrl}/api/chat',
      options: Options(
        headers: {
          'Content-Type': 'application/json',
        },
      ),
      data: {
        'model': _config.model,
        'messages': messages,
        'stream': false,
        'options': {
          'temperature': 0.5,
          'num_predict': _config.maxTokens,
        },
      },
    );

    final data = response.data as Map<String, dynamic>;
    final message = data['message'] as Map<String, dynamic>?;
    
    if (message != null) {
      return _removeThinkTags(message['content'] as String);
    }
    
    throw Exception('未获取到有效摘要');
  }

  Future<String> _customSummarize(List<Map<String, String>> messages) async {
    final headers = <String, String>{
      'Content-Type': 'application/json',
    };
    
    if (_config.apiKey != null && _config.apiKey!.isNotEmpty) {
      headers['Authorization'] = 'Bearer ${_config.apiKey}';
    }

    final response = await _dio.post(
      '${_config.apiUrl}/chat/completions',
      options: Options(headers: headers),
      data: {
        'model': _config.model,
        'messages': messages,
        'max_tokens': _config.maxTokens,
        'temperature': 0.5,
      },
    );

    final data = response.data as Map<String, dynamic>;
    final choices = data['choices'] as List;
    
    if (choices.isNotEmpty) {
      return choices[0]['message']['content'] as String;
    }
    
    throw Exception('未获取到有效摘要');
  }

  List<Map<String, String>> _buildMessages(
    String question,
    String? context,
    String? systemPrompt,
  ) {
    final messages = <Map<String, String>>[
      {
        'role': 'system',
        'content': systemPrompt ?? _getDefaultSystemPrompt(),
      },
    ];

    if (context != null && context.isNotEmpty) {
      messages.add({
        'role': 'user',
        'content': '背景：$context\n\n问题：$question',
      });
    } else {
      messages.add({
        'role': 'user',
        'content': question,
      });
    }

    return messages;
  }

  String _getDefaultSystemPrompt() {
    return '你是课堂助手。简洁回答问题。';
  }

  Future<String> optimizeAsrText(String text) async {
    await _ensureModelLoaded();
    
    final messages = [
      {
        'role': 'system',
        'content': '''你是一个专业的语音识别文本校正助手。请修正以下语音识别文本中的错误：

1. 专业术语纠正（计算机组成原理/数据库相关）：
   CPU相关：
   - "CDU/CBU/CU/CVU/C you/C音" → "CPU"
   
   IO设备相关：
   - "LO设备/L设备/IOS设备" → "IO设备"
   
   计算机体系结构：
   - "罗一曼" → "冯·诺依曼"
   - "总现阶段" → "总线阶段"
   - "单走线/单属线" → "单总线"
   - "分散链接/分产年接" → "分散连接"
   
   工作方式：
   - "串新工作/创新工作" → "串行工作"
   - "中断旁式/中间没方式" → "中断方式"
   - "义度工作/预部工作" → "异步工作"
   
   编址相关：
   - "社会/设会" → "设备"
   - "编制/编子/编持" → "编址"
   - "统一编辑/统一电" → "统一编址"
   - "不同一编制" → "不统一编址"
   
   存储器相关：
   - "主存权利" → "主存储器"
   - "存储仿" → "存储器"
   - "MEQ" → "MREQ"
   
   数据库相关：
   - "出发机/除化器/出金协/处罚记" → "触发器"
   - "出要机/除发题/出要性" → "触发器体"
   - "滚 back" → "rollback"
   - "语句笔句/行列除化题" → "语句级触发器"
   - "然语距离" → "行级触发器"
   - "upper除化器" → "BEFORE触发器"
   - "收会领域/收会语据" → "SQL语句"
   - "增三改" → "INSERT/UPDATE/DELETE"
   
   其他：
   - "公此有我5个部分子升" → "共有五个部分组成"
   - "千三万年" → "千差万别"
   - "讲即系话" → "简单来说"
   - "enter特" → "ENTER"
   - "s a" → "SA"（作为表名/变量名时）

2. 标点符号：添加正确的标点符号，使语句通顺。

3. 口语化处理：
   - 去除无意义的语气词（如单独的"呃"、"啊"、"嗯"）
   - 去除单独的英文单词（如单独的"So."、"Yeah."、"But."、"Yes."、"I."、"Oh."、"To."、"No."）
   - 保留有意义的口语化表达

4. 保持原意：不要改变原文的核心意思。

只输出修正后的文本，不要解释。''',
      },
      {
        'role': 'user',
        'content': text,
      },
    ];

    try {
      switch (_config.providerType) {
        case LLMProviderType.openai:
          return await _openAIOptimize(messages);
        case LLMProviderType.ollama:
          return await _ollamaOptimize(messages);
        case LLMProviderType.local:
          return await _localOptimize(messages);
        case LLMProviderType.custom:
          return await _customOptimize(messages);
      }
    } catch (e) {
      _logger.e('ASR optimization error: $e');
      rethrow;
    }
  }

  Future<String> _openAIOptimize(List<Map<String, String>> messages) async {
    if (_config.apiKey?.isEmpty ?? true) {
      throw Exception('OpenAI API Key未配置');
    }

    final response = await _dio.post(
      '${_config.apiUrl}/chat/completions',
      options: Options(
        headers: {
          'Authorization': 'Bearer ${_config.apiKey}',
          'Content-Type': 'application/json',
        },
      ),
      data: {
        'model': _config.model,
        'messages': messages,
        'max_tokens': 1024,
        'temperature': 0.3,
      },
    );

    final data = response.data as Map<String, dynamic>;
    final choices = data['choices'] as List;
    
    if (choices.isNotEmpty) {
      return _removeThinkTags(choices[0]['message']['content'] as String);
    }
    
    throw Exception('未获取到优化结果');
  }

  Future<String> _ollamaOptimize(List<Map<String, String>> messages) async {
    final response = await _dio.post(
      '${_config.apiUrl}/api/chat',
      options: Options(
        headers: {
          'Content-Type': 'application/json',
        },
      ),
      data: {
        'model': _config.model,
        'messages': messages,
        'stream': false,
        'options': {
          'temperature': 0.3,
          'num_predict': 1024,
        },
      },
    );

    final data = response.data as Map<String, dynamic>;
    final message = data['message'] as Map<String, dynamic>?;
    
    if (message != null) {
      return _removeThinkTags(message['content'] as String);
    }
    
    throw Exception('未获取到优化结果');
  }

  Future<String> _localOptimize(List<Map<String, String>> messages) async {
    if (!_localLLM.isLoaded) {
      throw Exception('本地模型未加载');
    }

    try {
      final prompt = messages.map((m) => '${m['role']}: ${m['content']}').join('\n\n');
      final response = await _localLLM.generate(
        prompt,
        maxTokens: 1024,
        temperature: 0.3,
      );
      
      return response;
    } catch (e) {
      _logger.e('Local LLM optimize error: $e');
      rethrow;
    }
  }

  Future<String> _customOptimize(List<Map<String, String>> messages) async {
    final headers = <String, String>{
      'Content-Type': 'application/json',
    };
    
    if (_config.apiKey != null && _config.apiKey!.isNotEmpty) {
      headers['Authorization'] = 'Bearer ${_config.apiKey}';
    }

    final response = await _dio.post(
      '${_config.apiUrl}/chat/completions',
      options: Options(headers: headers),
      data: {
        'model': _config.model,
        'messages': messages,
        'max_tokens': 1024,
        'temperature': 0.3,
      },
    );

    final data = response.data as Map<String, dynamic>;
    final choices = data['choices'] as List;
    
    if (choices.isNotEmpty) {
      return choices[0]['message']['content'] as String;
    }
    
    throw Exception('未获取到优化结果');
  }

  Future<bool> testConnection() async {
    try {
      switch (_config.providerType) {
        case LLMProviderType.openai:
          return await _testOpenAIConnection();
        case LLMProviderType.ollama:
          return await _testOllamaConnection();
        case LLMProviderType.local:
          return await _testLocalConnection();
        case LLMProviderType.custom:
          return await _testCustomConnection();
      }
    } catch (e) {
      _logger.e('Connection test failed: $e');
      return false;
    }
  }

  Future<bool> _testLocalConnection() async {
    await _ensureModelLoaded();
    return _localLLM.isLoaded;
  }

  Future<bool> _testOpenAIConnection() async {
    try {
      final response = await _dio.get(
        '${_config.apiUrl}/models',
        options: Options(
          headers: {
            'Authorization': 'Bearer ${_config.apiKey}',
          },
        ),
      );
      return response.statusCode == 200;
    } catch (e) {
      return false;
    }
  }

  Future<bool> _testOllamaConnection() async {
    try {
      final response = await _dio.get('${_config.apiUrl}/api/tags');
      return response.statusCode == 200;
    } catch (e) {
      return false;
    }
  }

  Future<bool> _testCustomConnection() async {
    try {
      final headers = <String, String>{};
      if (_config.apiKey != null && _config.apiKey!.isNotEmpty) {
        headers['Authorization'] = 'Bearer ${_config.apiKey}';
      }
      
      final response = await _dio.get(
        '${_config.apiUrl}/models',
        options: Options(headers: headers),
      );
      return response.statusCode == 200;
    } catch (e) {
      return false;
    }
  }

  Future<List<String>> getAvailableModels() async {
    try {
      switch (_config.providerType) {
        case LLMProviderType.openai:
          return await _getOpenAIModels();
        case LLMProviderType.ollama:
          return await _getOllamaModels();
        case LLMProviderType.local:
          return await _getLocalModels();
        case LLMProviderType.custom:
          return await _getCustomModels();
      }
    } catch (e) {
      _logger.e('Failed to get models: $e');
      return [];
    }
  }

  Future<List<String>> _getLocalModels() async {
    return await _localLLM.getAvailableModels();
  }

  Future<List<String>> _getOpenAIModels() async {
    final response = await _dio.get(
      '${_config.apiUrl}/models',
      options: Options(
        headers: {
          'Authorization': 'Bearer ${_config.apiKey}',
        },
      ),
    );

    final data = response.data as Map<String, dynamic>;
    final models = data['data'] as List;
    
    return models
        .map((m) => m['id'] as String)
        .where((id) => id.contains('gpt'))
        .toList();
  }

  Future<List<String>> _getOllamaModels() async {
    final response = await _dio.get('${_config.apiUrl}/api/tags');
    
    final data = response.data as Map<String, dynamic>;
    final models = data['models'] as List;
    
    return models.map((m) => m['name'] as String).toList();
  }

  Future<List<String>> _getCustomModels() async {
    final headers = <String, String>{};
    if (_config.apiKey != null && _config.apiKey!.isNotEmpty) {
      headers['Authorization'] = 'Bearer ${_config.apiKey}';
    }
    
    final response = await _dio.get(
      '${_config.apiUrl}/models',
      options: Options(headers: headers),
    );

    final data = response.data as Map<String, dynamic>;
    final models = data['data'] as List;
    
    return models.map((m) => m['id'] as String).toList();
  }

  String _removeThinkTags(String text) {
    var result = text;
    while (result.contains('<think')) {
      final startIndex = result.indexOf('<think');
      final startClose = result.indexOf('>', startIndex);
      if (startClose == -1) {
        result = result.substring(0, startIndex);
        break;
      }
      
      final endIndex = result.indexOf('</think');
      if (endIndex == -1 || endIndex < startClose) {
        result = result.substring(0, startIndex);
        break;
      }
      
      final endClose = result.indexOf('>', endIndex);
      if (endClose == -1) {
        result = result.substring(0, startIndex);
        break;
      }
      
      result = result.substring(0, startIndex) + result.substring(endClose + 1);
    }
    return result.trim();
  }

  void dispose() {
    _unloadTimer?.cancel();
    _localLLM.dispose();
    _streamController.close();
    _modelLoadProgress.close();
    _statusController.close();
    _dio.close();
  }
}
