import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:google_fonts/google_fonts.dart';
import 'providers/asr_provider.dart';
import 'providers/question_provider.dart';
import 'providers/note_provider.dart';
import 'providers/pdf_provider.dart';
import 'providers/stroke_provider.dart';
import 'screens/pdf_reader_screen.dart';

class ClassHelperApp extends StatefulWidget {
  const ClassHelperApp({super.key});

  @override
  State<ClassHelperApp> createState() => _ClassHelperAppState();
}

class _ClassHelperAppState extends State<ClassHelperApp> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    
    final questionProvider = context.read<QuestionProvider?>();
    final asrProvider = context.read<ASRProvider?>();
    
    switch (state) {
      case AppLifecycleState.paused:
      case AppLifecycleState.inactive:
        questionProvider?.llmService.pauseModel();
        if (asrProvider?.isRecording == true && asrProvider?.backgroundMode != true) {
          asrProvider?.stopRecording();
        }
        break;
      case AppLifecycleState.resumed:
        break;
      default:
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => ASRProvider()),
        ChangeNotifierProvider(create: (_) => QuestionProvider()),
        ChangeNotifierProvider(create: (_) => NoteProvider()),
        ChangeNotifierProvider(create: (_) => PdfProvider()),
        ChangeNotifierProvider(create: (_) => StrokeProvider()),
      ],
      child: MaterialApp(
        title: '智能课堂助手',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          useMaterial3: true,
          colorScheme: ColorScheme.fromSeed(
            seedColor: const Color(0xFF6366F1),
            brightness: Brightness.light,
          ),
          textTheme: GoogleFonts.notoSansScTextTheme(),
          appBarTheme: const AppBarTheme(
            centerTitle: true,
            elevation: 0,
          ),
          cardTheme: CardThemeData(
            elevation: 2,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
            ),
          ),
        ),
        darkTheme: ThemeData(
          useMaterial3: true,
          colorScheme: ColorScheme.fromSeed(
            seedColor: const Color(0xFF6366F1),
            brightness: Brightness.dark,
          ),
          textTheme: GoogleFonts.notoSansScTextTheme(
            ThemeData(brightness: Brightness.dark).textTheme,
          ),
        ),
        themeMode: ThemeMode.system,
        home: const PdfReaderScreen(),
      ),
    );
  }
}
