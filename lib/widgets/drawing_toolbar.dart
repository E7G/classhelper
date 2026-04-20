import 'package:flutter/material.dart';

class DrawingToolbar extends StatelessWidget {
  final bool isEraser;
  final Color currentColor;
  final double currentStrokeWidth;
  final VoidCallback onUndo;
  final VoidCallback onClear;
  final VoidCallback? onSave;
  final ValueChanged<Color> onColorChanged;
  final ValueChanged<double> onStrokeWidthChanged;
  final VoidCallback onToggleEraser;
  final VoidCallback? onClose;

  const DrawingToolbar({
    super.key,
    required this.isEraser,
    required this.currentColor,
    required this.currentStrokeWidth,
    required this.onUndo,
    required this.onClear,
    this.onSave,
    required this.onColorChanged,
    required this.onStrokeWidthChanged,
    required this.onToggleEraser,
    this.onClose,
  });

  static const _colors = [
    Colors.red,
    Colors.blue,
    Colors.green,
    Colors.orange,
    Colors.purple,
    Colors.black,
    Colors.white,
  ];

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        borderRadius: BorderRadius.circular(12),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.1),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            icon: Icon(
              isEraser ? Icons.cleaning_services : Icons.edit,
              color: isEraser ? Theme.of(context).colorScheme.error : null,
            ),
            onPressed: onToggleEraser,
            tooltip: isEraser ? '画笔' : '橡皮擦',
            iconSize: 20,
          ),
          const SizedBox(width: 4),
          SizedBox(
            width: 80,
            child: Slider(
              value: currentStrokeWidth,
              min: 1.0,
              max: 8.0,
              divisions: 7,
              onChanged: onStrokeWidthChanged,
            ),
          ),
          const SizedBox(width: 4),
          ..._colors.map((color) => GestureDetector(
            onTap: () => onColorChanged(color),
            child: Container(
              width: 20,
              height: 20,
              margin: const EdgeInsets.symmetric(horizontal: 1),
              decoration: BoxDecoration(
                color: color,
                shape: BoxShape.circle,
                border: Border.all(
                  color: currentColor == color && !isEraser
                      ? Theme.of(context).colorScheme.primary
                      : Colors.grey.shade300,
                  width: currentColor == color && !isEraser ? 2 : 1,
                ),
              ),
            ),
          )),
          const SizedBox(width: 8),
          IconButton(
            icon: const Icon(Icons.undo),
            onPressed: onUndo,
            tooltip: '撤销',
            iconSize: 20,
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline),
            onPressed: onClear,
            tooltip: '清除本页',
            iconSize: 20,
          ),
          if (onSave != null)
            IconButton(
              icon: const Icon(Icons.save_outlined),
              onPressed: onSave,
              tooltip: '保存标注',
              iconSize: 20,
            ),
          if (onClose != null) ...[
            const SizedBox(width: 4),
            IconButton(
              icon: const Icon(Icons.close),
              onPressed: onClose,
              tooltip: '关闭工具栏',
              iconSize: 20,
            ),
          ],
        ],
      ),
    );
  }
}
