import 'package:hive/hive.dart';
import 'package:uuid/uuid.dart';
import 'package:logger/logger.dart';
import '../models/note.dart';

class NoteService {
  final Logger _logger = Logger();
  final Uuid _uuid = const Uuid();

  late Box<Note> _noteBox;

  Future<void> init() async {
    _noteBox = Hive.box<Note>('notes');
    _logger.i('NoteService initialized');
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
    String category = 'default',
  }) async {
    final note = Note(
      id: _uuid.v4(),
      content: content,
      createdAt: DateTime.now(),
      type: type,
      tags: tags,
      summary: summary,
      confidence: confidence,
      pdfPath: pdfPath,
      pdfPage: pdfPage,
      pdfOffsetX: pdfOffsetX,
      pdfOffsetY: pdfOffsetY,
      imagePath: imagePath,
      category: category,
    );

    await _noteBox.put(note.id, note);
    _logger.i('Created note: ${note.id} in category: $category');

    return note;
  }

  Future<Note?> getNote(String id) async {
    return _noteBox.get(id);
  }

  List<Note> getAllNotes() {
    return _noteBox.values.toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
  }

  List<Note> getNotesByCategory(String category) {
    return _noteBox.values
        .where((note) => note.category == category)
        .toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
  }

  List<Note> getNotesByType(NoteType type) {
    return _noteBox.values
        .where((note) => note.type == type)
        .toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
  }

  List<Note> getNotesByTypeAndCategory(NoteType type, String category) {
    return _noteBox.values
        .where((note) => note.type == type && note.category == category)
        .toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
  }

  List<Note> getNotesByPdfPath(String pdfPath) {
    return _noteBox.values
        .where((note) => note.pdfPath == pdfPath)
        .toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
  }

  List<Note> getNotesByDate(DateTime date) {
    return _noteBox.values.where((note) {
      return note.createdAt.year == date.year &&
             note.createdAt.month == date.month &&
             note.createdAt.day == date.day;
    }).toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
  }

  List<Note> getNotesByDateAndCategory(DateTime date, String category) {
    return _noteBox.values.where((note) {
      return note.createdAt.year == date.year &&
             note.createdAt.month == date.month &&
             note.createdAt.day == date.day &&
             note.category == category;
    }).toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
  }

  List<Note> searchNotes(String query) {
    final lowerQuery = query.toLowerCase();
    return _noteBox.values.where((note) {
      return note.content.toLowerCase().contains(lowerQuery) ||
             note.tags.any((tag) => tag.toLowerCase().contains(lowerQuery)) ||
             (note.summary?.toLowerCase().contains(lowerQuery) ?? false);
    }).toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
  }

  List<Note> searchNotesInCategory(String query, String category) {
    final lowerQuery = query.toLowerCase();
    return _noteBox.values.where((note) {
      return note.category == category &&
          (note.content.toLowerCase().contains(lowerQuery) ||
           note.tags.any((tag) => tag.toLowerCase().contains(lowerQuery)) ||
           (note.summary?.toLowerCase().contains(lowerQuery) ?? false));
    }).toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
  }

  Future<Note> updateNote(Note note) async {
    final updatedNote = note.copyWith(
      updatedAt: DateTime.now(),
    );

    await _noteBox.put(note.id, updatedNote);
    _logger.i('Updated note: ${note.id}');

    return updatedNote;
  }

  Future<void> deleteNote(String id) async {
    await _noteBox.delete(id);
    _logger.i('Deleted note: $id');
  }

  Future<void> deleteNotesByCategory(String category) async {
    final toDelete = _noteBox.values.where((note) => note.category == category).map((note) => note.id).toList();
    for (final id in toDelete) {
      await _noteBox.delete(id);
    }
    _logger.i('Deleted all notes in category: $category');
  }

  Future<void> deleteAllNotes() async {
    await _noteBox.clear();
    _logger.i('Deleted all notes');
  }

  Future<Note> addTags(String noteId, List<String> tags) async {
    final note = _noteBox.get(noteId);
    if (note == null) {
      throw Exception('Note not found: $noteId');
    }

    final updatedTags = {...note.tags, ...tags}.toList();
    final updatedNote = note.copyWith(tags: updatedTags);

    await _noteBox.put(noteId, updatedNote);
    return updatedNote;
  }

  Future<Note> removeTag(String noteId, String tag) async {
    final note = _noteBox.get(noteId);
    if (note == null) {
      throw Exception('Note not found: $noteId');
    }

    final updatedTags = note.tags.where((t) => t != tag).toList();
    final updatedNote = note.copyWith(tags: updatedTags);

    await _noteBox.put(noteId, updatedNote);
    return updatedNote;
  }

  Map<String, int> getTagStatistics() {
    final stats = <String, int>{};

    for (final note in _noteBox.values) {
      for (final tag in note.tags) {
        stats[tag] = (stats[tag] ?? 0) + 1;
      }
    }

    return stats;
  }

  Map<String, int> getTagStatisticsForCategory(String category) {
    final stats = <String, int>{};

    for (final note in _noteBox.values.where((n) => n.category == category)) {
      for (final tag in note.tags) {
        stats[tag] = (stats[tag] ?? 0) + 1;
      }
    }

    return stats;
  }

  int getNoteCount() {
    return _noteBox.length;
  }

  int getNoteCountByCategory(String category) {
    return _noteBox.values.where((note) => note.category == category).length;
  }

  int getNoteCountByType(NoteType type) {
    return _noteBox.values.where((note) => note.type == type).length;
  }
}
