import 'package:flutter/foundation.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';
import '../models/ocr_config.dart';

TextRecognizer? _textRecognizer;
OCRLanguage? _currentLanguage;

Future<bool> isAvailable() async {
  return !kIsWeb && 
      (defaultTargetPlatform == TargetPlatform.android ||
       defaultTargetPlatform == TargetPlatform.iOS);
}

Future<String?> recognizeText(String imagePath, OCRLanguage language) async {
  if (!await isAvailable()) return null;

  try {
    if (_textRecognizer == null || _currentLanguage != language) {
      _textRecognizer?.close();
      final script = _getScriptFromLanguage(language);
      _textRecognizer = TextRecognizer(script: script);
      _currentLanguage = language;
    }

    final inputImage = InputImage.fromFilePath(imagePath);
    final recognizedText = await _textRecognizer!.processImage(inputImage);
    return recognizedText.text;
  } catch (e) {
    return null;
  }
}

TextRecognitionScript _getScriptFromLanguage(OCRLanguage language) {
  switch (language) {
    case OCRLanguage.ch:
    case OCRLanguage.chEn:
      return TextRecognitionScript.chinese;
    case OCRLanguage.japanese:
      return TextRecognitionScript.japanese;
    case OCRLanguage.korean:
      return TextRecognitionScript.korean;
    case OCRLanguage.en:
      return TextRecognitionScript.latin;
  }
}
