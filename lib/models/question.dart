import 'package:hive/hive.dart';

part 'question.g.dart';

@HiveType(typeId: 0)
class Question {
  @HiveField(0)
  final String id;

  @HiveField(1)
  final String content;

  @HiveField(2)
  final String? answer;

  @HiveField(3)
  final DateTime createdAt;

  @HiveField(4)
  final DateTime? answeredAt;

  @HiveField(5)
  final QuestionType type;

  @HiveField(6)
  final QuestionStatus status;

  @HiveField(7)
  final double confidence;

  @HiveField(8)
  final String? context;

  @HiveField(9)
  final String category;

  Question({
    required this.id,
    required this.content,
    this.answer,
    required this.createdAt,
    this.answeredAt,
    this.type = QuestionType.unknown,
    this.status = QuestionStatus.pending,
    this.confidence = 0.0,
    this.context,
    this.category = 'default',
  });

  Question copyWith({
    String? id,
    String? content,
    String? answer,
    DateTime? createdAt,
    DateTime? answeredAt,
    QuestionType? type,
    QuestionStatus? status,
    double? confidence,
    String? context,
    String? category,
  }) {
    return Question(
      id: id ?? this.id,
      content: content ?? this.content,
      answer: answer ?? this.answer,
      createdAt: createdAt ?? this.createdAt,
      answeredAt: answeredAt ?? this.answeredAt,
      type: type ?? this.type,
      status: status ?? this.status,
      confidence: confidence ?? this.confidence,
      context: context ?? this.context,
      category: category ?? this.category,
    );
  }
}

@HiveType(typeId: 1)
enum QuestionType {
  @HiveField(0)
  factual,
  
  @HiveField(1)
  conceptual,
  
  @HiveField(2)
  procedural,
  
  @HiveField(3)
  analytical,
  
  @HiveField(4)
  unknown,
}

@HiveType(typeId: 2)
enum QuestionStatus {
  @HiveField(0)
  pending,
  
  @HiveField(1)
  answering,
  
  @HiveField(2)
  answered,
  
  @HiveField(3)
  failed,
}
