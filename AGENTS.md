# ClassHelper Product Design Notes

## Product promise
The app is a PDF-first classroom assistant: read and annotate normally, start listening in one tap, surface teacher questions quickly, and keep a durable class record.

## Primary flows
1. First use: Open PDF OR start listening -> model download only if needed -> microphone -> live class.
2. Reading: PDF remains dominant; toolbars stay compact and contextual.
3. Classroom: status -> live transcript -> question/answer -> notes/resources.
4. Settings: model readiness first, LLM second, automation third.

## Design system
- Build on the existing neutral paper/surface/ink palette; avoid decorative color.
- 8dp spacing rhythm; cards 14-18dp radius; controls at least 44dp high.
- Use text labels rather than improvised icon glyphs.
- One primary action per region; secondary actions should not visually compete.
- Long explanations belong in helper copy, not controls.
- Preserve reading area and avoid persistent overlays unless they carry live classroom value.

## Interaction rules
- “开始听课” is always visible and is the natural entry to model setup.
- Missing model: explain once, then start a system-managed background download.
- Download progress must remain visible in-app but must not depend on an Activity staying alive.
- LLM is optional; reading, PDF annotations and local ASR must remain usable without it.
- Do not auto-jump PDF pages from weak speech matching; suggest a page and let the user jump.

## v1.4 Reader layout rules
- PDF content owns the full ReaderActivity viewport. Reader controls must overlay it instead of consuming vertical layout space.
- Reader chrome auto-hides during page movement and after a short idle delay; a light tap restores it.
- Annotation mode is an exception: keep required tools visible while drawing.
- Classroom UI is one sidebar. Do not reintroduce separate floating transcript/answer panels over the PDF.
- The classroom sidebar must always keep its composer and primary actions pinned; transcript/history scroll independently in the middle region.
