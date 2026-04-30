enum ASRModelSource { github, modelscope }

enum ASRModelType { senseVoice, qwen3Asr }

class ASRModelConfig {
  ASRModelSource source;
  ASRModelType modelType;
  String githubProxyUrl;
  String? modelDir;
  String? vadModelPath;

  ASRModelConfig({
    this.source = ASRModelSource.modelscope,
    this.modelType = ASRModelType.senseVoice,
    this.githubProxyUrl = '',
    this.modelDir,
    this.vadModelPath,
  });

  String get modelTypeLabel {
    switch (modelType) {
      case ASRModelType.senseVoice:
        return 'SenseVoice';
      case ASRModelType.qwen3Asr:
        return 'Qwen3-ASR';
    }
  }

  String get sourceLabel {
    switch (source) {
      case ASRModelSource.github:
        return 'GitHub';
      case ASRModelSource.modelscope:
        return 'ModelScope';
    }
  }

  String get modelName => modelTypeLabel;

  String get senseVoiceArchiveName =>
      'sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09';

  String get githubAsrModelUrl {
    String archiveName;
    switch (modelType) {
      case ASRModelType.senseVoice:
        archiveName = '$senseVoiceArchiveName.tar.bz2';
      case ASRModelType.qwen3Asr:
        archiveName = 'sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2';
    }
    final baseUrl =
        'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archiveName';
    if (githubProxyUrl.isNotEmpty) {
      return '$githubProxyUrl$baseUrl';
    }
    return baseUrl;
  }

  String get githubVadModelUrl {
    const baseUrl =
        'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx';
    if (githubProxyUrl.isNotEmpty) {
      return '$githubProxyUrl$baseUrl';
    }
    return baseUrl;
  }

  static const String modelscopeVadUrl =
      'https://modelscope.cn/models/xnnehang/k2-fsa-silero-vad/resolve/master/silero_vad.onnx';

  static const String modelscopeSenseVoiceUrl =
      'https://modelscope.cn/models/xiaowangge/sherpa-onnx-sense-voice-small/resolve/master';

  String get vadModelUrl {
    if (source == ASRModelSource.modelscope) {
      return modelscopeVadUrl;
    }
    return githubVadModelUrl;
  }

  List<String> get modelscopeAsrFiles {
    switch (modelType) {
      case ASRModelType.senseVoice:
        return [
          'model_q8.onnx',
          'tokens.txt',
        ];
      case ASRModelType.qwen3Asr:
        return [
          'model_0.6B/conv_frontend.onnx',
          'model_0.6B/encoder.int8.onnx',
          'model_0.6B/decoder.int8.onnx',
        ];
    }
  }

  List<String> get modelscopeTokenizerFiles {
    switch (modelType) {
      case ASRModelType.senseVoice:
        return [];
      case ASRModelType.qwen3Asr:
        return [
          'tokenizer/merges.txt',
          'tokenizer/tokenizer_config.json',
          'tokenizer/vocab.json',
        ];
    }
  }

  String modelscopeFileUrl(String filePath) {
    switch (modelType) {
      case ASRModelType.senseVoice:
        return '$modelscopeSenseVoiceUrl/$filePath';
      case ASRModelType.qwen3Asr:
        return 'https://modelscope.cn/models/zengshuishui/Qwen3-ASR-onnx/resolve/master/$filePath';
    }
  }

  Map<String, dynamic> toJson() => {
        'source': source.index,
        'modelType': modelType.index,
        'githubProxyUrl': githubProxyUrl,
        'modelDir': modelDir,
        'vadModelPath': vadModelPath,
      };

  factory ASRModelConfig.fromJson(Map<dynamic, dynamic> json) =>
      ASRModelConfig(
        source: ASRModelSource.values[json['source'] as int? ?? 1],
        modelType: ASRModelType.values[json['modelType'] as int? ?? 0],
        githubProxyUrl: json['githubProxyUrl'] as String? ?? '',
        modelDir: json['modelDir'] as String?,
        vadModelPath: json['vadModelPath'] as String?,
      );

  bool get isModelReady => modelDir != null && modelDir!.isNotEmpty;

  bool get isVadReady => vadModelPath != null && vadModelPath!.isNotEmpty;

  bool get isReady => isModelReady && isVadReady;
}
