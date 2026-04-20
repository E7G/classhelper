import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_animate/flutter_animate.dart';
import '../providers/note_provider.dart';
import '../models/note.dart';
import '../widgets/note_card.dart';
import '../widgets/note_preview_dialog.dart';

class NotesScreen extends StatefulWidget {
  const NotesScreen({super.key});

  @override
  State<NotesScreen> createState() => _NotesScreenState();
}

class _NotesScreenState extends State<NotesScreen> {
  final TextEditingController _searchController = TextEditingController();

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('笔记管理'),
        actions: [
          PopupMenuButton<NoteType?>(
            icon: const Icon(Icons.filter_list),
            onSelected: (type) {
              context.read<NoteProvider>().setFilterType(type);
            },
            itemBuilder: (context) => [
              const PopupMenuItem(
                value: null,
                child: Text('全部'),
              ),
              ...NoteType.values.map((type) => PopupMenuItem(
                value: type,
                child: Text(_getNoteTypeName(type)),
              )),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              controller: _searchController,
              decoration: InputDecoration(
                hintText: '搜索笔记...',
                prefixIcon: const Icon(Icons.search),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
                filled: true,
              ),
              onChanged: (value) {
                context.read<NoteProvider>().setSearchQuery(value);
              },
            ),
          ),
          Expanded(
            child: Consumer<NoteProvider>(
              builder: (context, noteProvider, _) {
                if (noteProvider.notes.isEmpty) {
                  return Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          Icons.note_alt_outlined,
                          size: 64,
                          color: Theme.of(context).colorScheme.outline,
                        ),
                        const SizedBox(height: 16),
                        Text(
                          '暂无笔记',
                          style: Theme.of(context).textTheme.bodyLarge,
                        ),
                      ],
                    ),
                  ).animate().fadeIn();
                }

                return ListView.builder(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  itemCount: noteProvider.notes.length,
                  itemBuilder: (context, index) {
                    final note = noteProvider.notes[index];
                    return NoteCard(
                      note: note,
                      onTap: () => _showNotePreview(note),
                      onDelete: () => _deleteNote(noteProvider, note.id),
                      onEdit: () => _editNote(note),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () => _createNote(),
        child: const Icon(Icons.add),
      ),
    );
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

  Future<void> _createNote() async {
    final controller = TextEditingController();
    
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('新建笔记'),
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
            child: const Text('创建'),
          ),
        ],
      ),
    );

    if (confirmed == true && controller.text.isNotEmpty) {
      await context.read<NoteProvider>().createNote(
        content: controller.text,
        type: NoteType.manual,
      );
    }
    
    controller.dispose();
  }

  Future<void> _editNote(Note note) async {
    final controller = TextEditingController(text: note.content);
    
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('编辑笔记'),
        content: TextField(
          controller: controller,
          maxLines: 5,
          decoration: const InputDecoration(
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

    if (confirmed == true) {
      final updatedNote = note.copyWith(content: controller.text);
      await context.read<NoteProvider>().updateNote(updatedNote);
    }
    
    controller.dispose();
  }

  Future<void> _deleteNote(NoteProvider provider, String id) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认删除'),
        content: const Text('确定要删除这条笔记吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );

    if (confirmed == true && mounted) {
      try {
        await provider.deleteNote(id);
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

  void _showNotePreview(Note note) {
    NotePreviewDialog.show(
      context,
      note: note,
      onEdit: () => _editNote(note),
      onDelete: () => _deleteNote(context.read<NoteProvider>(), note.id),
    );
  }
}
