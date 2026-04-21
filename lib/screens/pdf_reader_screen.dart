import 'dart:io';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:pdfrx/pdfrx.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:markdown/markdown.dart' as md;
import '../providers/pdf_provider.dart';
import '../providers/stroke_provider.dart';
import '../providers/note_provider.dart';
import '../models/note.dart';
import '../models/stroke.dart';
import '../widgets/drawing_toolbar.dart';
import '../widgets/note_marker.dart';
import '../services/llm_service.dart';
import '../screens/settings_screen.dart';
import 'auxiliary_panel.dart';

class PdfReaderScreen extends StatefulWidget {
  const PdfReaderScreen({super.key});

  @override
  State<PdfReaderScreen> createState() => _PdfReaderScreenState();
}

class _PdfReaderScreenState extends State<PdfReaderScreen> {
  bool _showDrawingTools = false;
  bool _showAuxiliaryPanel = false;
  bool _showBookmarks = false;
  final _pageController = TextEditingController();
  final _llmService = LLMService();

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: _buildAppBar(),
      body: Consumer<PdfProvider>(
        builder: (context, pdfProvider, _) {
          if (pdfProvider.isLoading) {
            return const Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  CircularProgressIndicator(),
                  SizedBox(height: 16),
                  Text('正在加载PDF...'),
                ],
              ),
            );
          }

          if (pdfProvider.errorMessage != null) {
            return Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.error_outline,
                    size: 64,
                    color: Theme.of(context).colorScheme.error,
                  ),
                  const SizedBox(height: 16),
                  Text(
                    pdfProvider.errorMessage!,
                    style: Theme.of(context).textTheme.bodyLarge,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 16),
                  ElevatedButton.icon(
                    onPressed: _openPdf,
                    icon: const Icon(Icons.folder_open),
                    label: const Text('打开PDF'),
                  ),
                ],
              ),
            );
          }

          if (!pdfProvider.isDocumentLoaded) {
            return Row(
              children: [
                Expanded(child: _buildEmptyState()),
                if (_showAuxiliaryPanel && MediaQuery.of(context).size.width > 600)
                  _buildAuxiliaryPanel(),
              ],
            );
          }

          return Row(
            children: [
              Expanded(
                child: _buildPdfView(pdfProvider),
              ),
              if (_showAuxiliaryPanel && MediaQuery.of(context).size.width > 600)
                _buildAuxiliaryPanel(),
            ],
          );
        },
      ),
      bottomNavigationBar: Consumer<PdfProvider>(
        builder: (context, pdfProvider, _) {
          if (!pdfProvider.isDocumentLoaded) return const SizedBox.shrink();
          return _buildBottomBar(pdfProvider);
        },
      ),
    );
  }

  PreferredSizeWidget _buildAppBar() {
    return AppBar(
      title: Consumer<PdfProvider>(
        builder: (context, pdfProvider, _) {
          return Text(
            pdfProvider.fileName ?? '智能课堂助手',
            style: const TextStyle(fontSize: 16),
          );
        },
      ),
      leading: Consumer<PdfProvider>(
        builder: (context, pdfProvider, _) {
          if (!pdfProvider.isDocumentLoaded) return const SizedBox.shrink();
          return IconButton(
            icon: const Icon(Icons.folder_open),
            onPressed: _openPdf,
            tooltip: '打开PDF',
          );
        },
      ),
      actions: [
        Consumer<PdfProvider>(
          builder: (context, pdfProvider, _) {
            if (!pdfProvider.isDocumentLoaded) {
              return IconButton(
                icon: const Icon(Icons.folder_open),
                onPressed: _openPdf,
                tooltip: '打开PDF',
              );
            }
            return Row(
              children: [
                IconButton(
                  icon: Icon(
                    _showDrawingTools ? Icons.draw : Icons.draw_outlined,
                  ),
                  onPressed: () {
                    setState(() {
                      _showDrawingTools = !_showDrawingTools;
                    });
                  },
                  tooltip: '手写笔记',
                ),
                IconButton(
                  icon: Icon(
                    _showBookmarks ? Icons.bookmark : Icons.bookmark_outline,
                  ),
                  onPressed: () {
                    setState(() {
                      _showBookmarks = !_showBookmarks;
                    });
                  },
                  tooltip: '书签',
                ),
                IconButton(
                  icon: Icon(
                    _showAuxiliaryPanel
                        ? Icons.view_sidebar
                        : Icons.view_sidebar_outlined,
                  ),
                  onPressed: () {
                    final width = MediaQuery.of(context).size.width;
                    if (width > 600) {
                      setState(() {
                        _showAuxiliaryPanel = !_showAuxiliaryPanel;
                      });
                    } else {
                      _showAuxiliaryPanelAsSheet(context);
                    }
                  },
                  tooltip: '辅助工具',
                ),
                IconButton(
                  icon: const Icon(Icons.settings_outlined),
                  onPressed: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(builder: (_) => const SettingsScreen()),
                    );
                  },
                ),
              ],
            );
          },
        ),
      ],
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.picture_as_pdf_outlined,
            size: 80,
            color: Theme.of(context).colorScheme.outline,
          ).animate().fadeIn(duration: 600.ms),
          const SizedBox(height: 24),
          Text(
            '打开PDF开始学习',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 12),
          Text(
            '支持书签跳转、手写笔记、AI辅助记录',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 32),
          ElevatedButton.icon(
            onPressed: _openPdf,
            icon: const Icon(Icons.folder_open),
            label: const Text('选择PDF文件'),
          ).animate().fadeIn(delay: 200.ms).slideY(begin: 0.1),
          const SizedBox(height: 16),
          OutlinedButton.icon(
            onPressed: () {
              setState(() {
                _showAuxiliaryPanel = true;
              });
            },
            icon: const Icon(Icons.mic),
            label: const Text('打开辅助工具'),
          ),
        ],
      ),
    );
  }

  Widget _buildPdfView(PdfProvider pdfProvider) {
    return Stack(
      children: [
        PdfViewer.file(
          pdfProvider.filePath!,
          controller: pdfProvider.controller,
          params: PdfViewerParams(
            enableTextSelection: !_showDrawingTools,
            onPageChanged: (page) {
              if (page != null) {
                pdfProvider.setCurrentPage(page);
              }
            },
            pageOverlaysBuilder: (context, pageRect, page) {
              return _buildPageOverlays(context, pageRect, page, pdfProvider);
            },
            viewerOverlayBuilder: (context, size, handleLinkTap) {
              if (!_showDrawingTools) return [];
              return _buildDrawingOverlay(context, size, pdfProvider, handleLinkTap);
            },
          ),
        ),
        if (_showDrawingTools)
          Positioned(
            top: 8,
            left: 0,
            right: 0,
            child: Center(
              child: Consumer<StrokeProvider>(
                builder: (context, strokeProvider, _) {
                  return DrawingToolbar(
                    isEraser: strokeProvider.isEraser,
                    currentColor: strokeProvider.currentColor,
                    currentStrokeWidth: strokeProvider.currentStrokeWidth,
                    currentCategory: strokeProvider.currentCategory,
                    categories: strokeProvider.categories,
                    onCategoryChanged: (cat) => strokeProvider.setCurrentCategory(cat),
                    onUndo: () => strokeProvider.undoLastStroke(),
                    onClear: () => strokeProvider.clearStrokesForPage(pdfProvider.currentPage),
                    onSave: () {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text('标注已自动保存'),
                          duration: Duration(seconds: 1),
                        ),
                      );
                    },
                    onColorChanged: (color) => strokeProvider.setColor(color),
                    onStrokeWidthChanged: (width) => strokeProvider.setStrokeWidth(width),
                    onToggleEraser: () => strokeProvider.toggleEraser(),
                    onClose: () {
                      setState(() {
                        _showDrawingTools = false;
                      });
                    },
                  );
                },
              ),
            ),
          ),
        if (_showBookmarks)
          Positioned(
            top: _showDrawingTools ? 60 : 8,
            right: 8,
            child: _buildBookmarksPanel(pdfProvider),
          ),
        _buildNoteMarkers(pdfProvider),
        if (!_showDrawingTools)
          _buildTextSelectionToolbar(pdfProvider),
      ],
    );
  }

  Widget _buildTextSelectionToolbar(PdfProvider pdfProvider) {
    return Positioned(
      bottom: 80,
      right: 16,
      child: FloatingActionButton.small(
        heroTag: 'ask_pdf',
        onPressed: () => _showManualQuestionDialog(pdfProvider),
        child: const Icon(Icons.question_answer),
      ),
    );
  }

  void _showManualQuestionDialog(PdfProvider pdfProvider) async {
    final textController = TextEditingController();
    int startPage = pdfProvider.currentPage;
    int endPage = pdfProvider.currentPage;
    String pageRangeMode = '当前页';

    final result = await showDialog<String>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('针对PDF内容提问'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'PDF: ${pdfProvider.fileName ?? "未命名"}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 16),
                Text(
                  '选择页面范围',
                  style: Theme.of(context).textTheme.labelLarge,
                ),
                const SizedBox(height: 8),
                SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(value: '当前页', label: Text('当前页')),
                    ButtonSegment(value: '前后1页', label: Text('前后1页')),
                    ButtonSegment(value: '前后3页', label: Text('前后3页')),
                    ButtonSegment(value: '全部', label: Text('全部')),
                    ButtonSegment(value: '自定义', label: Text('自定义')),
                  ],
                  selected: {pageRangeMode},
                  onSelectionChanged: (Set<String> selection) {
                    setDialogState(() {
                      pageRangeMode = selection.first;
                      if (pageRangeMode == '当前页') {
                        startPage = pdfProvider.currentPage;
                        endPage = pdfProvider.currentPage;
                      } else if (pageRangeMode == '前后1页') {
                        startPage = (pdfProvider.currentPage - 1).clamp(1, pdfProvider.totalPages);
                        endPage = (pdfProvider.currentPage + 1).clamp(1, pdfProvider.totalPages);
                      } else if (pageRangeMode == '前后3页') {
                        startPage = (pdfProvider.currentPage - 3).clamp(1, pdfProvider.totalPages);
                        endPage = (pdfProvider.currentPage + 3).clamp(1, pdfProvider.totalPages);
                      } else if (pageRangeMode == '全部') {
                        startPage = 1;
                        endPage = pdfProvider.totalPages;
                      }
                    });
                  },
                ),
                if (pageRangeMode == '自定义') ...[
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          decoration: const InputDecoration(
                            labelText: '起始页',
                            border: OutlineInputBorder(),
                          ),
                          keyboardType: TextInputType.number,
                          onChanged: (value) {
                            final page = int.tryParse(value);
                            if (page != null) {
                              startPage = page.clamp(1, pdfProvider.totalPages);
                            }
                          },
                        ),
                      ),
                      const SizedBox(width: 12),
                      const Text('至'),
                      const SizedBox(width: 12),
                      Expanded(
                        child: TextField(
                          decoration: const InputDecoration(
                            labelText: '结束页',
                            border: OutlineInputBorder(),
                          ),
                          keyboardType: TextInputType.number,
                          onChanged: (value) {
                            final page = int.tryParse(value);
                            if (page != null) {
                              endPage = page.clamp(1, pdfProvider.totalPages);
                            }
                          },
                        ),
                      ),
                    ],
                  ),
                ] else ...[
                  const SizedBox(height: 8),
                  Text(
                    '将包含第 $startPage 至 $endPage 页（共 ${pdfProvider.totalPages} 页）',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.primary,
                    ),
                  ),
                ],
                const SizedBox(height: 16),
                TextField(
                  controller: textController,
                  maxLines: 3,
                  decoration: const InputDecoration(
                    hintText: '输入你的问题...',
                    border: OutlineInputBorder(),
                  ),
                  autofocus: true,
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, textController.text),
              child: const Text('提问'),
            ),
          ],
        ),
      ),
    );

    textController.dispose();

    if (result != null && result.isNotEmpty && mounted) {
      await _processQuestion(result, startPage, endPage, pdfProvider);
    }
  }

  Future<void> _processQuestion(String question, int startPage, int endPage, PdfProvider pdfProvider) async {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        content: Row(
          children: [
            const CircularProgressIndicator(),
            const SizedBox(width: 16),
            Expanded(child: Text('正在从第 $startPage-$endPage 页检索答案...')),
          ],
        ),
      ),
    );

    try {
      final contextText = await pdfProvider.getPagesText(startPage, endPage);

      final answer = await _llmService.generateAnswer(
        question,
        context: contextText.isEmpty ? null : contextText,
        systemPrompt: '你是学习助手。根据提供的PDF内容回答问题。简洁明了。',
      );

      if (mounted) {
        Navigator.pop(context);

        final noteProvider = context.read<NoteProvider>();
        await noteProvider.createNote(
          content: '**问题:** $question\n\n**回答:** $answer',
          type: NoteType.manual,
          pdfPath: pdfProvider.filePath,
          pdfPage: startPage == endPage ? startPage : null,
        );

        _showAnswerDialog(question, answer, pdfProvider, startPage, endPage);
      }
    } catch (e) {
      if (mounted) {
        Navigator.pop(context);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('回答失败: $e')),
        );
      }
    }
  }

  void _showAnswerDialog(String question, String answer, PdfProvider pdfProvider, int startPage, int endPage) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Row(
          children: [
            Icon(Icons.lightbulb, color: Theme.of(context).colorScheme.primary),
            const SizedBox(width: 8),
            Expanded(child: Text('AI 回答 (第 $startPage-$endPage 页)')),
          ],
        ),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '问题: $question',
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const Divider(),
              MarkdownBody(
                data: answer,
                shrinkWrap: true,
                softLineBreak: true,
                selectable: true,
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('关闭'),
          ),
        ],
      ),
    );
  }

  List<Widget> _buildPageOverlays(
    BuildContext context,
    Rect pageRect,
    PdfPage page,
    PdfProvider pdfProvider,
  ) {
    return [
      Consumer<StrokeProvider>(
        builder: (context, strokeProvider, _) {
          final strokes = strokeProvider.getStrokesForPage(page.pageNumber);
          final currentStroke = strokeProvider.currentStroke;

          final allStrokes = <Stroke>[];
          allStrokes.addAll(strokes);
          if (currentStroke != null &&
              currentStroke.pageNumber == page.pageNumber &&
              currentStroke.points.isNotEmpty) {
            allStrokes.add(currentStroke);
          }

          if (allStrokes.isEmpty) return const SizedBox.shrink();

          return CustomPaint(
            size: pageRect.size,
            painter: _PageStrokesPainter(
              strokes: allStrokes,
              page: page,
              scaledPageSize: pageRect.size,
            ),
          );
        },
      ),
    ];
  }

  List<Widget> _buildDrawingOverlay(
    BuildContext context,
    Size size,
    PdfProvider pdfProvider,
    PdfViewerHandleLinkTap handleLinkTap,
  ) {
    return [
      Positioned.fill(
        child: GestureDetector(
          behavior: HitTestBehavior.translucent,
          onPanStart: (details) {
            _onDrawingStart(details, pdfProvider);
          },
          onPanUpdate: (details) {
            _onDrawingUpdate(details, pdfProvider);
          },
          onPanEnd: (details) {
            _onDrawingEnd(pdfProvider);
          },
        ),
      ),
    ];
  }

  void _onDrawingStart(DragStartDetails details, PdfProvider pdfProvider) {
    if (!pdfProvider.controller.isReady) return;

    final result = _screenToPdfPoint(
      details.globalPosition,
      pdfProvider,
    );

    if (result != null) {
      final strokeProvider = context.read<StrokeProvider>();
      final pageNumber = result['pageNumber']!;
      final pdfPoint = result['pdfPoint']!;

      if (strokeProvider.isEraser) {
        strokeProvider.startEraser(pageNumber, pdfPoint);
      } else {
        strokeProvider.startStroke(pageNumber, pdfPoint);
      }
    }
  }

  void _onDrawingUpdate(DragUpdateDetails details, PdfProvider pdfProvider) {
    final strokeProvider = context.read<StrokeProvider>();
    if (!strokeProvider.isDrawing) return;

    final result = _screenToPdfPoint(
      details.globalPosition,
      pdfProvider,
    );

    if (result != null) {
      if (strokeProvider.isEraser) {
        strokeProvider.addEraserPoint(result['pdfPoint']!);
      } else {
        strokeProvider.addPoint(result['pdfPoint']!);
      }
    }
  }

  void _onDrawingEnd(PdfProvider pdfProvider) {
    final strokeProvider = context.read<StrokeProvider>();
    if (!strokeProvider.isDrawing) return;

    if (strokeProvider.isEraser) {
      strokeProvider.endEraser();
    } else {
      strokeProvider.endStroke();
    }
  }

  Map<String, dynamic>? _screenToPdfPoint(
    Offset globalPosition,
    PdfProvider pdfProvider,
  ) {
    if (!pdfProvider.controller.isReady) return null;

    try {
      final document = pdfProvider.document;
      if (document == null) return null;

      final docPosition = pdfProvider.controller.globalToDocument(globalPosition);
      if (docPosition == null) return null;

      final pageLayouts = pdfProvider.controller.layout.pageLayouts;
      int? pageIndex;
      for (int i = 0; i < pageLayouts.length; i++) {
        if (pageLayouts[i].contains(docPosition)) {
          pageIndex = i;
          break;
        }
      }

      if (pageIndex == null) return null;

      final pageLayout = pageLayouts[pageIndex];
      final page = document.pages[pageIndex];
      final inPageOffset = docPosition - pageLayout.topLeft;

      final pdfPoint = inPageOffset.toPdfPoint(
        page: page,
        scaledPageSize: pageLayout.size,
      );

      return {
        'pageNumber': pageIndex + 1,
        'pdfPoint': Offset(pdfPoint.x, pdfPoint.y),
      };
    } catch (e) {
      debugPrint('Error converting screen to PDF point: $e');
    }

    return null;
  }

  Widget _buildNoteMarkers(PdfProvider pdfProvider) {
    return Consumer<NoteProvider>(
      builder: (context, noteProvider, _) {
        final pageNotes = noteProvider.notes
            .where((n) =>
                n.pdfPath == pdfProvider.filePath &&
                n.pdfPage == pdfProvider.currentPage)
            .toList();

        if (pageNotes.isEmpty) return const SizedBox.shrink();

        return Positioned(
          right: 16,
          bottom: 80,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: pageNotes.map((note) {
              return Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: NoteMarker(
                  note: note,
                  onTap: () => _showNoteDetail(note),
                ),
              );
            }).toList(),
          ),
        );
      },
    );
  }

  Widget _buildBookmarksPanel(PdfProvider pdfProvider) {
    return Consumer<PdfProvider>(
      builder: (context, provider, _) {
        return Container(
          width: 240,
          constraints: BoxConstraints(
            maxHeight: MediaQuery.of(context).size.height * 0.6,
          ),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surface,
            borderRadius: BorderRadius.circular(12),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.15),
                blurRadius: 12,
                offset: const Offset(0, 4),
              ),
            ],
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.all(12),
                child: Row(
                  children: [
                    Text(
                      '书签',
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                    const Spacer(),
                    IconButton(
                      icon: const Icon(Icons.add, size: 18),
                      onPressed: () => _addBookmark(provider),
                      tooltip: '添加书签',
                    ),
                  ],
                ),
              ),
              const Divider(height: 1),
              if (provider.bookmarks.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('暂无书签', style: TextStyle(fontSize: 13)),
                )
              else
                Flexible(
                  child: ListView.builder(
                    shrinkWrap: true,
                    padding: EdgeInsets.zero,
                    itemCount: provider.bookmarks.length,
                    itemBuilder: (context, index) {
                      final bookmark = provider.bookmarks[index];
                      return ListTile(
                        dense: true,
                        leading: Icon(
                          Icons.bookmark,
                          size: 18,
                          color: Theme.of(context).colorScheme.primary,
                        ),
                        title: Text(
                          bookmark.title,
                          style: const TextStyle(fontSize: 13),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        subtitle: Text(
                          '第 ${bookmark.pageNumber} 页',
                          style: const TextStyle(fontSize: 11),
                        ),
                        trailing: IconButton(
                          icon: const Icon(Icons.close, size: 14),
                          onPressed: () => provider.removeBookmark(bookmark.id),
                        ),
                        onTap: () {
                          provider.goToPage(bookmark.pageNumber);
                          setState(() => _showBookmarks = false);
                        },
                      );
                    },
                  ),
                ),
            ],
          ),
        ).animate().fadeIn(duration: 200.ms).slideX(begin: 0.1);
      },
    );
  }

  Widget _buildAuxiliaryPanel() {
    final screenWidth = MediaQuery.of(context).size.width;
    final panelWidth = screenWidth > 600 ? 360.0 : screenWidth * 0.85;
    return Container(
      width: panelWidth,
      decoration: BoxDecoration(
        border: Border(
          left: BorderSide(
            color: Theme.of(context).colorScheme.outlineVariant,
          ),
        ),
      ),
      child: const AuxiliaryPanel(),
    );
  }

  void _showAuxiliaryPanelAsSheet(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (context) => DraggableScrollableSheet(
        initialChildSize: 0.7,
        minChildSize: 0.3,
        maxChildSize: 0.95,
        expand: false,
        builder: (context, scrollController) => Container(
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surface,
            borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
          ),
          child: Column(
            children: [
              Container(
                margin: const EdgeInsets.symmetric(vertical: 8),
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.outline,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              const Expanded(child: AuxiliaryPanel()),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildBottomBar(PdfProvider pdfProvider) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        border: Border(
          top: BorderSide(
            color: Theme.of(context).colorScheme.outlineVariant,
          ),
        ),
      ),
      child: Row(
        children: [
          IconButton(
            icon: const Icon(Icons.chevron_left),
            onPressed: pdfProvider.currentPage > 1
                ? () {
                    pdfProvider.goToPage(pdfProvider.currentPage - 1);
                  }
                : null,
          ),
          Expanded(
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                SizedBox(
                  width: 60,
                  child: TextField(
                    controller: _pageController,
                    keyboardType: TextInputType.number,
                    textAlign: TextAlign.center,
                    decoration: InputDecoration(
                      isDense: true,
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 8,
                      ),
                      border: const OutlineInputBorder(),
                      hintText: '${pdfProvider.currentPage}',
                    ),
                    onSubmitted: (value) {
                      final page = int.tryParse(value);
                      if (page != null && page >= 1 && page <= pdfProvider.totalPages) {
                        pdfProvider.goToPage(page);
                      }
                      _pageController.clear();
                    },
                  ),
                ),
                Text(
                  ' / ${pdfProvider.totalPages}',
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
              ],
            ),
          ),
          IconButton(
            icon: const Icon(Icons.chevron_right),
            onPressed: pdfProvider.currentPage < pdfProvider.totalPages
                ? () {
                    pdfProvider.goToPage(pdfProvider.currentPage + 1);
                  }
                : null,
          ),
          const SizedBox(width: 8),
          IconButton(
            icon: const Icon(Icons.zoom_out),
            onPressed: () => pdfProvider.zoomOut(),
            tooltip: '缩小',
          ),
          Text(
            '${(pdfProvider.zoomLevel * 100).toInt()}%',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          IconButton(
            icon: const Icon(Icons.zoom_in),
            onPressed: () => pdfProvider.zoomIn(),
            tooltip: '放大',
          ),
        ],
      ),
    );
  }

  Future<void> _openPdf() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['pdf'],
    );

    if (result != null && result.files.single.path != null && mounted) {
      await context.read<PdfProvider>().openPdf(result.files.single.path!);
    }
  }

  Future<void> _addBookmark(PdfProvider pdfProvider) async {
    final controller = TextEditingController(
      text: '第 ${pdfProvider.currentPage} 页',
    );

    final title = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('添加书签'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(
            labelText: '书签名称',
            border: OutlineInputBorder(),
          ),
          autofocus: true,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, controller.text),
            child: const Text('添加'),
          ),
        ],
      ),
    );

    if (title != null && title.isNotEmpty) {
      pdfProvider.addBookmark(title);
    }

    controller.dispose();
  }

  void _showNoteDetail(Note note) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Row(
          children: [
            Icon(
              _getNoteTypeIcon(note.type),
              size: 20,
              color: _getNoteTypeColor(note.type),
            ),
            const SizedBox(width: 8),
            Text(
              _getNoteTypeName(note.type),
              style: const TextStyle(fontSize: 16),
            ),
          ],
        ),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (note.type == NoteType.photo && note.imagePath != null) ...[
                GestureDetector(
                  onTap: () => _showFullScreenImage(context, note.imagePath!, heroTag: 'note_marker_${note.id}'),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Hero(
                      tag: 'note_marker_${note.id}',
                      child: Image.file(
                        File(note.imagePath!),
                        width: 300,
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
                ),
                const SizedBox(height: 12),
              ],
              MarkdownBody(
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
              ),
              if (note.pdfPage != null) ...[
                const SizedBox(height: 8),
                Text(
                  '标记位置: 第 ${note.pdfPage} 页',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('关闭'),
          ),
        ],
      ),
    );
  }

  void _showFullScreenImage(BuildContext context, String imagePath, {String? heroTag}) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => Scaffold(
          backgroundColor: Colors.black,
          appBar: AppBar(
            backgroundColor: Colors.black,
            foregroundColor: Colors.white,
            iconTheme: const IconThemeData(color: Colors.white),
          ),
          body: GestureDetector(
            onTap: () => Navigator.pop(context),
            child: InteractiveViewer(
              minScale: 0.5,
              maxScale: 5.0,
              child: Center(
                child: heroTag != null
                    ? Hero(
                        tag: heroTag,
                        child: Image.file(
                          File(imagePath),
                          fit: BoxFit.contain,
                        ),
                      )
                    : Image.file(
                        File(imagePath),
                        fit: BoxFit.contain,
                      ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  IconData _getNoteTypeIcon(NoteType type) {
    switch (type) {
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

  Color _getNoteTypeColor(NoteType type) {
    switch (type) {
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

  String _getNoteTypeName(NoteType type) {
    switch (type) {
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

class _PageStrokesPainter extends CustomPainter {
  final List<Stroke> strokes;
  final PdfPage page;
  final Size scaledPageSize;

  _PageStrokesPainter({
    required this.strokes,
    required this.page,
    required this.scaledPageSize,
  });

  @override
  void paint(Canvas canvas, Size size) {
    for (final stroke in strokes) {
      final screenPoints = stroke.points.map((pdfPoint) {
        return PdfPoint(pdfPoint.dx, pdfPoint.dy).toOffset(
          page: page,
          scaledPageSize: scaledPageSize,
        );
      }).toList();

      if (screenPoints.isEmpty) continue;

      final paint = Paint()
        ..color = stroke.color
        ..strokeWidth = stroke.strokeWidth
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round
        ..style = PaintingStyle.stroke;

      if (screenPoints.length == 1) {
        canvas.drawPoints(PointMode.points, screenPoints, paint);
        continue;
      }

      final path = Path();
      path.moveTo(screenPoints[0].dx, screenPoints[0].dy);

      for (int i = 1; i < screenPoints.length; i++) {
        path.lineTo(screenPoints[i].dx, screenPoints[i].dy);
      }

      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(_PageStrokesPainter oldDelegate) {
    if (strokes.length != oldDelegate.strokes.length) return true;
    for (int i = 0; i < strokes.length; i++) {
      if (strokes[i].points.length != oldDelegate.strokes[i].points.length ||
          strokes[i].color != oldDelegate.strokes[i].color ||
          strokes[i].strokeWidth != oldDelegate.strokes[i].strokeWidth) {
        return true;
      }
    }
    return false;
  }
}
