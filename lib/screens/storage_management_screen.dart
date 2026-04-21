import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:hive/hive.dart';
import '../providers/pdf_provider.dart';
import '../providers/note_provider.dart';
import '../providers/question_provider.dart';
import '../providers/stroke_provider.dart';

class StorageManagementScreen extends StatefulWidget {
  const StorageManagementScreen({super.key});

  @override
  State<StorageManagementScreen> createState() => _StorageManagementScreenState();
}

class _StorageManagementScreenState extends State<StorageManagementScreen> {
  final Set<String> _selectedCategories = {};
  bool _isSelectAll = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('存储管理'),
        actions: [
          if (_selectedCategories.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.delete),
              onPressed: () => _deleteSelected(),
              tooltip: '删除选中',
            ),
        ],
      ),
      body: Consumer4<PdfProvider, NoteProvider, QuestionProvider, StrokeProvider>(
        builder: (context, pdfProvider, noteProvider, questionProvider, strokeProvider, child) {
          final allCategories = _getAllCategories(
            pdfProvider,
            noteProvider,
            questionProvider,
            strokeProvider,
          );

          if (allCategories.isEmpty) {
            return const Center(
              child: Text('暂无存储数据'),
            );
          }

          return Column(
            children: [
              _buildSummaryCard(noteProvider, questionProvider, strokeProvider),
              _buildSelectAllRow(allCategories),
              Expanded(
                child: ListView.builder(
                  itemCount: allCategories.length,
                  itemBuilder: (context, index) {
                    final category = allCategories.elementAt(index);
                    return _buildCategoryTile(
                      category,
                      pdfProvider,
                      noteProvider,
                      questionProvider,
                      strokeProvider,
                    );
                  },
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _buildSummaryCard(
    NoteProvider noteProvider,
    QuestionProvider questionProvider,
    StrokeProvider strokeProvider,
  ) {
    return Card(
      margin: const EdgeInsets.all(16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '存储概览',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: _buildSummaryItem(
                    '笔记',
                    noteProvider.getNoteCount(),
                    Icons.note,
                    Colors.blue,
                  ),
                ),
                Expanded(
                  child: _buildSummaryItem(
                    '问题',
                    questionProvider.questions.length,
                    Icons.question_answer,
                    Colors.green,
                  ),
                ),
                Expanded(
                  child: _buildSummaryItem(
                    '笔画',
                    strokeProvider.strokes.length,
                    Icons.draw,
                    Colors.orange,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSummaryItem(String label, int count, IconData icon, Color color) {
    return Column(
      children: [
        Icon(icon, color: color, size: 28),
        const SizedBox(height: 4),
        Text(
          count.toString(),
          style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.bold,
              ),
        ),
        Text(
          label,
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ],
    );
  }

  Widget _buildSelectAllRow(Set<String> allCategories) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          Checkbox(
            value: _isSelectAll,
            onChanged: (value) {
              setState(() {
                _isSelectAll = value ?? false;
                if (_isSelectAll) {
                  _selectedCategories.addAll(allCategories);
                } else {
                  _selectedCategories.clear();
                }
              });
            },
          ),
          const Text('全选'),
          const Spacer(),
          TextButton.icon(
            onPressed: () => _showClearAllDialog(),
            icon: const Icon(Icons.delete_forever),
            label: const Text('清空所有数据'),
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildCategoryTile(
    String category,
    PdfProvider pdfProvider,
    NoteProvider noteProvider,
    QuestionProvider questionProvider,
    StrokeProvider strokeProvider,
  ) {
    final noteCount = noteProvider.getNoteCountByCategory(category);
    final questionCount = questionProvider.getQuestionCountByCategory(category);
    final strokeCount = strokeProvider.getStrokeCountByCategory(category);
    final bookmarkCount = pdfProvider.getBookmarkCountByCategory(category);
    final totalCount = noteCount + questionCount + strokeCount + bookmarkCount;

    return ListTile(
      leading: Checkbox(
        value: _selectedCategories.contains(category),
        onChanged: (value) {
          setState(() {
            if (value == true) {
              _selectedCategories.add(category);
            } else {
              _selectedCategories.remove(category);
            }
            _isSelectAll = _selectedCategories.length == _getAllCategories(
              pdfProvider,
              noteProvider,
              questionProvider,
              strokeProvider,
            ).length;
          });
        },
      ),
      title: Text(
        category,
        style: const TextStyle(fontWeight: FontWeight.w500),
      ),
      subtitle: Text(
        '笔记: $noteCount | 问题: $questionCount | 书签: $bookmarkCount | 笔画: $strokeCount',
        style: Theme.of(context).textTheme.bodySmall,
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.primaryContainer,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Text(
              '共 $totalCount 项',
              style: TextStyle(
                fontSize: 12,
                color: Theme.of(context).colorScheme.onPrimaryContainer,
              ),
            ),
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline),
            onPressed: () => _deleteCategory(category),
            tooltip: '删除此分类数据',
          ),
        ],
      ),
    );
  }

  Set<String> _getAllCategories(
    PdfProvider pdfProvider,
    NoteProvider noteProvider,
    QuestionProvider questionProvider,
    StrokeProvider strokeProvider,
  ) {
    final categories = <String>{};
    categories.addAll(pdfProvider.categories);
    categories.addAll(noteProvider.categories);
    categories.addAll(questionProvider.categories);
    categories.addAll(strokeProvider.categories);
    return categories;
  }

  void _deleteSelected() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认删除'),
        content: Text('确定要删除选中的 ${_selectedCategories.length} 个分类的所有数据吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () async {
              Navigator.pop(context);
              final messenger = ScaffoldMessenger.of(context);
              int deletedCount = 0;
              int failedCount = 0;

              try {
                for (final category in _selectedCategories) {
                  final success = await _deleteCategoryData(category);
                  if (success) {
                    deletedCount++;
                  } else {
                    failedCount++;
                  }
                }
                setState(() {
                  _selectedCategories.clear();
                  _isSelectAll = false;
                });
                if (mounted) {
                  messenger.showSnackBar(
                    SnackBar(
                      content: Text(failedCount == 0
                          ? '已删除 $deletedCount 个分类的数据'
                          : '已删除 $deletedCount 个分类，$failedCount 个失败'),
                      duration: const Duration(seconds: 2),
                    ),
                  );
                }
              } catch (e) {
                if (mounted) {
                  messenger.showSnackBar(
                    SnackBar(content: Text('删除失败: $e')),
                  );
                }
              }
            },
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            child: const Text('删除'),
          ),
        ],
      ),
    );
  }

  void _deleteCategory(String category) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认删除'),
        content: Text('确定要删除分类 "$category" 的所有数据吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () async {
              Navigator.pop(context);
              final messenger = ScaffoldMessenger.of(context);

              try {
                final success = await _deleteCategoryData(category);
                if (mounted) {
                  messenger.showSnackBar(
                    SnackBar(
                      content: Text(success
                          ? '已删除分类 "$category" 的数据'
                          : '分类 "$category" 不存在或已删除'),
                      duration: const Duration(seconds: 2),
                    ),
                  );
                }
              } catch (e) {
                if (mounted) {
                  messenger.showSnackBar(
                    SnackBar(content: Text('删除失败: $e')),
                  );
                }
              }
            },
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            child: const Text('删除'),
          ),
        ],
      ),
    );
  }

  Future<bool> _deleteCategoryData(String category) async {
    final pdfProvider = context.read<PdfProvider>();
    final noteProvider = context.read<NoteProvider>();
    final questionProvider = context.read<QuestionProvider>();
    final strokeProvider = context.read<StrokeProvider>();

    final results = await Future.wait([
      pdfProvider.deleteCategory(category),
      noteProvider.deleteCategory(category),
      questionProvider.deleteCategory(category),
      strokeProvider.deleteCategory(category),
    ]);

    return results.any((success) => success);
  }

  void _showClearAllDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('清空所有数据'),
        content: const Text('确定要清空所有存储数据吗？此操作不可恢复！'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () async {
              Navigator.pop(context);
              final messenger = ScaffoldMessenger.of(context);
              try {
                final pdfProvider = context.read<PdfProvider>();
                final noteProvider = context.read<NoteProvider>();
                final questionProvider = context.read<QuestionProvider>();
                final strokeProvider = context.read<StrokeProvider>();

                if (pdfProvider.isDocumentLoaded) {
                  await pdfProvider.closePdf();
                }

                await Hive.box('settings').put('pdf_bookmarks', <Map<String, dynamic>>[]);
                await Hive.box('settings').put('pdf_strokes', <Map<String, dynamic>>[]);

                noteProvider.clearAllNotes();
                questionProvider.clearAllQuestions();
                strokeProvider.clearAllStrokes();

                await pdfProvider.deleteCategory('default');
                await noteProvider.deleteCategory('default');
                await questionProvider.deleteCategory('default');
                await strokeProvider.deleteCategory('default');

                await pdfProvider.createCategory('default');
                await noteProvider.createCategory('default');
                await questionProvider.createCategory('default');
                await strokeProvider.createCategory('default');

                setState(() {
                  _selectedCategories.clear();
                  _isSelectAll = false;
                });

                if (mounted) {
                  messenger.showSnackBar(
                    const SnackBar(
                      content: Text('已清空所有数据'),
                      duration: Duration(seconds: 1),
                    ),
                  );
                }
              } catch (e) {
                if (mounted) {
                  messenger.showSnackBar(
                    SnackBar(content: Text('清空失败: $e')),
                  );
                }
              }
            },
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            child: const Text('清空'),
          ),
        ],
      ),
    );
  }
}
