import 'package:flutter/material.dart';
import 'package:hive_flutter/hive_flutter.dart';
import 'package:path_provider/path_provider.dart';
import 'app.dart';
import 'models/note.dart';
import 'models/question.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final appDir = await getApplicationSupportDirectory();
  Hive.init(appDir.path);

  Hive.registerAdapter(NoteAdapter());
  Hive.registerAdapter(NoteTypeAdapter());
  Hive.registerAdapter(QuestionAdapter());
  Hive.registerAdapter(QuestionTypeAdapter());
  Hive.registerAdapter(QuestionStatusAdapter());

  await Hive.openBox<Note>('notes');
  await Hive.openBox<Question>('questions');
  await Hive.openBox('settings');

  runApp(const ClassHelperApp());
}
