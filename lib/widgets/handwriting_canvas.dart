import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/stroke_provider.dart';
import '../models/stroke.dart';

class HandwritingCanvas extends StatelessWidget {
  final int pageNumber;
  final Widget child;

  const HandwritingCanvas({
    super.key,
    required this.pageNumber,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return Consumer<StrokeProvider>(
      builder: (context, strokeProvider, _) {
        return Stack(
          children: [
            child,
            Positioned.fill(
              child: _DrawingOverlay(
                pageNumber: pageNumber,
                strokes: strokeProvider.getStrokesForPage(pageNumber),
                currentStroke: strokeProvider.currentStroke?.pageNumber == pageNumber
                    ? strokeProvider.currentStroke
                    : null,
                onStrokeStart: (point) => strokeProvider.startStroke(pageNumber, point),
                onStrokeUpdate: (point) => strokeProvider.addPoint(point),
                onStrokeEnd: () => strokeProvider.endStroke(),
                isEnabled: true,
              ),
            ),
          ],
        );
      },
    );
  }
}

class _DrawingOverlay extends StatelessWidget {
  final int pageNumber;
  final List<Stroke> strokes;
  final Stroke? currentStroke;
  final void Function(Offset) onStrokeStart;
  final void Function(Offset) onStrokeUpdate;
  final VoidCallback onStrokeEnd;
  final bool isEnabled;

  const _DrawingOverlay({
    required this.pageNumber,
    required this.strokes,
    this.currentStroke,
    required this.onStrokeStart,
    required this.onStrokeUpdate,
    required this.onStrokeEnd,
    this.isEnabled = true,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onPanStart: isEnabled
          ? (details) {
              final renderBox = context.findRenderObject() as RenderBox;
              final localPosition = renderBox.globalToLocal(details.globalPosition);
              onStrokeStart(localPosition);
            }
          : null,
      onPanUpdate: isEnabled
          ? (details) {
              final renderBox = context.findRenderObject() as RenderBox;
              final localPosition = renderBox.globalToLocal(details.globalPosition);
              onStrokeUpdate(localPosition);
            }
          : null,
      onPanEnd: isEnabled ? (_) => onStrokeEnd() : null,
      child: CustomPaint(
        painter: _StrokePainter(
          strokes: strokes,
          currentStroke: currentStroke,
        ),
        size: Size.infinite,
      ),
    );
  }
}

class _StrokePainter extends CustomPainter {
  final List<Stroke> strokes;
  final Stroke? currentStroke;

  _StrokePainter({
    required this.strokes,
    this.currentStroke,
  });

  @override
  void paint(Canvas canvas, Size size) {
    for (final stroke in strokes) {
      _drawStroke(canvas, stroke);
    }
    if (currentStroke != null) {
      _drawStroke(canvas, currentStroke!);
    }
  }

  void _drawStroke(Canvas canvas, Stroke stroke) {
    if (stroke.points.length < 2) return;

    final paint = Paint()
      ..color = stroke.color
      ..strokeWidth = stroke.strokeWidth
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..style = PaintingStyle.stroke
      ..isAntiAlias = true;

    final path = Path();
    path.moveTo(stroke.points.first.dx, stroke.points.first.dy);

    if (stroke.points.length == 2) {
      path.lineTo(stroke.points.last.dx, stroke.points.last.dy);
    } else {
      for (int i = 1; i < stroke.points.length - 1; i++) {
        final midX = (stroke.points[i].dx + stroke.points[i + 1].dx) / 2;
        final midY = (stroke.points[i].dy + stroke.points[i + 1].dy) / 2;
        path.quadraticBezierTo(
          stroke.points[i].dx,
          stroke.points[i].dy,
          midX,
          midY,
        );
      }
      final last = stroke.points.last;
      path.lineTo(last.dx, last.dy);
    }

    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(covariant _StrokePainter oldDelegate) {
    return oldDelegate.strokes != strokes || oldDelegate.currentStroke != currentStroke;
  }
}
