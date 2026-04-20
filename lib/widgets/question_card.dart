import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import '../models/question.dart';

class QuestionCard extends StatelessWidget {
  final Question question;
  final bool isGenerating;
  final VoidCallback? onRegenerate;
  final VoidCallback? onDelete;

  const QuestionCard({
    super.key,
    required this.question,
    this.isGenerating = false,
    this.onRegenerate,
    this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                _buildTypeChip(context),
                const Spacer(),
                _buildStatusChip(context),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              question.content,
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
            if (question.context != null && question.context!.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                '上下文: ${question.context}',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ],
            const Divider(height: 24),
            if (isGenerating)
              _buildGeneratingIndicator(context)
            else if (question.answer != null)
              _buildAnswer(context)
            else
              _buildPendingState(context),
            const SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  _formatTime(question.createdAt),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                Row(
                  children: [
                    if (onRegenerate != null)
                      IconButton(
                        icon: const Icon(Icons.refresh),
                        onPressed: onRegenerate,
                        tooltip: '重新生成',
                      ),
                    if (onDelete != null)
                      IconButton(
                        icon: const Icon(Icons.delete_outline),
                        onPressed: onDelete,
                        tooltip: '删除',
                      ),
                  ],
                ),
              ],
            ),
          ],
        ),
      ),
    ).animate().fadeIn().slideY(begin: 0.1);
  }

  Widget _buildTypeChip(BuildContext context) {
    return Chip(
      label: Text(_getTypeName(question.type)),
      backgroundColor: _getTypeColor(context).withOpacity(0.1),
      labelStyle: TextStyle(
        color: _getTypeColor(context),
        fontSize: 12,
      ),
    );
  }

  Widget _buildStatusChip(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: _getStatusColor(context).withOpacity(0.1),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            _getStatusIcon(),
            size: 14,
            color: _getStatusColor(context),
          ),
          const SizedBox(width: 4),
          Text(
            _getStatusName(),
            style: TextStyle(
              color: _getStatusColor(context),
              fontSize: 12,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildGeneratingIndicator(BuildContext context) {
    return Row(
      children: [
        SizedBox(
          width: 16,
          height: 16,
          child: CircularProgressIndicator(
            strokeWidth: 2,
            color: Theme.of(context).colorScheme.primary,
          ),
        ),
        const SizedBox(width: 12),
        Text(
          '正在生成答案...',
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
            color: Theme.of(context).colorScheme.primary,
          ),
        ),
      ],
    ).animate().fadeIn();
  }

  Widget _buildAnswer(BuildContext context) {
    return MarkdownBody(
      data: question.answer!,
      styleSheet: MarkdownStyleSheet(
        p: Theme.of(context).textTheme.bodyMedium,
      ),
    );
  }

  Widget _buildPendingState(BuildContext context) {
    return Text(
      '等待生成答案...',
      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
        color: Theme.of(context).colorScheme.onSurfaceVariant,
        fontStyle: FontStyle.italic,
      ),
    );
  }

  String _getTypeName(QuestionType type) {
    switch (type) {
      case QuestionType.factual:
        return '事实';
      case QuestionType.conceptual:
        return '概念';
      case QuestionType.procedural:
        return '流程';
      case QuestionType.analytical:
        return '分析';
      case QuestionType.unknown:
        return '未知';
    }
  }

  Color _getTypeColor(BuildContext context) {
    switch (question.type) {
      case QuestionType.factual:
        return Colors.blue;
      case QuestionType.conceptual:
        return Colors.purple;
      case QuestionType.procedural:
        return Colors.orange;
      case QuestionType.analytical:
        return Colors.green;
      case QuestionType.unknown:
        return Theme.of(context).colorScheme.outline;
    }
  }

  Color _getStatusColor(BuildContext context) {
    switch (question.status) {
      case QuestionStatus.pending:
        return Colors.orange;
      case QuestionStatus.answering:
        return Theme.of(context).colorScheme.primary;
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
        return Icons.hourglass_empty;
      case QuestionStatus.answered:
        return Icons.check_circle;
      case QuestionStatus.failed:
        return Icons.error;
    }
  }

  String _getStatusName() {
    switch (question.status) {
      case QuestionStatus.pending:
        return '待处理';
      case QuestionStatus.answering:
        return '生成中';
      case QuestionStatus.answered:
        return '已回答';
      case QuestionStatus.failed:
        return '失败';
    }
  }

  String _formatTime(DateTime time) {
    return '${time.hour}:${time.minute.toString().padLeft(2, '0')}';
  }
}
