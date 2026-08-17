# Product Design pass — v1.3.0

## Brief
Redesign the existing native Android classroom/PDF experience for immediate learnability while preserving the current feature set. The primary outcomes are: find “open PDF” instantly, understand whether local ASR is ready, start listening in one step, and keep reading/annotation controls from competing with live classroom controls.

## Highest-impact issues found in the existing codebase
- First-use actions and persistent reader actions were mixed across two stacked top bars and a large empty-state card.
- Model download was hidden inside Settings even though it is a prerequisite for the core “listen” flow.
- Settings treated model management, LLM credentials and automation as equally weighted configuration.
- The model download path used a custom foreground data-sync service, creating a fragile lifecycle/permission path for a one-time user download.
- Live classroom, knowledge-base management and note history had equal visual weight in the side panel.

## Redesign decisions
- Keep one app bar and one three-action context row: Open / Listen / Classroom assistant.
- Make the first-use surface a true start screen with Open PDF as the primary CTA and Start listening as the secondary CTA.
- Put model readiness directly on the start screen and Settings; never require hunting for ASR configuration.
- Keep the PDF dominant once opened; annotations and low-frequency file actions stay contextual at the bottom.
- Reorder the classroom side panel around status -> live transcript -> question input -> class record -> secondary management actions.
- Use one app-owned foreground data-sync downloader for model files; keep progress persistent, resumable, and independent of Activity lifetime.
