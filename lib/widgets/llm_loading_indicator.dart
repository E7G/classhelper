import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';

enum LLMLoadingState {
  idle,
  loading,
  ready,
  error,
}

class LLMLoadingOverlay extends StatelessWidget {
  final LLMLoadingState state;
  final double progress;
  final String? errorMessage;
  final VoidCallback? onRetry;

  const LLMLoadingOverlay({
    super.key,
    required this.state,
    this.progress = 0.0,
    this.errorMessage,
    this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    if (state == LLMLoadingState.idle || state == LLMLoadingState.ready) {
      return const SizedBox.shrink();
    }

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (state == LLMLoadingState.loading) ...[
            _buildLoadingContent(context),
          ] else if (state == LLMLoadingState.error) ...[
            _buildErrorContent(context),
          ],
        ],
      ),
    ).animate().fadeIn().scale(begin: const Offset(0.95, 0.95));
  }

  Widget _buildLoadingContent(BuildContext context) {
    return Row(
      children: [
        SizedBox(
          width: 24,
          height: 24,
          child: CircularProgressIndicator(
            strokeWidth: 2,
            value: progress > 0 ? progress : null,
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '正在加载AI模型...',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              if (progress > 0) ...[
                const SizedBox(height: 4),
                ClipRRect(
                  borderRadius: BorderRadius.circular(2),
                  child: LinearProgressIndicator(
                    value: progress,
                    minHeight: 3,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  '${(progress * 100).toInt()}%',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildErrorContent(BuildContext context) {
    return Column(
      children: [
        Icon(
          Icons.error_outline,
          color: Theme.of(context).colorScheme.error,
          size: 32,
        ),
        const SizedBox(height: 8),
        Text(
          errorMessage ?? '模型加载失败',
          style: Theme.of(context).textTheme.bodyMedium,
          textAlign: TextAlign.center,
        ),
        if (onRetry != null) ...[
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: onRetry,
            icon: const Icon(Icons.refresh, size: 18),
            label: const Text('重试'),
          ),
        ],
      ],
    );
  }
}

class LLMLoadingButton extends StatelessWidget {
  final bool isLoading;
  final String loadingText;
  final String text;
  final IconData icon;
  final VoidCallback? onPressed;
  final double? progress;

  const LLMLoadingButton({
    super.key,
    required this.isLoading,
    this.loadingText = '加载中...',
    this.text = 'AI处理',
    this.icon = Icons.auto_awesome,
    this.onPressed,
    this.progress,
  });

  @override
  Widget build(BuildContext context) {
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 200),
      child: isLoading
          ? _buildLoadingButton(context)
          : _buildNormalButton(context),
    );
  }

  Widget _buildNormalButton(BuildContext context) {
    return IconButton(
      key: const ValueKey('normal'),
      icon: Icon(icon, size: 20),
      onPressed: onPressed,
      tooltip: text,
    );
  }

  Widget _buildLoadingButton(BuildContext context) {
    return Container(
      key: const ValueKey('loading'),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.primaryContainer,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            width: 16,
            height: 16,
            child: CircularProgressIndicator(
              strokeWidth: 2,
              valueColor: AlwaysStoppedAnimation<Color>(
                Theme.of(context).colorScheme.onPrimaryContainer,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Text(
            loadingText,
            style: TextStyle(
              fontSize: 12,
              color: Theme.of(context).colorScheme.onPrimaryContainer,
            ),
          ),
        ],
      ),
    ).animate().fadeIn().slideX(begin: 0.2);
  }
}
