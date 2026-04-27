enum ASRModelSource { github, modelscope }

enum ASRModelSize { size06B, size17B }

class ASRModelConfig {
  ASRModelSource source;
  ASRModelSize size;
  String githubProxyUrl;
  String? modelDir;
  String? vadModelPath;

  ASRModelConfig({
    this.source = ASRModelSource.modelscope,
    this.size = ASRModelSize.size06B,
    this.githubProxyUrl = '',
    this.modelDir,
    this.vadModelPath,
  });

  String get sizeLabel {
    switch (size) {
      case ASRModelSize.size06B:
        return '0.6B';
      case ASRModelSize.size17B:
        return '1.7B';
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

  String get modelName => 'Qwen3-ASR $sizeLabel';

  String get asrModelArchiveName {
    switch (size) {
      case ASRModelSize.size06B:
        return 'sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25';
      case ASRModelSize.size17B:
        return 'sherpa-onnx-qwen3-asr-1.7B-int8-2026-03-25';
    }
  }

  String get githubAsrModelUrl {
    final baseUrl =
        'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$asrModelArchiveName.tar.bz2';
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

  String get vadModelUrl {
    if (source == ASRModelSource.modelscope) {
      return modelscopeVadUrl;
    }
    return githubVadModelUrl;
  }

  String get modelscopeModelSubdir {
    switch (size) {
      case ASRModelSize.size06B:
        return 'model_0.6B';
      case ASRModelSize.size17B:
        return 'model_1.7B';
    }
  }

  static const String modelscopeBaseUrl =
      'https://modelscope.cn/models/zengshuishui/Qwen3-ASR-onnx/resolve/master';

  List<String> get modelscopeAsrFiles => [
    '$modelscopeModelSubdir/conv_frontend.onnx',
    '$modelscopeModelSubdir/encoder.int8.onnx',
    '$modelscopeModelSubdir/decoder.int8.onnx',
  ];

  List<String> get modelscopeTokenizerFiles => [
    'tokenizer/merges.txt',
    'tokenizer/tokenizer_config.json',
    'tokenizer/vocab.json',
  ];

  String modelscopeFileUrl(String filePath) =>
      '$modelscopeBaseUrl/$filePath';

  Map<String, dynamic> toJson() => {
    'source': source.index,
    'size': size.index,
    'githubProxyUrl': githubProxyUrl,
    'modelDir': modelDir,
    'vadModelPath': vadModelPath,
  };

  factory ASRModelConfig.fromJson(Map<dynamic, dynamic> json) =>
      ASRModelConfig(
        source: ASRModelSource.values[json['source'] as int? ?? 1],
        size: ASRModelSize.values[json['size'] as int? ?? 0],
        githubProxyUrl: json['githubProxyUrl'] as String? ?? '',
        modelDir: json['modelDir'] as String?,
        vadModelPath: json['vadModelPath'] as String?,
      );

  bool get isModelReady => modelDir != null && modelDir!.isNotEmpty;

  bool get isVadReady => vadModelPath != null && vadModelPath!.isNotEmpty;

  bool get isReady => isModelReady && isVadReady;
}
