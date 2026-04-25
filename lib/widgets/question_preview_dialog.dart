import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:markdown/markdown.dart' as md;
import 'package:intl/intl.dart';
import '../models/question.dart';

class QuestionPreviewDialog extends StatelessWidget {
  final Question question;
  final VoidCallback? onRegenerate;
  final VoidCallback? onDelete;
  final VoidCallback? onSaveAsNote;

  const QuestionPreviewDialog({
    super.key,
    required this.question,
    this.onRegenerate,
    this.onDelete,
    this.onSaveAsNote,
  });

  static Future<void> show(
    BuildContext context, {
    required Question question,
    VoidCallback? onRegenerate,
    VoidCallback? onDelete,
    VoidCallback? onSaveAsNote,
  }) {
    return showDialog(
      context: context,
      builder: (context) => QuestionPreviewDialog(
        question: question,
        onRegenerate: onRegenerate,
        onDelete: onDelete,
        onSaveAsNote: onSaveAsNote,
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
                    _buildQuestionContent(context),
                    if (question.answer != null) ...[
                      const SizedBox(height: 16),
                      _buildAnswerContent(context),
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
              color: _getStatusColor(context).withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  _getStatusIcon(),
                  size: 16,
                  color: _getStatusColor(context),
                ),
                const SizedBox(width: 6),
                Text(
                  _getStatusName(),
                  style: TextStyle(
                    color: _getStatusColor(context),
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
              dateFormat.format(question.createdAt),
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
        if (question.context != null)
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
                question.context!,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.category,
              size: 14,
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
            const SizedBox(width: 4),
            Text(
              question.category,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildQuestionContent(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(
              Icons.help_outline,
              size: 18,
              color: Theme.of(context).colorScheme.primary,
            ),
            const SizedBox(width: 8),
            Text(
              '问题',
              style: Theme.of(context).textTheme.titleSmall?.copyWith(
                fontWeight: FontWeight.bold,
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primaryContainer.withValues(alpha: 0.3),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.3),
            ),
          ),
          child: Text(
            question.content,
            style: Theme.of(context).textTheme.bodyMedium,
          ),
        ),
      ],
    );
  }

  Widget _buildAnswerContent(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(
              Icons.lightbulb_outline,
              size: 18,
              color: Theme.of(context).colorScheme.secondary,
            ),
            const SizedBox(width: 8),
            Text(
              '回答',
              style: Theme.of(context).textTheme.titleSmall?.copyWith(
                fontWeight: FontWeight.bold,
              ),
            ),
            if (question.answeredAt != null) ...[
              const Spacer(),
              Text(
                DateFormat('HH:mm').format(question.answeredAt!),
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ],
        ),
        const SizedBox(height: 8),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(8),
          ),
          child: MarkdownBody(
            data: question.answer!,
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
                backgroundColor: Theme.of(context).colorScheme.surface,
              ),
              codeblockDecoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surface,
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
                  left: BorderSide(color: Theme.of(context).colorScheme.secondary, width: 4),
                ),
              ),
              blockquotePadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildActions(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(8),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          if (onSaveAsNote != null)
            TextButton.icon(
              onPressed: () {
                Navigator.pop(context);
                onSaveAsNote?.call();
              },
              icon: const Icon(Icons.note_add_outlined, size: 18),
              label: const Text('保存为笔记'),
            ),
          if (onRegenerate != null)
            TextButton.icon(
              onPressed: () {
                Navigator.pop(context);
                onRegenerate?.call();
              },
              icon: const Icon(Icons.refresh, size: 18),
              label: const Text('重新生成'),
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

  Color _getStatusColor(BuildContext context) {
    switch (question.status) {
      case QuestionStatus.pending:
        return Colors.orange;
      case QuestionStatus.answering:
        return Colors.blue;
      case QuestionStatus.answered:
        return Colors.green;
      case QuestionStatus.failed:
        return Theme.of(context).colorScheme.error;
    }
  }

  IconData _getStatusIcon() {
    switch (question.status) {
      case QuestionStatus.pending:
        return Icons.schedule;
      case QuestionStatus.answering:
        return Icons.hourglass_top;
      case QuestionStatus.answered:
        return Icons.check_circle;
      case QuestionStatus.failed:
        return Icons.error;
    }
  }

  String _getStatusName() {
    switch (question.status) {
      case QuestionStatus.pending:
        return '等待回答';
      case QuestionStatus.answering:
        return '正在回答';
      case QuestionStatus.answered:
        return '已回答';
      case QuestionStatus.failed:
        return '回答失败';
    }
  }
}
