import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:markdown/markdown.dart' as md;
import 'package:intl/intl.dart';
import '../models/note.dart';

class NotePreviewDialog extends StatelessWidget {
  final Note note;
  final VoidCallback? onEdit;
  final VoidCallback? onDelete;
  final VoidCallback? onShowFullScreenImage;

  const NotePreviewDialog({
    super.key,
    required this.note,
    this.onEdit,
    this.onDelete,
    this.onShowFullScreenImage,
  });

  static Future<void> show(
    BuildContext context, {
    required Note note,
    VoidCallback? onEdit,
    VoidCallback? onDelete,
    VoidCallback? onShowFullScreenImage,
  }) {
    return showDialog(
      context: context,
      builder: (context) => NotePreviewDialog(
        note: note,
        onEdit: onEdit,
        onDelete: onDelete,
        onShowFullScreenImage: onShowFullScreenImage,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      insetPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
      child: Container(
        constraints: const BoxConstraints(maxWidth: 500, maxHeight: 600),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _buildHeader(context),
            const Divider(height: 1),
            Flexible(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildMetaInfo(context),
                    const SizedBox(height: 16),
                    if (note.type == NoteType.photo && note.imagePath != null) ...[
                      _buildImagePreview(context),
                      const SizedBox(height: 16),
                    ],
                    _buildContent(context),
                    if (note.tags.isNotEmpty) ...[
                      const SizedBox(height: 16),
                      _buildTags(context),
                    ],
                  ],
                ),
              ),
            ),
            const Divider(height: 1),
            _buildActions(context),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
              color: _getTypeColor(context).withOpacity(0.1),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  _getTypeIcon(),
                  size: 16,
                  color: _getTypeColor(context),
                ),
                const SizedBox(width: 6),
                Text(
                  _getTypeName(),
                  style: TextStyle(
                    color: _getTypeColor(context),
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ],
            ),
          ),
          const Spacer(),
          IconButton(
            icon: const Icon(Icons.close),
            onPressed: () => Navigator.pop(context),
            iconSize: 20,
          ),
        ],
      ),
    );
  }

  Widget _buildMetaInfo(BuildContext context) {
    final dateFormat = DateFormat('yyyy-MM-dd HH:mm');
    
    return Wrap(
      spacing: 16,
      runSpacing: 8,
      children: [
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.access_time,
              size: 14,
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
            const SizedBox(width: 4),
            Text(
              dateFormat.format(note.createdAt),
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
        if (note.pdfPage != null)
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.menu_book,
                size: 14,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
              const SizedBox(width: 4),
              Text(
                '第 ${note.pdfPage} 页',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
      ],
    );
  }

  Widget _buildImagePreview(BuildContext context) {
    return GestureDetector(
      onTap: onShowFullScreenImage,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: Hero(
          tag: 'note_preview_image_${note.id}',
          child: Image.file(
            File(note.imagePath!),
            width: double.infinity,
            fit: BoxFit.cover,
            errorBuilder: (context, error, stackTrace) {
              return Container(
                height: 150,
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                child: const Center(
                  child: Icon(Icons.broken_image, size: 48),
                ),
              );
            },
          ),
        ),
      ),
    );
  }

  Widget _buildContent(BuildContext context) {
    return MarkdownBody(
      data: note.content,
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

  Widget _buildTags(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 4,
      children: note.tags.map((tag) => Chip(
        label: Text(tag, style: const TextStyle(fontSize: 12)),
        visualDensity: VisualDensity.compact,
      )).toList(),
    );
  }

  Widget _buildActions(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(8),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          if (onEdit != null)
            TextButton.icon(
              onPressed: () {
                Navigator.pop(context);
                onEdit?.call();
              },
              icon: const Icon(Icons.edit_outlined, size: 18),
              label: const Text('编辑'),
            ),
          if (onDelete != null)
            TextButton.icon(
              onPressed: () {
                Navigator.pop(context);
                onDelete?.call();
              },
              icon: const Icon(Icons.delete_outline, size: 18),
              label: const Text('删除'),
              style: TextButton.styleFrom(
                foregroundColor: Theme.of(context).colorScheme.error,
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
}
