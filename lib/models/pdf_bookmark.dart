class PdfBookmark {
  final String id;
  final String title;
  final int pageNumber;
  final String category;
  final DateTime? createdAt;

  PdfBookmark({
    required this.id,
    required this.title,
    required this.pageNumber,
    this.category = 'default',
    DateTime? createdAt,
  }) : createdAt = createdAt ?? DateTime.now();

  PdfBookmark copyWith({
    String? id,
    String? title,
    int? pageNumber,
    String? category,
    DateTime? createdAt,
  }) {
    return PdfBookmark(
      id: id ?? this.id,
      title: title ?? this.title,
      pageNumber: pageNumber ?? this.pageNumber,
      category: category ?? this.category,
      createdAt: createdAt ?? this.createdAt,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'page_number': pageNumber,
      'category': category,
      'created_at': createdAt?.toIso8601String(),
    };
  }

  factory PdfBookmark.fromJson(Map<String, dynamic> json) {
    return PdfBookmark(
      id: json['id'] as String,
      title: json['title'] as String,
      pageNumber: json['page_number'] as int,
      category: (json['category'] as String?) ?? 'default',
      createdAt: json['created_at'] != null
          ? DateTime.parse(json['created_at'] as String)
          : null,
    );
  }
}
