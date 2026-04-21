import 'package:flutter/foundation.dart';
import 'package:hive/hive.dart';
import 'package:path/path.dart' as path;
import '../models/note.dart';
import '../services/note_service.dart';

class NoteProvider extends ChangeNotifier {
  final NoteService _noteService = NoteService();

  List<Note> _notes = [];
  List<Note> _filteredNotes = [];
  String _searchQuery = '';
  NoteType? _filterType;
  String? _errorMessage;
  String _currentCategory = 'default';

  List<Note> get notes => List.unmodifiable(_filteredNotes.isEmpty ? _notes : _filteredNotes);
  String get searchQuery => _searchQuery;
  NoteType? get filterType => _filterType;
  String? get errorMessage => _errorMessage;
  String get currentCategory => _currentCategory;

  NoteProvider() {
    _init();
  }

  Future<void> _init() async {
    await _noteService.init();
    _loadCategories();
    _loadNotes();
  }

  List<String> _categories = ['default'];

  List<String> get categories => List.unmodifiable(_categories);

  void _loadCategories() {
    _categories = ['default'];
    final savedCategories = Hive.box('settings').get('note_categories') as List?;
    if (savedCategories != null) {
      _categories = List<String>.from(savedCategories);
    }
  }

  Future<void> _saveCategories() async {
    await Hive.box('settings').put('note_categories', _categories);
  }

  void setCurrentCategory(String category) {
    if (_currentCategory != category) {
      _currentCategory = category;
      _loadNotes();
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
      await _noteService.deleteNotesByCategory(name);
      _categories.remove(name);
      if (_currentCategory == name) {
        _currentCategory = 'default';
        _loadNotes();
      }
      await _saveCategories();
      notifyListeners();
      return true;
    }
    return false;
  }

  void _loadNotes() {
    _notes = _noteService.getNotesByCategory(_currentCategory);
    _applyFilters();
    notifyListeners();
  }

  void setSearchQuery(String query) {
    _searchQuery = query;
    _applyFilters();
    notifyListeners();
  }

  void setFilterType(NoteType? type) {
    _filterType = type;
    _applyFilters();
    notifyListeners();
  }

  void _applyFilters() {
    var result = _notes;

    if (_filterType != null) {
      result = result.where((n) => n.type == _filterType).toList();
    }

    if (_searchQuery.isNotEmpty) {
      result = _noteService.searchNotesInCategory(_searchQuery, _currentCategory);
    }

    _filteredNotes = result;
  }

  Future<Note> createNote({
    required String content,
    NoteType type = NoteType.asr,
    List<String> tags = const [],
    String? summary,
    double? confidence,
    String? pdfPath,
    int? pdfPage,
    double? pdfOffsetX,
    double? pdfOffsetY,
    String? imagePath,
    String? category,
  }) async {
    String effectiveCategory;
    if (category != null && category.isNotEmpty) {
      effectiveCategory = category;
    } else if (pdfPath != null && pdfPath.isNotEmpty) {
      effectiveCategory = path.basename(pdfPath);
      if (!_categories.contains(effectiveCategory)) {
        _categories.add(effectiveCategory);
        await _saveCategories();
      }
    } else {
      effectiveCategory = _currentCategory;
    }

    try {
      final note = await _noteService.createNote(
        content: content,
        type: type,
        tags: tags,
        summary: summary,
        confidence: confidence,
        pdfPath: pdfPath,
        pdfPage: pdfPage,
        pdfOffsetX: pdfOffsetX,
        pdfOffsetY: pdfOffsetY,
        imagePath: imagePath,
        category: effectiveCategory,
      );

      _notes.insert(0, note);
      _applyFilters();
      notifyListeners();

      return note;
    } catch (e) {
      _errorMessage = '创建笔记失败: $e';
      notifyListeners();
      rethrow;
    }
  }

  Future<void> updateNote(Note note) async {
    try {
      final updatedNote = await _noteService.updateNote(note);

      final index = _notes.indexWhere((n) => n.id == note.id);
      if (index != -1) {
        _notes[index] = updatedNote;
      }

      _applyFilters();
      notifyListeners();
    } catch (e) {
      _errorMessage = '更新笔记失败: $e';
      notifyListeners();
      rethrow;
    }
  }

  Future<void> deleteNote(String id) async {
    try {
      await _noteService.deleteNote(id);
      _notes.removeWhere((n) => n.id == id);
      _applyFilters();
      notifyListeners();
    } catch (e) {
      _errorMessage = '删除笔记失败: $e';
      notifyListeners();
      rethrow;
    }
  }

  Future<void> addTags(String noteId, List<String> tags) async {
    try {
      final updatedNote = await _noteService.addTags(noteId, tags);

      final index = _notes.indexWhere((n) => n.id == noteId);
      if (index != -1) {
        _notes[index] = updatedNote;
      }

      _applyFilters();
      notifyListeners();
    } catch (e) {
      _errorMessage = '添加标签失败: $e';
      notifyListeners();
      rethrow;
    }
  }

  Future<void> removeTag(String noteId, String tag) async {
    try {
      final updatedNote = await _noteService.removeTag(noteId, tag);

      final index = _notes.indexWhere((n) => n.id == noteId);
      if (index != -1) {
        _notes[index] = updatedNote;
      }

      _applyFilters();
      notifyListeners();
    } catch (e) {
      _errorMessage = '移除标签失败: $e';
      notifyListeners();
      rethrow;
    }
  }

  List<Note> getNotesByDate(DateTime date) {
    return _noteService.getNotesByDateAndCategory(date, _currentCategory);
  }

  List<Note> getNotesForPdfPage(String pdfPath, int page) {
    return _notes.where((n) => n.pdfPath == pdfPath && n.pdfPage == page).toList();
  }

  List<Note> getNotesForPdf(String pdfPath) {
    return _noteService.getNotesByPdfPath(pdfPath);
  }

  Map<String, int> getTagStatistics() {
    return _noteService.getTagStatisticsForCategory(_currentCategory);
  }

  int getNoteCount() {
    return _noteService.getNoteCountByCategory(_currentCategory);
  }

  int getNoteCountByType(NoteType type) {
    return _notes.where((n) => n.type == type).length;
  }

  int getNoteCountByCategory(String category) {
    return _noteService.getNoteCountByCategory(category);
  }

  void clearCurrentCategoryNotes() async {
    await _noteService.deleteNotesByCategory(_currentCategory);
    _notes.clear();
    _filteredNotes.clear();
    notifyListeners();
  }

  void clearAllNotes() async {
    await _noteService.deleteAllNotes();
    _notes.clear();
    _filteredNotes.clear();
    notifyListeners();
  }
}
