import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:markdown/markdown.dart' as md;
import 'package:flutter_animate/flutter_animate.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as path;
import '../providers/asr_provider.dart';
import '../providers/note_provider.dart';
import '../providers/question_provider.dart';
import '../providers/pdf_provider.dart';
import '../models/asr_result.dart';
import '../models/note.dart';
import '../models/question.dart';
import '../widgets/note_preview_dialog.dart';

class AuxiliaryPanel extends StatefulWidget {
  const AuxiliaryPanel({super.key});

  @override
  State<AuxiliaryPanel> createState() => _AuxiliaryPanelState();
}

class _AuxiliaryPanelState extends State<AuxiliaryPanel>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;
  bool _isOrganizing = false;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        TabBar(
          controller: _tabController,
          tabs: const [
            Tab(text: '语音识别', icon: Icon(Icons.mic, size: 18)),
            Tab(text: '笔记', icon: Icon(Icons.note, size: 18)),
            Tab(text: '问题', icon: Icon(Icons.help, size: 18)),
          ],
          labelStyle: const TextStyle(fontSize: 12),
        ),
        Expanded(
          child: TabBarView(
            controller: _tabController,
            children: [
              _buildASRTab(),
              _buildNotesTab(),
              _buildQuestionsTab(),
            ],
          ),
        ),
        _buildControlBar(),
      ],
    );
  }

  Widget _buildASRTab() {
    return Consumer2<ASRProvider, PdfProvider>(
      builder: (context, asr, pdfProvider, _) {
        return Column(
          children: [
            ASRStatusIndicator(
              status: asr.status,
              errorMessage: asr.errorMessage,
              loadProgress: asr.loadProgress,
            ),
            if (asr.results.isNotEmpty)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    Text(
                      '${asr.results.length} 条记录',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                    const Spacer(),
                    PopupMenuButton<String>(
                      icon: const Icon(Icons.file_download, size: 18),
                      tooltip: '导出',
                      onSelected: (value) => _handleAsrExport(value, asr),
                      itemBuilder: (context) => [
                        const PopupMenuItem(
                          value: 'export_text',
                          child: Row(
                            children: [
                              Icon(Icons.description, size: 18),
                              SizedBox(width: 8),
                              Text('导出为文本'),
                            ],
                          ),
                        ),
                        const PopupMenuItem(
                          value: 'export_json',
                          child: Row(
                            children: [
                              Icon(Icons.code, size: 18),
                              SizedBox(width: 8),
                              Text('导出为JSON'),
                            ],
                          ),
                        ),
                        const PopupMenuDivider(),
                        const PopupMenuItem(
                          value: 'share_text',
                          child: Row(
                            children: [
                              Icon(Icons.share, size: 18),
                              SizedBox(width: 8),
                              Text('分享文本'),
                            ],
                          ),
                        ),
                        const PopupMenuItem(
                          value: 'share_json',
                          child: Row(
                            children: [
                              Icon(Icons.share, size: 18),
                              SizedBox(width: 8),
                              Text('分享JSON'),
                            ],
                          ),
                        ),
                      ],
                    ),
                    TextButton.icon(
                      onPressed: () => _clearAllAsrResults(asr),
                      icon: const Icon(Icons.delete_sweep, size: 16),
                      label: const Text('清空全部', style: TextStyle(fontSize: 12)),
                      style: TextButton.styleFrom(
                        foregroundColor: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ],
                ),
              ),
            Expanded(
              child: asr.results.isEmpty
                  ? Center(
                      child: Padding(
                        padding: const EdgeInsets.all(24),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Icon(
                              Icons.mic_none,
                              size: 40,
                              color: Theme.of(context).colorScheme.outline,
                            ),
                            const SizedBox(height: 12),
                            Text(
                              '点击下方按钮开始录音',
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                          ],
                        ),
                      ),
                    )
                  : ListView.builder(
                      padding: const EdgeInsets.all(8),
                      itemCount: asr.results.length,
                      key: const PageStorageKey('asr_results_list'),
                      itemBuilder: (context, index) {
                        final result = asr.results[asr.results.length - 1 - index];
                        return _buildASRResultCard(result, asr, pdfProvider);
                      },
                    ),
            ),
          ],
        );
      },
    );
  }

  Widget _buildASRResultCard(
      ASRResult result, ASRProvider asr, PdfProvider pdfProvider) {
    return Card(
      key: ValueKey(result.timestamp.toIso8601String()),
      margin: const EdgeInsets.only(bottom: 6),
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              result.text,
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 4),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                Text(
                  '${result.timestamp.hour}:${result.timestamp.minute.toString().padLeft(2, '0')}',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                    fontSize: 10,
                  ),
                ),
                const SizedBox(width: 4),
                _buildMiniButton(
                  icon: Icons.edit,
                  tooltip: '编辑',
                  onPressed: () => _editAsrResult(result, asr),
                ),
                _buildMiniButton(
                  icon: Icons.auto_awesome,
                  tooltip: 'AI优化',
                  onPressed: () => _optimizeAsrResult(result, pdfProvider),
                ),
                _buildMiniButton(
                  icon: Icons.note_add,
                  tooltip: '保存为笔记',
                  onPressed: () => _saveAsrResultAsNote(result, pdfProvider),
                ),
                _buildMiniButton(
                  icon: Icons.question_mark,
                  tooltip: '检测问题',
                  onPressed: () async {
                    final pdfProvider = context.read<PdfProvider>();
                    String? pdfContext;
                    if (pdfProvider.isDocumentLoaded) {
                      pdfContext = await pdfProvider.getSurroundingPagesText(range: 1);
                    }
                    if (mounted) {
                      context.read<QuestionProvider>().detectQuestion(
                        result.text,
                        context: pdfContext,
                      );
                    }
                  },
                ),
                _buildMiniButton(
                  icon: Icons.delete_outline,
                  tooltip: '删除',
                  onPressed: () => _deleteAsrResult(result, asr),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMiniButton({
    required IconData icon,
    required String tooltip,
    required VoidCallback onPressed,
  }) {
    return IconButton(
      icon: Icon(icon, size: 16),
      onPressed: onPressed,
      tooltip: tooltip,
      visualDensity: VisualDensity.compact,
      padding: EdgeInsets.zero,
      constraints: const BoxConstraints(minWidth: 28, minHeight: 28),
    );
  }

  Widget _buildNotesTab() {
    return Consumer2<NoteProvider, PdfProvider>(
      builder: (context, noteProvider, pdfProvider, _) {
        final notes = pdfProvider.isDocumentLoaded && pdfProvider.filePath != null
            ? noteProvider.getNotesForPdf(pdfProvider.filePath!)
            : noteProvider.notes;

        if (notes.isEmpty) {
          return Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  Icons.note_alt_outlined,
                  size: 40,
                  color: Theme.of(context).colorScheme.outline,
                ),
                const SizedBox(height: 12),
                Text(
                  '暂无笔记',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 8),
                Text(
                  '录音内容可自动保存为笔记',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          );
        }

        return ListView.builder(
          padding: const EdgeInsets.all(8),
          itemCount: notes.length,
          key: const PageStorageKey('notes_list'),
          itemBuilder: (context, index) {
            final note = notes[index];
            return _buildNoteCard(note, noteProvider, pdfProvider);
          },
        );
      },
    );
  }

  Widget _buildNoteCard(Note note, NoteProvider noteProvider, PdfProvider pdfProvider) {
    return Card(
      key: ValueKey(note.id),
      margin: const EdgeInsets.only(bottom: 6),
      child: InkWell(
        onTap: () => _showNotePreview(note, noteProvider, pdfProvider),
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      color: _getNoteTypeColor(note.type).withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      _getNoteTypeName(note.type),
                      style: TextStyle(
                        fontSize: 10,
                        color: _getNoteTypeColor(note.type),
                      ),
                    ),
                  ),
                  const Spacer(),
                  if (note.pdfPage != null)
                    Text(
                      'P${note.pdfPage}',
                      style: TextStyle(
                        fontSize: 10,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  _buildMiniButton(
                    icon: Icons.edit_outlined,
                    tooltip: '编辑',
                    onPressed: () => _editNote(note, noteProvider),
                  ),
                  _buildMiniButton(
                    icon: Icons.delete_outline,
                    tooltip: '删除',
                    onPressed: () => _confirmDeleteNote(note, noteProvider),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              if (note.type == NoteType.photo && note.imagePath != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: GestureDetector(
                    onTap: () => _showFullScreenImage(context, note.imagePath!, heroTag: 'note_image_${note.id}'),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(4),
                      child: Hero(
                        tag: 'note_image_${note.id}',
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
                    ),
                  ),
                ),
              _buildMarkdownPreview(note.content, note.type == NoteType.photo ? 2 : 3),
              if (note.pdfPage != null && 
                  pdfProvider.isDocumentLoaded && 
                  note.pdfPath == pdfProvider.filePath)
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    onPressed: () {
                      if (pdfProvider.controller.isReady) {
                        pdfProvider.goToPage(note.pdfPage!);
                      } else {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('PDF正在加载中，请稍后再试'),
                            duration: Duration(seconds: 1),
                          ),
                        );
                      }
                    },
                    icon: const Icon(Icons.open_in_new, size: 14),
                    label: const Text('跳转', style: TextStyle(fontSize: 11)),
                    style: TextButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 8),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildQuestionsTab() {
    return Consumer<QuestionProvider>(
      builder: (context, questionProvider, _) {
        if (questionProvider.questions.isEmpty) {
          return Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  Icons.question_answer_outlined,
                  size: 40,
                  color: Theme.of(context).colorScheme.outline,
                ),
                const SizedBox(height: 12),
                Text(
                  '暂无问题',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 16),
                OutlinedButton.icon(
                  onPressed: () => _showAddQuestionDialog(questionProvider),
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('添加问题'),
                ),
              ],
            ),
          );
        }

        return Column(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton.icon(
                    onPressed: () => _showAddQuestionDialog(questionProvider),
                    icon: const Icon(Icons.add, size: 16),
                    label: const Text('添加问题', style: TextStyle(fontSize: 12)),
                  ),
                ],
              ),
            ),
            Expanded(
              child: ListView.builder(
                padding: const EdgeInsets.all(8),
                itemCount: questionProvider.questions.length,
                itemBuilder: (context, index) {
                  final question = questionProvider.questions[index];
                  return _buildQuestionCard(question, questionProvider);
                },
              ),
            ),
          ],
        );
      },
    );
  }

  Future<void> _showAddQuestionDialog(QuestionProvider questionProvider) async {
    final controller = TextEditingController();
    final pdfProvider = context.read<PdfProvider>();

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('添加问题'),
        content: TextField(
          controller: controller,
          maxLines: 4,
          decoration: const InputDecoration(
            hintText: '请输入您的问题...',
            border: OutlineInputBorder(),
          ),
          autofocus: true,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('添加'),
          ),
        ],
      ),
    );

    if (confirmed == true && controller.text.isNotEmpty) {
      String? pdfContext;
      if (pdfProvider.isDocumentLoaded) {
        pdfContext = await pdfProvider.getSurroundingPagesText(range: 1);
      }
      await questionProvider.createQuestion(
        controller.text,
        context: pdfContext,
      );
    }

    controller.dispose();
  }

  Widget _buildQuestionCard(Question question, QuestionProvider provider) {
    return Card(
      key: ValueKey(question.id),
      margin: const EdgeInsets.only(bottom: 6),
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: _getStatusColor(question.status).withValues(alpha: 0.1),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    _getStatusName(question.status),
                    style: TextStyle(
                      fontSize: 10,
                      color: _getStatusColor(question.status),
                    ),
                  ),
                ),
                const Spacer(),
                _buildMiniButton(
                  icon: Icons.refresh,
                  tooltip: '重新生成',
                  onPressed: () => provider.regenerateAnswer(question.id),
                ),
                _buildMiniButton(
                  icon: Icons.note_add,
                  tooltip: '保存为笔记',
                  onPressed: () => _saveQuestionAsNote(question),
                ),
                _buildMiniButton(
                  icon: Icons.delete_outline,
                  tooltip: '删除',
                  onPressed: () => _confirmDeleteQuestion(question, provider),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              question.content,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                fontWeight: FontWeight.w600,
              ),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
            if (question.answer != null) ...[
              const SizedBox(height: 4),
              Container(
                padding: const EdgeInsets.all(6),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: MarkdownBody(
                  data: question.answer!,
                  styleSheet: MarkdownStyleSheet(
                    p: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
              ),
            ] else if (provider.isGenerating &&
                provider.currentQuestion?.id == question.id)
              const Padding(
                padding: EdgeInsets.all(8),
                child: Center(
                  child: SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildControlBar() {
    return Consumer<ASRProvider>(
      builder: (context, asr, _) {
        return Container(
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surface,
            border: Border(
              top: BorderSide(
                color: Theme.of(context).colorScheme.outlineVariant,
              ),
            ),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (_isOrganizing)
                Container(
                  margin: const EdgeInsets.only(bottom: 8),
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.primaryContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Row(
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
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          'AI正在整理笔记...',
                          style: TextStyle(
                            fontSize: 12,
                            color: Theme.of(context).colorScheme.onPrimaryContainer,
                          ),
                        ),
                      ),
                    ],
                  ),
                ).animate().fadeIn().slideY(begin: -0.5),
              Row(
                children: [
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: asr.isLoading
                          ? null
                          : (asr.isConnected
                              ? () => _toggleRecording(asr)
                              : () => _connectASR(asr)),
                      icon: asr.isLoading
                          ? Container(
                              width: 18,
                              height: 18,
                              padding: const EdgeInsets.all(2),
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                valueColor: AlwaysStoppedAnimation<Color>(
                                  Theme.of(context).colorScheme.onSurface,
                                ),
                              ),
                            )
                          : Icon(
                              asr.isRecording ? Icons.stop : Icons.mic,
                              size: 18,
                            ),
                      label: Text(
                        asr.isLoading
                            ? '加载中...'
                            : (asr.isConnected
                                ? (asr.isRecording ? '停止录音' : '开始录音')
                                : '连接ASR'),
                        style: const TextStyle(fontSize: 12),
                      ),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: asr.isRecording
                            ? Theme.of(context).colorScheme.error
                            : null,
                        foregroundColor: asr.isRecording
                            ? Theme.of(context).colorScheme.onError
                            : null,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  if (Platform.isAndroid)
                    Tooltip(
                      message: asr.backgroundMode ? '后台录音已开启' : '后台录音已关闭',
                      child: InkWell(
                        onTap: () => asr.setBackgroundMode(!asr.backgroundMode),
                        borderRadius: BorderRadius.circular(8),
                        child: Container(
                          padding: const EdgeInsets.all(8),
                          decoration: BoxDecoration(
                            color: asr.backgroundMode
                                ? Theme.of(context).colorScheme.primaryContainer
                                : Theme.of(context).colorScheme.surfaceContainerHighest,
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: Icon(
                            asr.backgroundMode ? Icons.music_note : Icons.music_note_outlined,
                            size: 20,
                            color: asr.backgroundMode
                                ? Theme.of(context).colorScheme.onPrimaryContainer
                                : Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    ),
                  const SizedBox(width: 8),
                  AnimatedSwitcher(
                    duration: const Duration(milliseconds: 200),
                    child: _isOrganizing
                        ? Container(
                            key: const ValueKey('loading'),
                            padding: const EdgeInsets.all(8),
                            child: const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                          )
                        : IconButton(
                            key: const ValueKey('normal'),
                            icon: const Icon(Icons.auto_awesome, size: 20),
                            onPressed: _organizeNotesWithLLM,
                            tooltip: 'AI整理笔记',
                          ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.add, size: 20),
                    onPressed: _createManualNote,
                    tooltip: '手动添加笔记',
                  ),
                  IconButton(
                    icon: const Icon(Icons.camera_alt, size: 20),
                    onPressed: _takePhotoNote,
                    tooltip: '拍照笔记',
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }

  Future<void> _editAsrResult(ASRResult result, ASRProvider asr) async {
    final controller = TextEditingController(text: result.text);
    
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('编辑识别内容'),
        content: TextField(
          controller: controller,
          maxLines: 5,
          decoration: const InputDecoration(
            border: OutlineInputBorder(),
            contentPadding: EdgeInsets.all(8),
          ),
          autofocus: true,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('保存'),
          ),
        ],
      ),
    );

    if (confirmed == true && controller.text.isNotEmpty) {
      asr.editResultByTimestamp(result.timestamp, controller.text);
    }

    controller.dispose();
  }

  Future<void> _deleteAsrResult(ASRResult result, ASRProvider asr) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认删除'),
        content: const Text('确定要删除这条识别记录吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            child: const Text('删除'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      asr.deleteResultByTimestamp(result.timestamp);
    }
  }

  Future<void> _handleAsrExport(String action, ASRProvider asr) async {
    String? path;
    
    switch (action) {
      case 'export_text':
        path = await asr.exportToText();
        if (path != null && mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('已导出到: $path')),
          );
        }
        break;
      case 'export_json':
        path = await asr.exportToJson();
        if (path != null && mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('已导出到: $path')),
          );
        }
        break;
      case 'share_text':
        await asr.shareAsText();
        break;
      case 'share_json':
        await asr.shareAsJson();
        break;
    }
    
    if (path == null && (action == 'export_text' || action == 'export_json') && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('导出失败')),
      );
    }
  }

  Future<void> _clearAllAsrResults(ASRProvider asr) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认清空'),
        content: const Text('确定要清空所有识别记录吗？此操作不可恢复。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            child: const Text('清空'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      asr.clearResults();
    }
  }

  Future<void> _connectASR(ASRProvider asr) async {
    try {
      await asr.connect(localeId: 'zh_CN');
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('ASR初始化失败: $e')),
        );
      }
    }
  }

  Future<void> _toggleRecording(ASRProvider asr) async {
    if (asr.isRecording) {
      await asr.stopRecording();
    } else {
      await asr.startRecording();
    }
  }

  void _saveAsrResultAsNote(ASRResult result, PdfProvider pdfProvider) {
    context.read<NoteProvider>().createNote(
      content: result.text,
      type: NoteType.asr,
      pdfPath: pdfProvider.filePath,
      pdfPage: pdfProvider.isDocumentLoaded ? pdfProvider.currentPage : null,
    );

    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('已保存为笔记'),
        duration: Duration(seconds: 1),
      ),
    );
  }

  Future<void> _optimizeAsrResult(ASRResult result, PdfProvider pdfProvider) async {
    final questionProvider = context.read<QuestionProvider>();
    
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: const Row(
          children: [
            SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            SizedBox(width: 12),
            Text('AI优化中...'),
          ],
        ),
        content: Consumer<QuestionProvider>(
          builder: (context, qp, _) {
            if (qp.llmService.isModelLoading) {
              return const Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('正在加载LLM模型...'),
                  SizedBox(height: 8),
                  LinearProgressIndicator(),
                ],
              );
            }
            return const Text('正在使用AI优化语音识别内容...');
          },
        ),
      ),
    );

    try {
      String? pdfContext;
      if (pdfProvider.isDocumentLoaded) {
        pdfContext = await pdfProvider.getSurroundingPagesText(range: 1);
      }
      
      String optimizedText;
      if (pdfContext != null && pdfContext.isNotEmpty) {
        optimizedText = await questionProvider.llmService.generateAnswer(
          result.text,
          context: '当前PDF内容：\n$pdfContext',
          systemPrompt: '你是一个课堂助手。请根据PDF内容和语音识别文本，优化语音识别结果。'
              '修正错别字、添加标点、整理语句。保持原意，结合PDF上下文使内容更准确。只输出优化后的文本。',
        );
      } else {
        optimizedText = await questionProvider.llmService.optimizeAsrText(result.text);
      }
      
      if (mounted) {
        Navigator.pop(context);
        
        final controller = TextEditingController(text: optimizedText);
        
        final shouldSave = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('AI优化结果'),
            content: SizedBox(
              width: double.maxFinite,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '原文：',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: Theme.of(context).colorScheme.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      result.text,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    '优化后：',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.primary,
                    ),
                  ),
                  const SizedBox(height: 4),
                  TextField(
                    controller: controller,
                    maxLines: 5,
                    decoration: const InputDecoration(
                      border: OutlineInputBorder(),
                      contentPadding: EdgeInsets.all(8),
                    ),
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('取消'),
              ),
              TextButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('保存为笔记'),
              ),
            ],
          ),
        );

        if (shouldSave == true && mounted) {
          await context.read<NoteProvider>().createNote(
            content: controller.text,
            type: NoteType.asr,
            pdfPath: pdfProvider.filePath,
            pdfPage: pdfProvider.isDocumentLoaded ? pdfProvider.currentPage : null,
          );
          
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('已保存优化后的笔记')),
            );
          }
        }

        controller.dispose();
      }
    } catch (e) {
      if (mounted) {
        Navigator.pop(context);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('优化失败: $e')),
        );
      }
    }
  }

  void _saveQuestionAsNote(Question question) {
    final content = question.answer != null
        ? 'Q: ${question.content}\n\nA: ${question.answer}'
        : question.content;

    final pdfProvider = context.read<PdfProvider>();
    context.read<NoteProvider>().createNote(
      content: content,
      type: NoteType.keypoint,
      pdfPath: pdfProvider.filePath,
      pdfPage: pdfProvider.isDocumentLoaded ? pdfProvider.currentPage : null,
    );

    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('已保存为笔记'),
        duration: Duration(seconds: 1),
      ),
    );
  }

  Future<void> _createManualNote() async {
    final controller = TextEditingController();
    final pdfProvider = context.read<PdfProvider>();

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('新建笔记'),
        content: TextField(
          controller: controller,
          maxLines: 5,
          decoration: InputDecoration(
            hintText: '输入笔记内容...',
            border: const OutlineInputBorder(),
            suffixText: pdfProvider.isDocumentLoaded
                ? 'P${pdfProvider.currentPage}'
                : null,
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('创建'),
          ),
        ],
      ),
    );

    if (confirmed == true && controller.text.isNotEmpty && mounted) {
      await context.read<NoteProvider>().createNote(
        content: controller.text,
        type: NoteType.manual,
        pdfPath: pdfProvider.filePath,
        pdfPage: pdfProvider.isDocumentLoaded ? pdfProvider.currentPage : null,
      );
    }

    controller.dispose();
  }

  Future<void> _takePhotoNote() async {
    final ImagePicker picker = ImagePicker();
    final pdfProvider = context.read<PdfProvider>();
    
    try {
      final source = await showDialog<ImageSource>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('选择图片来源'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (defaultTargetPlatform == TargetPlatform.android ||
                  defaultTargetPlatform == TargetPlatform.iOS)
                ListTile(
                  leading: const Icon(Icons.camera_alt),
                  title: const Text('相机'),
                  onTap: () => Navigator.pop(context, ImageSource.camera),
                ),
              ListTile(
                leading: const Icon(Icons.photo_library),
                title: const Text('相册'),
                onTap: () => Navigator.pop(context, ImageSource.gallery),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('取消'),
            ),
          ],
        ),
      );

      if (source == null) return;

      final photo = await picker.pickImage(
        source: source,
        maxWidth: 1920,
        maxHeight: 1080,
        imageQuality: 85,
      );
      
      if (photo == null) return;
      
      if (!mounted) return;
      
      final controller = TextEditingController();
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => StatefulBuilder(
          builder: (context, setDialogState) => AlertDialog(
            title: const Text('拍照笔记'),
            content: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  GestureDetector(
                    onTap: () => _showFullScreenImage(context, photo.path),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(8),
                      child: Image.file(
                        File(photo.path),
                        height: 200,
                        width: 300,
                        fit: BoxFit.cover,
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: controller,
                    maxLines: 3,
                    decoration: const InputDecoration(
                      hintText: '添加备注（可选）...',
                      border: OutlineInputBorder(),
                    ),
                  ),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('取消'),
              ),
              TextButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('保存'),
              ),
            ],
          ),
        ),
      );

      if (confirmed == true && mounted) {
        final appDir = await getApplicationSupportDirectory();
        final photosDir = Directory('${appDir.path}/photos');
        if (!await photosDir.exists()) {
          await photosDir.create(recursive: true);
        }
        
        final timestamp = DateTime.now().millisecondsSinceEpoch;
        final ext = path.extension(photo.path);
        final savedPath = '${photosDir.path}/photo_$timestamp$ext';
        
        await File(photo.path).copy(savedPath);
        
        await context.read<NoteProvider>().createNote(
          content: controller.text.isEmpty ? '照片笔记' : controller.text,
          type: NoteType.photo,
          pdfPath: pdfProvider.filePath,
          pdfPage: pdfProvider.isDocumentLoaded ? pdfProvider.currentPage : null,
          imagePath: savedPath,
        );
        
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('照片笔记已保存')),
          );
        }
      }

      controller.dispose();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('拍照失败: $e')),
        );
      }
    }
  }

  Future<void> _organizeNotesWithLLM() async {
    final asr = context.read<ASRProvider>();
    final noteProvider = context.read<NoteProvider>();
    final questionProvider = context.read<QuestionProvider>();
    final pdfProvider = context.read<PdfProvider>();

    if (asr.results.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('暂无ASR识别内容，请先录音')),
      );
      return;
    }

    setState(() => _isOrganizing = true);

    try {
      final llmService = questionProvider.llmService;

      final asrTexts = asr.results.map((r) => r.text).toList();
      final asrContent = asrTexts.join('\n');

      String? pdfContext;
      if (pdfProvider.isDocumentLoaded) {
        pdfContext = await pdfProvider.getSurroundingPagesText(range: 2);
      }

      String organized;
      if (pdfContext != null && pdfContext.isNotEmpty) {
        organized = await llmService.generateAnswer(
          asrContent,
          context: '当前PDF内容：\n$pdfContext',
          systemPrompt: '你是一个专业的课堂笔记整理助手。请结合PDF内容和课堂语音识别内容，整理成结构化的笔记，'
              '包括：1. 主要知识点 2. 重点内容 3. 关键概念解释。'
              '请用简洁的中文输出，使用Markdown格式。结合PDF上下文使笔记更准确完整。',
        );
      } else {
        organized = await llmService.generateAnswer(
          asrContent,
          systemPrompt: '你是一个专业的课堂笔记整理助手。请将以下课堂语音识别内容整理成结构化的笔记，'
              '包括：1. 主要知识点 2. 重点内容 3. 关键概念解释。'
              '请用简洁的中文输出，使用Markdown格式。',
        );
      }

      await noteProvider.createNote(
        content: organized,
        type: NoteType.summary,
        pdfPath: pdfProvider.filePath,
        pdfPage: pdfProvider.isDocumentLoaded ? pdfProvider.currentPage : null,
        tags: ['AI整理', '课堂笔记'],
      );

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('笔记整理完成')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('整理失败: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _isOrganizing = false);
      }
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
        return '语音';
      case NoteType.manual:
        return '手动';
      case NoteType.summary:
        return '摘要';
      case NoteType.keypoint:
        return '重点';
      case NoteType.photo:
        return '照片';
    }
  }

  Color _getStatusColor(QuestionStatus status) {
    switch (status) {
      case QuestionStatus.pending:
        return Colors.orange;
      case QuestionStatus.answering:
        return Colors.blue;
      case QuestionStatus.answered:
        return Colors.green;
      case QuestionStatus.failed:
        return Colors.red;
    }
  }

  String _getStatusName(QuestionStatus status) {
    switch (status) {
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

  Future<void> _editNote(Note note, NoteProvider noteProvider) async {
    final controller = TextEditingController(text: note.content);

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('编辑笔记'),
        content: TextField(
          controller: controller,
          maxLines: 5,
          decoration: const InputDecoration(
            hintText: '输入笔记内容...',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('保存'),
          ),
        ],
      ),
    );

    if (confirmed == true && controller.text.isNotEmpty) {
      final updatedNote = note.copyWith(
        content: controller.text,
        updatedAt: DateTime.now(),
      );
      await noteProvider.updateNote(updatedNote);
    }

    controller.dispose();
  }

  Future<void> _confirmDeleteNote(Note note, NoteProvider noteProvider) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除笔记'),
        content: const Text('确定要删除这条笔记吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            child: const Text('删除'),
          ),
        ],
      ),
    );

    if (confirmed == true && mounted) {
      try {
        await noteProvider.deleteNote(note.id);
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('笔记已删除'),
              duration: Duration(seconds: 1),
            ),
          );
        }
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('删除失败: $e')),
          );
        }
      }
    }
  }

  void _showNotePreview(Note note, NoteProvider noteProvider, PdfProvider pdfProvider) {
    NotePreviewDialog.show(
      context,
      note: note,
      onEdit: () => _editNote(note, noteProvider),
      onDelete: () => _confirmDeleteNote(note, noteProvider),
      onShowFullScreenImage: note.imagePath != null
          ? () => _showFullScreenImage(context, note.imagePath!, heroTag: 'note_preview_image_${note.id}')
          : null,
    );
  }

  Future<void> _confirmDeleteQuestion(Question question, QuestionProvider provider) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除问题'),
        content: const Text('确定要删除这个问题吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            child: const Text('删除'),
          ),
        ],
      ),
    );

    if (confirmed == true && mounted) {
      try {
        await provider.deleteQuestion(question.id);
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('问题已删除'),
              duration: Duration(seconds: 1),
            ),
          );
        }
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('删除失败: $e')),
          );
        }
      }
    }
  }

  Widget _buildMarkdownPreview(String content, int maxLines) {
    final lines = content.split('\n');
    final previewLines = lines.take(maxLines * 2).join('\n');
    final isTruncated = lines.length > maxLines * 2;
    
    return MarkdownBody(
      data: isTruncated ? '$previewLines...' : previewLines,
      shrinkWrap: true,
      softLineBreak: true,
      selectable: true,
      extensionSet: md.ExtensionSet.gitHubWeb,
      styleSheet: MarkdownStyleSheet(
        p: Theme.of(context).textTheme.bodySmall,
        h1: Theme.of(context).textTheme.titleMedium?.copyWith(
          fontWeight: FontWeight.bold,
        ),
        h2: Theme.of(context).textTheme.titleSmall?.copyWith(
          fontWeight: FontWeight.bold,
        ),
        h3: Theme.of(context).textTheme.bodyMedium?.copyWith(
          fontWeight: FontWeight.bold,
        ),
        h4: Theme.of(context).textTheme.bodySmall?.copyWith(
          fontWeight: FontWeight.bold,
        ),
        listBullet: Theme.of(context).textTheme.bodySmall,
        code: Theme.of(context).textTheme.bodySmall?.copyWith(
          fontFamily: 'monospace',
          backgroundColor: Theme.of(context).colorScheme.surfaceContainerHighest,
        ),
        codeblockDecoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(8),
        ),
        codeblockPadding: const EdgeInsets.all(12),
        blockquote: Theme.of(context).textTheme.bodySmall?.copyWith(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          fontStyle: FontStyle.italic,
        ),
        blockquoteDecoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(4),
          border: Border(
            left: BorderSide(
              color: Theme.of(context).colorScheme.primary,
              width: 4,
            ),
          ),
        ),
        blockquotePadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        tableHead: Theme.of(context).textTheme.bodySmall?.copyWith(
          fontWeight: FontWeight.bold,
        ),
        tableBody: Theme.of(context).textTheme.bodySmall,
        tableBorder: TableBorder.all(
          color: Theme.of(context).colorScheme.outlineVariant,
          width: 1,
        ),
        tableCellsPadding: const EdgeInsets.all(8),
        horizontalRuleDecoration: BoxDecoration(
          border: Border(
            top: BorderSide(
              color: Theme.of(context).colorScheme.outlineVariant,
              width: 1,
            ),
          ),
        ),
      ),
      onTapLink: (text, href, title) {
        if (href != null) {
          // 可以在这里处理链接点击
        }
      },
      imageBuilder: (uri, title, alt) {
        return ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: Image.network(
            uri.toString(),
            errorBuilder: (context, error, stackTrace) {
              return Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.broken_image, size: 16),
                    SizedBox(width: 8),
                    Text(alt ?? '图片加载失败', style: Theme.of(context).textTheme.bodySmall),
                  ],
                ),
              );
            },
          ),
        );
      },
    );
  }
}
