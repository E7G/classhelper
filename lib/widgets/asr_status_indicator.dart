import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import '../models/asr_result.dart';

class ASRStatusIndicator extends StatelessWidget {
  final ASRStatus status;
  final String? errorMessage;
  final double loadProgress;

  const ASRStatusIndicator({
    super.key,
    required this.status,
    this.errorMessage,
    this.loadProgress = 0.0,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: _getBackgroundColor(context),
        borderRadius: const BorderRadius.only(
          bottomLeft: Radius.circular(12),
          bottomRight: Radius.circular(12),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              _buildStatusIcon(context),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _getStatusText(),
                      style: TextStyle(
                        color: _getTextColor(context),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    if (status == ASRStatus.connecting) ...[
                      const SizedBox(height: 8),
                      _buildProgressBar(context),
                    ],
                    if (errorMessage != null && status == ASRStatus.error) ...[
                      const SizedBox(height: 4),
                      Text(
                        errorMessage!,
                        style: TextStyle(
                          color: _getTextColor(context),
                          fontSize: 12,
                        ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    ).animate().fadeIn().slideY(begin: -0.5);
  }

  Widget _buildProgressBar(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(4),
          child: LinearProgressIndicator(
            value: loadProgress > 0 ? loadProgress : null,
            backgroundColor: _getTextColor(context).withValues(alpha: 0.2),
            valueColor: AlwaysStoppedAnimation<Color>(_getTextColor(context)),
            minHeight: 4,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          loadProgress > 0 ? '加载模型中 ${(loadProgress * 100).toInt()}%' : '正在初始化...',
          style: TextStyle(
            color: _getTextColor(context),
            fontSize: 11,
          ),
        ),
      ],
    );
  }

  Widget _buildStatusIcon(BuildContext context) {
    if (status == ASRStatus.connecting) {
      return Container(
        width: 32,
        height: 32,
        decoration: BoxDecoration(
          color: _getTextColor(context).withValues(alpha: 0.2),
          shape: BoxShape.circle,
        ),
        child: CircularProgressIndicator(
          strokeWidth: 2,
          valueColor: AlwaysStoppedAnimation<Color>(_getTextColor(context)),
        ),
      );
    }

    if (status == ASRStatus.listening) {
      return Container(
        width: 32,
        height: 32,
        decoration: BoxDecoration(
          color: _getTextColor(context).withValues(alpha: 0.2),
          shape: BoxShape.circle,
        ),
        child: Icon(
          Icons.mic,
          color: _getTextColor(context),
          size: 20,
        ),
      ).animate(
        onPlay: (controller) => controller.repeat(),
      ).scale(
        begin: const Offset(1, 1),
        end: const Offset(1.2, 1.2),
        duration: 600.ms,
      ).then().scale(
        begin: const Offset(1.2, 1.2),
        end: const Offset(1, 1),
        duration: 600.ms,
      );
    }

    return Container(
      width: 32,
      height: 32,
      decoration: BoxDecoration(
        color: _getTextColor(context).withValues(alpha: 0.2),
        shape: BoxShape.circle,
      ),
      child: Icon(
        _getIcon(),
        color: _getTextColor(context),
        size: 20,
      ),
    );
  }

  IconData _getIcon() {
    switch (status) {
      case ASRStatus.disconnected:
        return Icons.cloud_off;
      case ASRStatus.connecting:
        return Icons.cloud_sync;
      case ASRStatus.connected:
        return Icons.cloud_done;
      case ASRStatus.listening:
        return Icons.mic;
      case ASRStatus.error:
        return Icons.error;
    }
  }

  Color _getBackgroundColor(BuildContext context) {
    switch (status) {
      case ASRStatus.disconnected:
        return Theme.of(context).colorScheme.surfaceContainerHighest;
      case ASRStatus.connecting:
        return Theme.of(context).colorScheme.primaryContainer;
      case ASRStatus.connected:
        return Theme.of(context).colorScheme.primaryContainer;
      case ASRStatus.listening:
        return Theme.of(context).colorScheme.errorContainer;
      case ASRStatus.error:
        return Theme.of(context).colorScheme.errorContainer;
    }
  }

  Color _getTextColor(BuildContext context) {
    switch (status) {
      case ASRStatus.disconnected:
        return Theme.of(context).colorScheme.onSurfaceVariant;
      case ASRStatus.connecting:
        return Theme.of(context).colorScheme.onPrimaryContainer;
      case ASRStatus.connected:
        return Theme.of(context).colorScheme.onPrimaryContainer;
      case ASRStatus.listening:
        return Theme.of(context).colorScheme.onErrorContainer;
      case ASRStatus.error:
        return Theme.of(context).colorScheme.onErrorContainer;
    }
  }

  String _getStatusText() {
    switch (status) {
      case ASRStatus.disconnected:
        return '未连接';
      case ASRStatus.connecting:
        return '正在加载语音识别模型...';
      case ASRStatus.connected:
        return '已就绪';
      case ASRStatus.listening:
        return '正在录音';
      case ASRStatus.error:
        return '连接错误';
    }
  }
}
