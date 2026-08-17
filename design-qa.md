# Design QA — v1.4.0 Immersive Reader

source visual truth path: none; redesign is driven by the user's explicit interaction requirements and existing ClassHelper design notes.
implementation screenshot path: unavailable in the current container because no Android SDK/emulator is installed.
viewport: responsive Android phone/tablet layout; side panel capped at 360dp and reduced to screen width minus 24dp.
state: PDF reading + classroom sidebar.

## Full-view comparison evidence
Blocked for visual screenshot comparison: there is no supplied mock/screenshot target and the environment cannot render the Android activity.

## Focused region comparison evidence
Blocked for the same reason. Source-level layout QA was performed instead.

## Findings addressed in source
- P1: persistent stacked top bars and status/tool rows reduced visible PDF height -> replaced by overlay chrome.
- P1: transcript growth pushed classroom controls off-screen -> scrollable middle region + pinned bottom composer/actions.
- P1: answer floating card covered PDF -> moved into the unified classroom sidebar.
- P2: classroom functions were fragmented across answer card, side panel, and reader toolbar -> consolidated into one sidebar.
- P2: reader controls remained visible during continuous reading -> tap-to-show, scroll-to-hide, delayed auto-hide added.

## Interaction checks performed from source
- PDF touch listener returns false so AhmerPdfViewer retains gesture handling.
- Drawing mode suppresses chrome auto-hide.
- Side panel suppresses reader chrome and has its own persistent close control.
- New teacher question opens the sidebar once per question.
- All ReaderActivity R.id references exist in activity_reader.xml.
- XML resources parse successfully.

## Remaining blocker
A visual Design QA pass requires a rendered Android screenshot at the target device size. The current container cannot launch an Android emulator and cannot download the Gradle distribution because services.gradle.org DNS resolution fails.

final result: blocked
