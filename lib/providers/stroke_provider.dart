import 'dart:ui';
import 'package:flutter/foundation.dart';
import 'package:hive/hive.dart';
import 'package:uuid/uuid.dart';
import '../models/stroke.dart';

class StrokeProvider extends ChangeNotifier {
  final Uuid _uuid = const Uuid();

  List<Stroke> _strokes = [];
  Stroke? _currentStroke;
  bool _isDrawing = false;
  Color _currentColor = const Color(0xFFFF0000);
  double _currentStrokeWidth = 2.0;
  bool _isEraser = false;
  late Box _strokeBox;
  String _currentCategory = 'default';

  int? _eraserPageNumber;
  List<Offset> _eraserPath = [];

  List<Stroke> get strokes => List.unmodifiable(_strokes);
  Stroke? get currentStroke => _currentStroke;
  bool get isDrawing => _isDrawing;
  Color get currentColor => _currentColor;
  double get currentStrokeWidth => _currentStrokeWidth;
  bool get isEraser => _isEraser;
  String get currentCategory => _currentCategory;

  StrokeProvider() {
    _init();
  }

  void _init() {
    _strokeBox = Hive.box('settings');
    _loadCategories();
  }

  List<String> _categories = ['default'];

  List<String> get categories => List.unmodifiable(_categories);

  void _loadCategories() {
    final savedCategories = _strokeBox.get('stroke_categories') as List?;
    if (savedCategories != null && savedCategories.isNotEmpty) {
      _categories = List<String>.from(savedCategories);
      final savedCurrentCategory = _strokeBox.get('current_stroke_category') as String?;
      if (savedCurrentCategory != null && _categories.contains(savedCurrentCategory)) {
        _currentCategory = savedCurrentCategory;
      } else if (!_categories.contains('default')) {
        _categories.insert(0, 'default');
      }
    } else {
      _categories = ['default'];
    }
    _loadStrokes();
  }

  Future<void> _saveCategories() async {
    await _strokeBox.put('stroke_categories', _categories);
    await _strokeBox.put('current_stroke_category', _currentCategory);
  }

  void setCurrentCategory(String category) {
    if (_currentCategory != category && _categories.contains(category)) {
      _currentCategory = category;
      _saveCategories();
      _loadStrokes();
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
    if (name == 'default') return false;
    if (_categories.contains(name)) {
      _strokes.removeWhere((s) => s.category == name);
      _saveStrokes();
      _categories.remove(name);
      if (_currentCategory == name) {
        _currentCategory = 'default';
        _loadStrokes();
      }
      await _saveCategories();
      notifyListeners();
      return true;
    }
    return false;
  }

  void _loadStrokes() {
    final strokesJson = _strokeBox.get('pdf_strokes') as List?;
    if (strokesJson != null) {
      _strokes = strokesJson
          .map((s) => Stroke.fromJson(Map<String, dynamic>.from(s as Map)))
          .where((s) => s.category == _currentCategory)
          .toList();
    } else {
      _strokes = [];
    }
  }

  void _saveStrokes() {
    final allStrokesJson = _strokeBox.get('pdf_strokes') as List? ?? [];
    final allStrokes = allStrokesJson
        .map((s) => Stroke.fromJson(Map<String, dynamic>.from(s as Map)))
        .where((s) => s.category != _currentCategory)
        .toList();
    allStrokes.addAll(_strokes);
    _strokeBox.put(
      'pdf_strokes',
      allStrokes.map((s) => s.toJson()).toList(),
    );
  }

  void startStroke(int pageNumber, Offset pdfPoint) {
    _isDrawing = true;
    _currentStroke = Stroke(
      id: _uuid.v4(),
      pageNumber: pageNumber,
      points: [pdfPoint],
      color: _currentColor,
      strokeWidth: _currentStrokeWidth,
      category: _currentCategory,
    );
    notifyListeners();
  }

  void addPoint(Offset pdfPoint) {
    if (_currentStroke == null || !_isDrawing) return;

    _currentStroke = _currentStroke!.copyWith(
      points: [..._currentStroke!.points, pdfPoint],
    );
    notifyListeners();
  }

  void endStroke() {
    if (_currentStroke == null || !_isDrawing) return;

    _strokes.add(_currentStroke!);
    _saveStrokes();
    _currentStroke = null;
    _isDrawing = false;
    notifyListeners();
  }

  void startEraser(int pageNumber, Offset pdfPoint) {
    _isDrawing = true;
    _eraserPageNumber = pageNumber;
    _eraserPath = [pdfPoint];
    _eraseAtPoint(pdfPoint, pageNumber);
    notifyListeners();
  }

  void addEraserPoint(Offset pdfPoint) {
    if (!_isDrawing || _eraserPageNumber == null) return;

    _eraserPath.add(pdfPoint);
    _eraseAtPoint(pdfPoint, _eraserPageNumber!);
    notifyListeners();
  }

  void endEraser() {
    _isDrawing = false;
    _eraserPageNumber = null;
    _eraserPath.clear();
    notifyListeners();
  }

  void _eraseAtPoint(Offset point, int pageNumber) {
    final eraserRadius = _currentStrokeWidth * 10;
    final toRemove = <String>[];

    for (final stroke in _strokes) {
      if (stroke.pageNumber != pageNumber) continue;

      for (final strokePoint in stroke.points) {
        if ((strokePoint - point).distance < eraserRadius) {
          toRemove.add(stroke.id);
          break;
        }
      }
    }

    if (toRemove.isNotEmpty) {
      _strokes.removeWhere((s) => toRemove.contains(s.id));
      _saveStrokes();
    }
  }

  List<Stroke> getStrokesForPage(int pageNumber) {
    return _strokes.where((s) => s.pageNumber == pageNumber).toList();
  }

  void undoLastStroke() {
    if (_strokes.isEmpty) return;
    _strokes.removeLast();
    _saveStrokes();
    notifyListeners();
  }

  void clearStrokesForPage(int pageNumber) {
    _strokes.removeWhere((s) => s.pageNumber == pageNumber);
    _saveStrokes();
    notifyListeners();
  }

  void clearCurrentCategoryStrokes() {
    _strokes.clear();
    _saveStrokes();
    notifyListeners();
  }

  void clearAllStrokes() {
    _strokeBox.put('pdf_strokes', []);
    _strokes.clear();
    notifyListeners();
  }

  void setColor(Color color) {
    _currentColor = color;
    _isEraser = false;
    notifyListeners();
  }

  void setStrokeWidth(double width) {
    _currentStrokeWidth = width;
    notifyListeners();
  }

  void toggleEraser() {
    _isEraser = !_isEraser;
    notifyListeners();
  }

  void setEraser(bool value) {
    _isEraser = value;
    notifyListeners();
  }

  int getStrokeCountByCategory(String category) {
    final strokesJson = _strokeBox.get('pdf_strokes') as List?;
    if (strokesJson == null) return 0;
    return strokesJson
        .map((s) => Stroke.fromJson(Map<String, dynamic>.from(s as Map)))
        .where((s) => s.category == category)
        .length;
  }

  List<Map<String, dynamic>> getStrokesDataForPage(int pageNumber) {
    return getStrokesForPage(pageNumber).map((s) => {
      'points': s.points,
      'color': s.color,
      'strokeWidth': s.strokeWidth,
    }).toList();
  }
}
