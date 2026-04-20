import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:markdown/markdown.dart' as md;
import '../models/note.dart';

class NoteCard extends StatelessWidget {
  final Note note;
  final VoidCallback? onDelete;
  final VoidCallback? onEdit;
  final VoidCallback? onTap;

  const NoteCard({
    super.key,
    required this.note,
    this.onDelete,
    this.onEdit,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: InkWell(
        onTap: onTap ?? onEdit,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  _buildTypeChip(context),
                  const Spacer(),
                  Text(
                    _formatTime(note.createdAt),
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
              const SizedBox(height: 12),
              _buildMarkdownContent(context),
              if (note.summary != null) ...[
                const SizedBox(height: 8),
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.surfaceVariant,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    note.summary!,
                    style: Theme.of(context).textTheme.bodySmall,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
              if (note.type == NoteType.photo && note.imagePath != null) ...[
                const SizedBox(height: 8),
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: Image.file(
                    File(note.imagePath!),
                    height: 80,
                    width: double.infinity,
                    fit: BoxFit.cover,
                    errorBuilder: (context, error, stackTrace) {
                      return Container(
                        height: 80,
                        color: Theme.of(context).colorScheme.surfaceContainerHighest,
                        child: const Center(
                          child: Icon(Icons.broken_image, size: 24),
                        ),
                      );
                    },
                  ),
                ),
              ],
              if (note.tags.isNotEmpty) ...[
                const SizedBox(height: 12),
                Wrap(
                  spacing: 8,
                  runSpacing: 4,
                  children: note.tags.map((tag) => Chip(
                    label: Text(
                      tag,
                      style: const TextStyle(fontSize: 12),
                    ),
                    visualDensity: VisualDensity.compact,
                  )).toList(),
                ),
              ],
              if (onDelete != null || onEdit != null) ...[
                const SizedBox(height: 8),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    if (onEdit != null)
                      IconButton(
                        icon: const Icon(Icons.edit_outlined),
                        onPressed: onEdit,
                        tooltip: '编辑',
                        iconSize: 20,
                      ),
                    if (onDelete != null)
                      IconButton(
                        icon: const Icon(Icons.delete_outline),
                        onPressed: onDelete,
                        tooltip: '删除',
                        iconSize: 20,
                      ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    ).animate().fadeIn().slideX(begin: 0.1);
  }

  Widget _buildTypeChip(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: _getTypeColor(context).withOpacity(0.1),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            _getTypeIcon(),
            size: 14,
            color: _getTypeColor(context),
          ),
          const SizedBox(width: 4),
          Text(
            _getTypeName(),
            style: TextStyle(
              color: _getTypeColor(context),
              fontSize: 12,
            ),
          ),
        ],
      ),
    );
  }

  Color _getTypeColor(BuildContext context) {
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

  String _getTypeName() {
    switch (note.type) {
      case NoteType.asr:
        return '语音识别';
      case NoteType.manual:
        return '手动记录';
      case NoteType.summary:
        return '摘要';
      case NoteType.keypoint:
        return '重点';
      case NoteType.photo:
        return '照片';
    }
  }

  String _formatTime(DateTime time) {
    final now = DateTime.now();
    final difference = now.difference(time);
    
    if (difference.inDays == 0) {
      return '${time.hour}:${time.minute.toString().padLeft(2, '0')}';
    } else if (difference.inDays == 1) {
      return '昨天';
    } else if (difference.inDays < 7) {
      return '${difference.inDays}天前';
    } else {
      return '${time.month}/${time.day}';
    }
  }

  Widget _buildMarkdownContent(BuildContext context) {
    final lines = note.content.split('\n');
    final previewLines = lines.take(6).join('\n');
    final isTruncated = lines.length > 6;
    
    return MarkdownBody(
      data: isTruncated ? '$previewLines...' : previewLines,
      shrinkWrap: true,
      softLineBreak: true,
      selectable: true,
      extensionSet: md.ExtensionSet.gitHubWeb,
      styleSheet: MarkdownStyleSheet(
        p: Theme.of(context).textTheme.bodyMedium,
        h1: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
        h2: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
        h3: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
        h4: Theme.of(context).textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.bold),
        listBullet: Theme.of(context).textTheme.bodyMedium,
        code: Theme.of(context).textTheme.bodySmall?.copyWith(
          fontFamily: 'monospace',
          backgroundColor: Theme.of(context).colorScheme.surfaceContainerHighest,
        ),
        codeblockDecoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(8),
        ),
        codeblockPadding: const EdgeInsets.all(12),
        blockquote: Theme.of(context).textTheme.bodyMedium?.copyWith(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          fontStyle: FontStyle.italic,
        ),
        blockquoteDecoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(4),
          border: Border(
            left: BorderSide(color: Theme.of(context).colorScheme.primary, width: 4),
          ),
        ),
        blockquotePadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        tableHead: Theme.of(context).textTheme.bodySmall?.copyWith(fontWeight: FontWeight.bold),
        tableBody: Theme.of(context).textTheme.bodySmall,
        tableBorder: TableBorder.all(color: Theme.of(context).colorScheme.outlineVariant, width: 1),
        tableCellsPadding: const EdgeInsets.all(8),
      ),
    );
  }
}
