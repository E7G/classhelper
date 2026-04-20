import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:markdown/markdown.dart' as md;

class PdfTextQuestionDialog extends StatefulWidget {
  final String selectedText;
  final String? pdfTitle;
  final int? pageNumber;
  final Future<String> Function(String question, String context) onAsk;

  const PdfTextQuestionDialog({
    super.key,
    required this.selectedText,
    this.pdfTitle,
    this.pageNumber,
    required this.onAsk,
  });

  static Future<void> show({
    required BuildContext context,
    required String selectedText,
    String? pdfTitle,
    int? pageNumber,
    required Future<String> Function(String question, String context) onAsk,
  }) {
    return showDialog(
      context: context,
      builder: (context) => PdfTextQuestionDialog(
        selectedText: selectedText,
        pdfTitle: pdfTitle,
        pageNumber: pageNumber,
        onAsk: onAsk,
      ),
    );
  }

  @override
  State<PdfTextQuestionDialog> createState() => _PdfTextQuestionDialogState();
}

class _PdfTextQuestionDialogState extends State<PdfTextQuestionDialog> {
  final _questionController = TextEditingController();
  bool _isLoading = false;
  String? _answer;
  String? _error;

  @override
  void dispose() {
    _questionController.dispose();
    super.dispose();
  }

  Future<void> _askQuestion() async {
    if (_questionController.text.trim().isEmpty) return;

    setState(() {
      _isLoading = true;
      _error = null;
      _answer = null;
    });

    try {
      final answer = await widget.onAsk(
        _questionController.text.trim(),
        widget.selectedText,
      );
      
      if (mounted) {
        setState(() {
          _answer = answer;
          _isLoading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = e.toString();
          _isLoading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      child: Container(
        constraints: const BoxConstraints(
          maxWidth: 600,
          maxHeight: 700,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _buildHeader(context),
            const Divider(height: 1),
            Flexible(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildSelectedTextSection(context),
                    const SizedBox(height: 16),
                    _buildQuestionInput(context),
                    if (_isLoading) _buildLoadingIndicator(context),
                    if (_error != null) _buildError(context),
                    if (_answer != null) _buildAnswer(context),
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
          Icon(
            Icons.help_outline,
            color: Theme.of(context).colorScheme.primary,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '针对选中内容提问',
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
                ),
                if (widget.pdfTitle != null || widget.pageNumber != null)
                  Text(
                    '${widget.pdfTitle ?? ''}${widget.pageNumber != null ? ' · 第 ${widget.pageNumber} 页' : ''}',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
              ],
            ),
          ),
          IconButton(
            icon: const Icon(Icons.close),
            onPressed: () => Navigator.pop(context),
          ),
        ],
      ),
    );
  }

  Widget _buildSelectedTextSection(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.primaryContainer.withOpacity(0.3),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: Theme.of(context).colorScheme.primary.withOpacity(0.3),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                Icons.format_quote,
                size: 16,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(width: 8),
              Text(
                '选中内容',
                style: Theme.of(context).textTheme.labelMedium?.copyWith(
                  color: Theme.of(context).colorScheme.primary,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            widget.selectedText,
            style: Theme.of(context).textTheme.bodyMedium,
            maxLines: 5,
            overflow: TextOverflow.ellipsis,
          ),
        ],
      ),
    );
  }

  Widget _buildQuestionInput(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '你的问题',
          style: Theme.of(context).textTheme.labelLarge,
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _questionController,
          maxLines: 3,
          decoration: InputDecoration(
            hintText: '输入关于选中内容的问题...',
            border: const OutlineInputBorder(),
            suffixIcon: IconButton(
              icon: const Icon(Icons.send),
              onPressed: _isLoading ? null : _askQuestion,
              tooltip: '发送问题',
            ),
          ),
          onSubmitted: (_) => _askQuestion(),
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          children: [
            _buildQuickQuestion('这是什么意思？'),
            _buildQuickQuestion('请解释一下'),
            _buildQuickQuestion('举个例子'),
            _buildQuickQuestion('总结要点'),
          ],
        ),
      ],
    );
  }

  Widget _buildQuickQuestion(String question) {
    return ActionChip(
      label: Text(question, style: const TextStyle(fontSize: 12)),
      onPressed: _isLoading
          ? null
          : () {
              _questionController.text = question;
              _askQuestion();
            },
    );
  }

  Widget _buildLoadingIndicator(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 16),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          SizedBox(
            width: 20,
            height: 20,
            child: CircularProgressIndicator(
              strokeWidth: 2,
              color: Theme.of(context).colorScheme.primary,
            ),
          ),
          const SizedBox(width: 12),
          Text(
            '正在思考中...',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(context).colorScheme.primary,
            ),
          ),
        ],
      ),
    ).animate().fadeIn();
  }

  Widget _buildError(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(top: 16),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.errorContainer.withOpacity(0.3),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Icon(
            Icons.error_outline,
            color: Theme.of(context).colorScheme.error,
            size: 20,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              _error!,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.error,
              ),
            ),
          ),
        ],
      ),
    ).animate().fadeIn();
  }

  Widget _buildAnswer(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(top: 16),
      child: Column(
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
                'AI 回答',
                style: Theme.of(context).textTheme.labelLarge?.copyWith(
                  color: Theme.of(context).colorScheme.secondary,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(8),
            ),
            child: MarkdownBody(
              data: _answer!,
              shrinkWrap: true,
              softLineBreak: true,
              selectable: true,
              extensionSet: md.ExtensionSet.gitHubWeb,
              styleSheet: MarkdownStyleSheet(
                p: Theme.of(context).textTheme.bodyMedium,
                h1: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                h2: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                h3: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
                code: Theme.of(context).textTheme.bodySmall?.copyWith(
                  fontFamily: 'monospace',
                  backgroundColor: Theme.of(context).colorScheme.surface,
                ),
                codeblockDecoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surface,
                  borderRadius: BorderRadius.circular(8),
                ),
                codeblockPadding: const EdgeInsets.all(12),
              ),
            ),
          ),
        ],
      ),
    ).animate().fadeIn().slideY(begin: 0.1);
  }

  Widget _buildActions(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('关闭'),
          ),
          if (_answer != null)
            FilledButton.icon(
              onPressed: () {
                Navigator.pop(context, {
                  'question': _questionController.text,
                  'answer': _answer,
                  'context': widget.selectedText,
                });
              },
              icon: const Icon(Icons.note_add, size: 18),
              label: const Text('保存为笔记'),
            ),
        ],
      ),
    );
  }
}
