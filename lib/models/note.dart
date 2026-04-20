import 'package:hive/hive.dart';

part 'note.g.dart';

@HiveType(typeId: 3)
class Note {
  @HiveField(0)
  final String id;

  @HiveField(1)
  final String content;

  @HiveField(2)
  final DateTime createdAt;

  @HiveField(3)
  final DateTime? updatedAt;

  @HiveField(4)
  final NoteType type;

  @HiveField(5)
  final List<String> tags;

  @HiveField(6)
  final String? summary;

  @HiveField(7)
  final double? confidence;

  @HiveField(8)
  final String? pdfPath;

  @HiveField(9)
  final int? pdfPage;

  @HiveField(10)
  final double? pdfOffsetX;

  @HiveField(11)
  final double? pdfOffsetY;

  @HiveField(12)
  final String? imagePath;

  @HiveField(13)
  final String category;

  Note({
    required this.id,
    required this.content,
    required this.createdAt,
    this.updatedAt,
    this.type = NoteType.asr,
    this.tags = const [],
    this.summary,
    this.confidence,
    this.pdfPath,
    this.pdfPage,
    this.pdfOffsetX,
    this.pdfOffsetY,
    this.imagePath,
    this.category = 'default',
  });

  Note copyWith({
    String? id,
    String? content,
    DateTime? createdAt,
    DateTime? updatedAt,
    NoteType? type,
    List<String>? tags,
    String? summary,
    double? confidence,
    String? pdfPath,
    int? pdfPage,
    double? pdfOffsetX,
    double? pdfOffsetY,
    String? imagePath,
    String? category,
  }) {
    return Note(
      id: id ?? this.id,
      content: content ?? this.content,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      type: type ?? this.type,
      tags: tags ?? this.tags,
      summary: summary ?? this.summary,
      confidence: confidence ?? this.confidence,
      pdfPath: pdfPath ?? this.pdfPath,
      pdfPage: pdfPage ?? this.pdfPage,
      pdfOffsetX: pdfOffsetX ?? this.pdfOffsetX,
      pdfOffsetY: pdfOffsetY ?? this.pdfOffsetY,
      imagePath: imagePath ?? this.imagePath,
      category: category ?? this.category,
    );
  }
}

@HiveType(typeId: 4)
enum NoteType {
  @HiveField(0)
  asr,

  @HiveField(1)
  manual,

  @HiveField(2)
  summary,

  @HiveField(3)
  keypoint,

  @HiveField(4)
  photo,
}
