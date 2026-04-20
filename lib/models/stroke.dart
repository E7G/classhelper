import 'dart:ui';

class Stroke {
  final String id;
  final int pageNumber;
  final List<Offset> points;
  final Color color;
  final double strokeWidth;
  final DateTime createdAt;
  final String category;

  Stroke({
    required this.id,
    required this.pageNumber,
    required this.points,
    this.color = const Color(0xFFFF0000),
    this.strokeWidth = 2.0,
    DateTime? createdAt,
    this.category = 'default',
  }) : createdAt = createdAt ?? DateTime.now();

  Stroke copyWith({
    String? id,
    int? pageNumber,
    List<Offset>? points,
    Color? color,
    double? strokeWidth,
    DateTime? createdAt,
    String? category,
  }) {
    return Stroke(
      id: id ?? this.id,
      pageNumber: pageNumber ?? this.pageNumber,
      points: points ?? this.points,
      color: color ?? this.color,
      strokeWidth: strokeWidth ?? this.strokeWidth,
      createdAt: createdAt ?? this.createdAt,
      category: category ?? this.category,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'page_number': pageNumber,
      'points': points.map((p) => {'x': p.dx, 'y': p.dy}).toList(),
      'color': color.toARGB32(),
      'stroke_width': strokeWidth,
      'created_at': createdAt.toIso8601String(),
      'category': category,
    };
  }

  factory Stroke.fromJson(Map<String, dynamic> json) {
    return Stroke(
      id: json['id'] as String,
      pageNumber: json['page_number'] as int,
      points: (json['points'] as List)
          .map((p) => Offset((p as Map)['x'] as double, p['y'] as double))
          .toList(),
      color: Color(json['color'] as int),
      strokeWidth: (json['stroke_width'] as num).toDouble(),
      createdAt: DateTime.parse(json['created_at'] as String),
      category: (json['category'] as String?) ?? 'default',
    );
  }
}
