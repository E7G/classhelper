import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:pdfrx/pdfrx.dart';
import 'package:hive/hive.dart';
import 'package:uuid/uuid.dart';
import 'package:logger/logger.dart';
import '../models/pdf_bookmark.dart';

class PdfProvider extends ChangeNotifier {
  final Logger _logger = Logger();
  final Uuid _uuid = const Uuid();

  PdfDocument? _document;
  String? _filePath;
  String? _fileName;
  int _currentPage = 1;
  int _totalPages = 0;
  bool _isLoading = false;
  String? _errorMessage;
  List<PdfBookmark> _bookmarks = [];
  double _zoomLevel = 1.0;
  late Box _pdfBox;
  final PdfViewerController controller = PdfViewerController();
  String _currentCategory = 'default';

  PdfDocument? get document => _document;
  String? get filePath => _filePath;
  String? get fileName => _fileName;
  int get currentPage => _currentPage;
  int get totalPages => _totalPages;
  bool get isLoading => _isLoading;
  String? get errorMessage => _errorMessage;
  List<PdfBookmark> get bookmarks => List.unmodifiable(_bookmarks);
  double get zoomLevel => _zoomLevel;
  bool get isDocumentLoaded => _document != null;
  String get currentCategory => _currentCategory;

  PdfProvider() {
    _init();
  }

  void _init() {
    _pdfBox = Hive.box('settings');
    _loadCategories();
    _loadBookmarks();
  }

  List<String> _categories = ['default'];

  List<String> get categories => List.unmodifiable(_categories);

  void _loadCategories() {
    final savedCategories = _pdfBox.get('pdf_categories') as List?;
    if (savedCategories != null) {
      _categories = List<String>.from(savedCategories);
    }
  }

  void _saveCategories() {
    _pdfBox.put('pdf_categories', _categories);
  }

  void setCurrentCategory(String category) {
    if (_currentCategory != category) {
      _currentCategory = category;
      _loadBookmarks();
      notifyListeners();
    }
  }

  void createCategory(String name) {
    if (name.isNotEmpty && !_categories.contains(name)) {
      _categories.add(name);
      _saveCategories();
      notifyListeners();
    }
  }

  void deleteCategory(String name) {
    if (name == 'default') return;
    if (_categories.contains(name)) {
      final allBookmarks = _pdfBox.get('pdf_bookmarks') as List? ?? [];
      final remaining = allBookmarks
          .map((b) => PdfBookmark.fromJson(Map<String, dynamic>.from(b as Map)))
          .where((b) => b.category != name)
          .toList();
      _pdfBox.put('pdf_bookmarks', remaining.map((b) => b.toJson()).toList());
      _categories.remove(name);
      if (_currentCategory == name) {
        _currentCategory = 'default';
        _loadBookmarks();
      }
      _saveCategories();
      notifyListeners();
    }
  }

  void _loadBookmarks() {
    final bookmarksJson = _pdfBox.get('pdf_bookmarks') as List?;
    if (bookmarksJson != null) {
      _bookmarks = bookmarksJson
          .map((b) => PdfBookmark.fromJson(Map<String, dynamic>.from(b as Map)))
          .where((b) => b.category == _currentCategory)
          .toList();
    } else {
      _bookmarks = [];
    }
  }

  void _saveBookmarks() {
    final allBookmarksJson = _pdfBox.get('pdf_bookmarks') as List? ?? [];
    final allBookmarks = allBookmarksJson
        .map((b) => PdfBookmark.fromJson(Map<String, dynamic>.from(b as Map)))
        .where((b) => b.category != _currentCategory)
        .toList();
    allBookmarks.addAll(_bookmarks);
    _pdfBox.put(
      'pdf_bookmarks',
      allBookmarks.map((b) => b.toJson()).toList(),
    );
  }

  Future<void> openPdf(String path) async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      if (_document != null) {
        _document!.dispose();
      }

      _document = await PdfDocument.openFile(path);
      _filePath = path;
      _fileName = File(path).uri.pathSegments.last;
      _totalPages = _document!.pages.length;
      _currentPage = 1;
      _zoomLevel = 1.0;

      _currentCategory = _fileName!;
      if (!_categories.contains(_currentCategory)) {
        _categories.add(_currentCategory);
        _saveCategories();
      }

      _loadBookmarks();

      _isLoading = false;
      notifyListeners();

      _logger.i('PDF opened: $_fileName, pages: $_totalPages');
    } catch (e) {
      _isLoading = false;
      _errorMessage = '打开PDF失败: $e';
      _logger.e('Failed to open PDF: $e');
      notifyListeners();
    }
  }

  void setCurrentPage(int page) {
    if (page >= 1 && page <= _totalPages && page != _currentPage) {
      _currentPage = page;
      notifyListeners();
    }
  }

  void goToPage(int page) {
    if (page >= 1 && page <= _totalPages) {
      if (!controller.isReady) {
        _logger.w('PDF controller is not ready yet');
        _currentPage = page;
        notifyListeners();
        return;
      }
      
      try {
        controller.goToPage(pageNumber: page);
        _currentPage = page;
        notifyListeners();
        _logger.i('Navigated to page $page');
      } catch (e) {
        _logger.e('Failed to navigate to page $page: $e');
      }
    }
  }

  void setZoomLevel(double level) {
    final newLevel = level.clamp(0.5, 3.0);
    if (newLevel == _zoomLevel) return;

    try {
      if (!controller.isReady) return;
      controller.zoomUp();
      _zoomLevel = controller.currentZoom;
      _logger.i('Zoom set: $_zoomLevel');
    } catch (e) {
      _logger.e('Failed to set zoom: $e');
    }
  }

  void zoomIn() {
    try {
      if (!controller.isReady) return;
      controller.zoomUp();
      _zoomLevel = controller.currentZoom;
      _logger.i('Zoom in: $_zoomLevel');
    } catch (e) {
      _logger.e('Failed to zoom in: $e');
    }
  }

  void zoomOut() {
    try {
      if (!controller.isReady) return;
      controller.zoomDown();
      _zoomLevel = controller.currentZoom;
      _logger.i('Zoom out: $_zoomLevel');
    } catch (e) {
      _logger.e('Failed to zoom out: $e');
    }
  }

  PdfBookmark addBookmark(String title, {int? pageNumber}) {
    final bookmark = PdfBookmark(
      id: _uuid.v4(),
      title: title,
      pageNumber: pageNumber ?? _currentPage,
      category: _currentCategory,
    );

    _bookmarks.add(bookmark);
    _saveBookmarks();
    notifyListeners();

    return bookmark;
  }

  void removeBookmark(String id) {
    _bookmarks.removeWhere((b) => b.id == id);
    _saveBookmarks();
    notifyListeners();
  }

  List<PdfBookmark> getBookmarksForPage(int page) {
    return _bookmarks.where((b) => b.pageNumber == page).toList();
  }

  int getBookmarkCountByCategory(String category) {
    final bookmarksJson = _pdfBox.get('pdf_bookmarks') as List?;
    if (bookmarksJson == null) return 0;
    return bookmarksJson
        .map((b) => PdfBookmark.fromJson(Map<String, dynamic>.from(b as Map)))
        .where((b) => b.category == category)
        .length;
  }

  Future<void> closePdf() async {
    if (_document != null) {
      _document!.dispose();
      _document = null;
    }
    _filePath = null;
    _fileName = null;
    _currentPage = 1;
    _totalPages = 0;
    _zoomLevel = 1.0;
    notifyListeners();
  }

  Future<String> getCurrentPageText() async {
    if (_document == null) return '';
    
    try {
      final page = _document!.pages[_currentPage - 1];
      final pageText = await page.loadText();
      return pageText.fullText;
    } catch (e) {
      _logger.e('Failed to extract text from page $_currentPage: $e');
      return '';
    }
  }

  Future<String> getPageText(int pageNumber) async {
    if (_document == null || pageNumber < 1 || pageNumber > _totalPages) return '';
    
    try {
      final page = _document!.pages[pageNumber - 1];
      final pageText = await page.loadText();
      return pageText.fullText;
    } catch (e) {
      _logger.e('Failed to extract text from page $pageNumber: $e');
      return '';
    }
  }

  Future<String> getSurroundingPagesText({int range = 1}) async {
    if (_document == null) return '';
    
    final texts = <String>[];
    final startPage = (_currentPage - range).clamp(1, _totalPages);
    final endPage = (_currentPage + range).clamp(1, _totalPages);
    
    for (int i = startPage; i <= endPage; i++) {
      final text = await getPageText(i);
      if (text.isNotEmpty) {
        texts.add('--- 第 $i 页 ---\n$text');
      }
    }
    
    return texts.join('\n\n');
  }

  Future<String> getPagesText(int startPage, int endPage) async {
    if (_document == null) return '';

    final clampedStart = startPage.clamp(1, _totalPages);
    final clampedEnd = endPage.clamp(1, _totalPages);

    final texts = <String>[];
    for (int i = clampedStart; i <= clampedEnd; i++) {
      final text = await getPageText(i);
      if (text.isNotEmpty) {
        texts.add('--- 第 $i 页 ---\n$text');
      }
    }

    return texts.join('\n\n');
  }

  @override
  void dispose() {
    closePdf();
    super.dispose();
  }
}
