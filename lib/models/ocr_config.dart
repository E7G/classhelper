enum OCRMode {
  local,
  api,
}

enum OCRLanguage {
  ch,
  en,
  chEn,
  japanese,
  korean,
}

class OCRConfig {
  final OCRMode mode;
  final OCRLanguage language;
  final String? apiUrl;
  final String? apiKey;
  final bool autoOCR;

  const OCRConfig({
    this.mode = OCRMode.local,
    this.language = OCRLanguage.chEn,
    this.apiUrl,
    this.apiKey,
    this.autoOCR = true,
  });

  OCRConfig copyWith({
    OCRMode? mode,
    OCRLanguage? language,
    String? apiUrl,
    String? apiKey,
    bool? autoOCR,
  }) {
    return OCRConfig(
      mode: mode ?? this.mode,
      language: language ?? this.language,
      apiUrl: apiUrl ?? this.apiUrl,
      apiKey: apiKey ?? this.apiKey,
      autoOCR: autoOCR ?? this.autoOCR,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'mode': mode.index,
      'language': language.index,
      'api_url': apiUrl,
      'api_key': apiKey,
      'auto_ocr': autoOCR,
    };
  }

  factory OCRConfig.fromJson(Map<String, dynamic> json) {
    return OCRConfig(
      mode: OCRMode.values[(json['mode'] as int?) ?? 0],
      language: OCRLanguage.values[(json['language'] as int?) ?? 2],
      apiUrl: json['api_url'] as String?,
      apiKey: json['api_key'] as String?,
      autoOCR: (json['auto_ocr'] as bool?) ?? true,
    );
  }
}
