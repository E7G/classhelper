import 'package:flutter/material.dart';
import '../models/note.dart';

class NoteMarker extends StatelessWidget {
  final Note note;
  final VoidCallback? onTap;

  const NoteMarker({
    super.key,
    required this.note,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 28,
        height: 28,
        decoration: BoxDecoration(
          color: _getTypeColor().withValues(alpha: 0.9),
          shape: BoxShape.circle,
          border: Border.all(
            color: Colors.white,
            width: 2,
          ),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.3),
              blurRadius: 4,
              offset: const Offset(0, 2),
            ),
          ],
        ),
        child: Icon(
          _getTypeIcon(),
          size: 14,
          color: Colors.white,
        ),
      ),
    );
  }

  Color _getTypeColor() {
    switch (note.type) {
      case NoteType.asr:
        return Colors.blue;
      case NoteType.manual:
        return Colors.green;
      case NoteType.summary:
        return Colors.purple;
      case NoteType.keypoint:
        return Colors.orange;
      case NoteType.photo:
        return Colors.red;
    }
  }

  IconData _getTypeIcon() {
    switch (note.type) {
      case NoteType.asr:
        return Icons.mic;
      case NoteType.manual:
        return Icons.edit;
      case NoteType.summary:
        return Icons.summarize;
      case NoteType.keypoint:
        return Icons.star;
      case NoteType.photo:
        return Icons.photo;
    }
  }
}
