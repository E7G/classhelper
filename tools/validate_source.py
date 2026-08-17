#!/usr/bin/env python3
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []

xml_files = list(ROOT.glob('app/src/main/res/**/*.xml')) + [ROOT/'app/src/main/AndroidManifest.xml']
for p in xml_files:
    try:
        ET.parse(p)
    except Exception as e:
        errors.append(f'XML parse failed: {p.relative_to(ROOT)}: {e}')

ids = set()
for p in ROOT.glob('app/src/main/res/**/*.xml'):
    ids.update(re.findall(r'@\+id/([A-Za-z0-9_]+)', p.read_text(errors='ignore')))
refs = set()
for p in ROOT.glob('app/src/main/java/**/*.kt'):
    refs.update(re.findall(r'R\.id\.([A-Za-z0-9_]+)', p.read_text(errors='ignore')))
for missing in sorted(refs - ids):
    errors.append(f'Missing R.id definition: {missing}')

layouts = {p.stem for p in ROOT.glob('app/src/main/res/layout/*.xml')}
layout_refs = set()
drawable_refs = set()
for p in ROOT.glob('app/src/main/java/**/*.kt'):
    text = p.read_text(errors='ignore')
    layout_refs.update(re.findall(r'R\.layout\.([A-Za-z0-9_]+)', text))
    drawable_refs.update(re.findall(r'R\.drawable\.([A-Za-z0-9_]+)', text))
for missing in sorted(layout_refs - layouts):
    errors.append(f'Missing R.layout: {missing}')
drawables = {p.stem for p in ROOT.glob('app/src/main/res/drawable/*')}
for missing in sorted(drawable_refs - drawables):
    errors.append(f'Missing R.drawable: {missing}')

manifest = (ROOT/'app/src/main/AndroidManifest.xml').read_text(errors='ignore')
source = '\n'.join(p.read_text(errors='ignore') for p in ROOT.glob('app/src/main/java/**/*.kt'))
for forbidden, label in [
    ('android.permission.WAKE_LOCK', 'explicit WAKE_LOCK permission'),
    ('android.webkit.WebView', 'WebView runtime'),
    ('io.flutter', 'Flutter runtime'),
    ('androidx.work', 'WorkManager periodic/background dependency'),
]:
    haystack = manifest + '\n' + source if 'permission' in label else source
    if forbidden in haystack:
        errors.append(f'Forbidden policy marker found: {label}: {forbidden}')

# Basic source completeness checks for critical features.
critical = {
    'AudioRecord': 'app/src/main/java/io/github/paper/classhelper/audio/AudioCapture.kt',
    'OfflineSenseVoiceModelConfig': 'app/src/main/java/io/github/paper/classhelper/asr/LocalSenseVoiceAsrEngine.kt',
    'model.int8.onnx': 'app/src/main/java/io/github/paper/classhelper/asr/AsrModelManager.kt',
    'tokens.txt': 'app/src/main/java/io/github/paper/classhelper/asr/AsrModelManager.kt',
    'ContextCompat.startForegroundService': 'app/src/main/java/io/github/paper/classhelper/asr/AsrModelManager.kt',
    'RandomAccessFile': 'app/src/main/java/io/github/paper/classhelper/asr/AsrModelManager.kt',
    'FOREGROUND_SERVICE_TYPE_DATA_SYNC': 'app/src/main/java/io/github/paper/classhelper/classroom/AsrModelDownloadService.kt',
    'FOREGROUND_SERVICE_TYPE_MICROPHONE': 'app/src/main/java/io/github/paper/classhelper/classroom/ClassroomService.kt',
    'PDAnnotationMarkup.SUB_TYPE_INK': 'app/src/main/java/io/github/paper/classhelper/pdf/PdfAnnotationWriter.kt',
    'PDAnnotationText': 'app/src/main/java/io/github/paper/classhelper/pdf/PdfAnnotationWriter.kt',
    'TextRecognition.getClient': 'app/src/main/java/io/github/paper/classhelper/knowledge/PdfTextIndexer.kt',
    'OpenMultipleDocuments': 'app/src/main/java/io/github/paper/classhelper/ui/ReaderActivity.kt',
    'SecretStore': 'app/src/main/java/io/github/paper/classhelper/SettingsStore.kt',
}
for marker, rel in critical.items():
    p = ROOT/rel
    if not p.exists() or marker not in p.read_text(errors='ignore'):
        errors.append(f'Critical marker missing: {marker} in {rel}')


# v1.4 reader regression checks: keep the immersive layout and known compatibility fixes intact.
reader_path = ROOT/'app/src/main/java/io/github/paper/classhelper/ui/ReaderActivity.kt'
reader_text = reader_path.read_text(errors='ignore') if reader_path.exists() else ''
plain_path = ROOT/'app/src/main/java/io/github/paper/classhelper/knowledge/PlainTextImporter.kt'
plain_text = plain_path.read_text(errors='ignore') if plain_path.exists() else ''
reader_layout_path = ROOT/'app/src/main/res/layout/activity_reader.xml'
reader_layout = reader_layout_path.read_text(errors='ignore') if reader_layout_path.exists() else ''
# AhmerPdfViewer 2.0.x enables double tap by default; API spelling differs across forks.
# Do not force a version-specific Configurator method here.
for marker in ['setMinZoom(1f)', 'setMidZoom(2f)', 'setMaxZoom(4f)']:
    if marker not in reader_text:
        errors.append(f'ReaderActivity regression: PDF zoom level missing: {marker}')
if 'setSingleLine(true)' not in reader_text:
    errors.append('ReaderActivity regression: programmatic search EditText must use setSingleLine(true)')
if '?.removePrefix("#")?.trim().orEmpty()' not in plain_text:
    errors.append('PlainTextImporter regression: nullable title trim must stay null-safe')
for marker in ['@+id/topChrome', '@+id/bottomChrome', '@+id/sidePanelScroll', '@+id/sidePanelActions']:
    if marker not in reader_layout:
        errors.append(f'Reader UI regression: missing immersive/unified-sidebar marker {marker}')

# v1.6.1 ASR regression checks: SenseVoiceSmall + Silero VAD must stay wired.
asr_manager = (ROOT/'app/src/main/java/io/github/paper/classhelper/asr/AsrModelManager.kt').read_text(errors='ignore')
service_text = (ROOT/'app/src/main/java/io/github/paper/classhelper/classroom/ClassroomService.kt').read_text(errors='ignore')
sense_engine = ROOT/'app/src/main/java/io/github/paper/classhelper/asr/LocalSenseVoiceAsrEngine.kt'
if 'sensevoice-small-int8-2024-07-17' not in asr_manager:
    errors.append('ASR regression: SenseVoiceSmall 2024-07-17 model id missing')
for required in ['model.int8.onnx', 'tokens.txt', 'silero_vad.onnx']:
    if required not in asr_manager:
        errors.append(f'ASR regression: SenseVoice model file missing: {required}')
sense_text = sense_engine.read_text(errors='ignore') if sense_engine.exists() else ''
if not sense_engine.exists() or 'OfflineSenseVoiceModelConfig' not in sense_text:
    errors.append('ASR regression: LocalSenseVoiceAsrEngine missing or not using OfflineSenseVoiceModelConfig')
if 'useInverseTextNormalization = true' not in sense_text:
    errors.append('ASR regression: SenseVoice ITN/punctuation must remain enabled')
if 'SileroVadModelConfig' not in sense_text or 'VadModelConfig' not in sense_text:
    errors.append('ASR regression: SenseVoice engine must use Silero VAD segmentation')
if 'LocalSenseVoiceAsrEngine' not in service_text:
    errors.append('ASR regression: ClassroomService is not wired to SenseVoice engine')
if (ROOT/'app/src/main/java/io/github/paper/classhelper/asr/LocalQwen3AsrEngine.kt').exists():
    errors.append('ASR regression: old LocalQwen3AsrEngine must stay removed')
if (ROOT/'app/src/main/java/io/github/paper/classhelper/asr/LocalParaformerStreamingEngine.kt').exists():
    errors.append('ASR regression: old LocalParaformerStreamingEngine must stay removed')

# v1.5.3 download regression: Android 14+ must use UIDT; older releases keep the FGS fallback.
downloader_service = ROOT/'app/src/main/java/io/github/paper/classhelper/classroom/AsrModelDownloadService.kt'
if 'DownloadManager.Request(' in asr_manager or '.enqueue(request)' in asr_manager:
    errors.append('Download regression: model downloads must not enqueue Android DownloadManager requests')
if not downloader_service.exists():
    errors.append('Download regression: AsrModelDownloadService missing')
else:
    dtext = downloader_service.read_text(errors='ignore')
    for marker in ['FOREGROUND_SERVICE_TYPE_DATA_SYNC', 'START_REDELIVER_INTENT', 'startForeground']:
        if marker not in dtext:
            errors.append(f'Download regression: missing {marker} in foreground downloader')
if 'android.permission.FOREGROUND_SERVICE_DATA_SYNC' not in manifest or 'android:foregroundServiceType="dataSync"' not in manifest:
    errors.append('Download regression: dataSync foreground service permission/type missing')
if 'AsrDownloadReceiver' in manifest:
    errors.append('Download regression: obsolete DownloadManager completion receiver still declared')

uidt = ROOT/'app/src/main/java/io/github/paper/classhelper/classroom/AsrModelDownloadJobService.kt'
if not uidt.exists():
    errors.append('Download regression: Android 14+ UIDT JobService missing')
else:
    utext = uidt.read_text(errors='ignore')
    for marker in ['setUserInitiated(true)', 'setEstimatedNetworkBytes', 'setNotification(', 'RUN_USER_INITIATED_JOBS' if False else 'JobService']:
        if marker not in utext:
            errors.append(f'Download regression: UIDT marker missing: {marker}')
if 'android.permission.RUN_USER_INITIATED_JOBS' not in manifest or 'android.permission.BIND_JOB_SERVICE' not in manifest:
    errors.append('Download regression: UIDT manifest permission/service binding missing')
if 'AsrModelDownloadJobService.schedule' not in asr_manager:
    errors.append('Download regression: AsrModelManager is not routing Android 14+ downloads through UIDT')


# v1.5.4 crash regression: never mix Kotlin interpolation containing a literal % with String.format/.format.
# This exact pattern caused UnknownFormatConversionException when `% ·` was parsed as a format specifier.
for rel in [
    'app/src/main/java/io/github/paper/classhelper/ui/ReaderActivity.kt',
    'app/src/main/java/io/github/paper/classhelper/ui/SettingsActivity.kt',
]:
    p = ROOT/rel
    t = p.read_text(errors='ignore') if p.exists() else ''
    if re.search(r'"[^"\n]*\$\{[^}]+\}%[^"\n]*"\.format\(', t):
        errors.append(f'Format regression: unsafe interpolated literal percent before .format() in {rel}')
if '后台下载 %d%% · %s' not in reader_text:
    errors.append('Format regression: ReaderActivity download progress must escape literal percent as %%')
settings_text = (ROOT/'app/src/main/java/io/github/paper/classhelper/ui/SettingsActivity.kt').read_text(errors='ignore')
if '后台下载 %d%% · %s · %s' not in settings_text:
    errors.append('Format regression: SettingsActivity download progress must escape literal percent as %%')


# v1.7.7 compile-compatibility regressions: keep locally verified build fixes intact.
build_gradle = (ROOT/'app/build.gradle.kts').read_text(errors='ignore')
proguard = (ROOT/'app/proguard-rules.pro').read_text(errors='ignore')
attrs_path = ROOT/'app/src/main/res/values/attrs.xml'
attrs_text = attrs_path.read_text(errors='ignore') if attrs_path.exists() else ''
themes_text = (ROOT/'app/src/main/res/values/themes.xml').read_text(errors='ignore')
if 'pickFirsts += setOf("**/libonnxruntime.so")' not in build_gradle:
    errors.append('Compile regression: duplicate libonnxruntime.so pickFirst rule missing')
for attr in ['insetLeft', 'insetRight', 'insetTop', 'insetBottom']:
    if f'name="{attr}"' not in attrs_text:
        errors.append(f'Compile regression: attrs.xml missing {attr}')
if '<style name="ShapeAppearanceOverlay.ClassHelper">' not in themes_text:
    errors.append('Compile regression: ShapeAppearanceOverlay.ClassHelper base style missing')
if re.search(r'com\.google\.android\.material\.R\.attr\.colorPrimary(?![A-Za-z0-9_])', source):
    errors.append('Compile regression: colorPrimary must be referenced from androidx.appcompat.R.attr')
if re.search(r'com\.google\.android\.material\.R\.attr\.colorError(?![A-Za-z0-9_])', source):
    errors.append('Compile regression: colorError must be referenced from androidx.appcompat.R.attr')
for marker in [
    '-keep class com.ahmer.pdfviewer.** { *; }',
    '-keep class com.tom_roush.pdfbox.** { *; }',
    '-keep class com.tom_roush.fontbox.** { *; }',
    '-dontwarn com.tom_roush.pdfbox.**',
]:
    if marker not in proguard:
        errors.append(f'Compile regression: missing ProGuard/R8 rule: {marker}')


# v1.7.9 annotation persistence regressions: one standard save path for add/erase.
writer_text = (ROOT/'app/src/main/java/io/github/paper/classhelper/pdf/PdfAnnotationWriter.kt').read_text(errors='ignore')
if 'saveIncremental(' in writer_text:
    errors.append('Annotation persistence regression: saveIncremental must stay removed')
if 'checkDeletedAnnotationsAbsent' in writer_text:
    errors.append('Annotation persistence regression: reopen-delete verification path must stay removed')
for marker in [
    'saveJournalTransaction(workspace, pending)',
    'doc.save(tmp)',
    'doc.getPage(pageIndex).annotations = ArrayList(annotations)',
    'awaitMutationJobs(workspace.id)',
    'StandardCopyOption.ATOMIC_MOVE',
]:
    if marker not in writer_text:
        errors.append(f'Annotation persistence regression: missing {marker}')


if errors:
    print('VALIDATION FAILED')
    for e in errors:
        print(' -', e)
    sys.exit(1)

print('VALIDATION OK')
print(f'XML files: {len(xml_files)}')
print(f'Kotlin files: {len(list(ROOT.glob("app/src/main/java/**/*.kt")))}')
print(f'R.id defined/referenced: {len(ids)}/{len(refs)}')
print('Policy scan: no Flutter/WebView/WAKE_LOCK/WorkManager markers')
print('ASR scan: Android 14+ UIDT downloader + legacy FGS fallback + SenseVoiceSmall INT8 + Silero VAD present')
