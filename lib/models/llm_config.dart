enum LLMProviderType {
  openai,
  ollama,
  local,
  custom,
}

class LLMConfig {
  final LLMProviderType providerType;
  final String apiUrl;
  final String? apiKey;
  final String model;
  final int maxTokens;
  final double temperature;
  final bool isLocal;
  final String? bingApiKey;
  final bool searchEnhance;

  const LLMConfig({
    this.providerType = LLMProviderType.openai,
    this.apiUrl = 'https://api.openai.com/v1',
    this.apiKey,
    this.model = 'gpt-4',
    this.maxTokens = 2000,
    this.temperature = 0.7,
    this.isLocal = false,
    this.bingApiKey,
    this.searchEnhance = false,
  });

  LLMConfig copyWith({
    LLMProviderType? providerType,
    String? apiUrl,
    String? apiKey,
    String? model,
    int? maxTokens,
    double? temperature,
    bool? isLocal,
    String? bingApiKey,
    bool? searchEnhance,
  }) {
    return LLMConfig(
      providerType: providerType ?? this.providerType,
      apiUrl: apiUrl ?? this.apiUrl,
      apiKey: apiKey ?? this.apiKey,
      model: model ?? this.model,
      maxTokens: maxTokens ?? this.maxTokens,
      temperature: temperature ?? this.temperature,
      isLocal: isLocal ?? this.isLocal,
      bingApiKey: bingApiKey ?? this.bingApiKey,
      searchEnhance: searchEnhance ?? this.searchEnhance,
    );
  }

  static LLMConfig openai({
    String apiKey = '',
    String model = 'gpt-4',
  }) {
    return LLMConfig(
      providerType: LLMProviderType.openai,
      apiUrl: 'https://api.openai.com/v1',
      apiKey: apiKey,
      model: model,
      isLocal: false,
    );
  }

  static LLMConfig ollama({
    String host = 'localhost',
    int port = 11434,
    String model = 'llama3',
  }) {
    return LLMConfig(
      providerType: LLMProviderType.ollama,
      apiUrl: 'http://$host:$port',
      model: model,
      isLocal: true,
    );
  }

  static LLMConfig local({
    String model = 'Qwen3.5-0.8B-Q4_K_M.gguf',
  }) {
    return LLMConfig(
      providerType: LLMProviderType.local,
      model: model,
      isLocal: true,
    );
  }

  static LLMConfig custom({
    required String apiUrl,
    String? apiKey,
    String model = 'default',
  }) {
    return LLMConfig(
      providerType: LLMProviderType.custom,
      apiUrl: apiUrl,
      apiKey: apiKey,
      model: model,
      isLocal: false,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'provider_type': providerType.index,
      'api_url': apiUrl,
      'api_key': apiKey,
      'model': model,
      'max_tokens': maxTokens,
      'temperature': temperature,
      'is_local': isLocal,
    };
  }

  factory LLMConfig.fromJson(Map<String, dynamic> json) {
    return LLMConfig(
      providerType: LLMProviderType.values[json['provider_type'] ?? 0],
      apiUrl: json['api_url'] ?? 'https://api.openai.com/v1',
      apiKey: json['api_key'],
      model: json['model'] ?? 'gpt-4',
      maxTokens: json['max_tokens'] ?? 2000,
      temperature: (json['temperature'] ?? 0.7).toDouble(),
      isLocal: json['is_local'] ?? false,
    );
  }
}
