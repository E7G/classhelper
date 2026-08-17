# PDF OCR

ClassHelper 1.7.0 uses a two-tier local OCR path.

1. **PDF text layer first.** `PDFTextStripper` is always attempted because native PDF text is faster, more accurate, and uses far less power than image OCR.
2. Pages with fewer than 20 non-whitespace characters are treated as likely scans during normal indexing.
3. If the optional PP-OCRv6 package is installed, scan pages are rendered locally and passed through:
   `PP-OCRv6 Small detector -> text-region crop -> PP-OCRv6 Small recognizer -> CTC decode -> reading-order merge`.
4. If PP-OCRv6 is not installed or a page produces no usable result, the app falls back to the bundled Google ML Kit Chinese recognizer.
5. Explicit **OCR / re-index** in the reader OCRs every page; this is intentionally heavier than automatic scan-page indexing.

## Models

The optional detector and recognizer are the RapidOCR PP-OCRv6 small ONNX models from the RapidAI ModelScope repository. Downloads are SHA-256 verified before activation. The model files live in app-private external storage and may be deleted without touching PDFs or existing text indexes.

## Performance policy

- OCR is sequential to avoid fighting SenseVoice for CPU and memory.
- Default PP-OCR rendering targets a 2400 px long edge; high-accuracy/forced OCR targets 3000 px.
- Rendered bitmaps are capped at 12 megapixels.
- ONNX Runtime uses up to four intra-op threads and one inter-op thread; CPU arena allocation is disabled to control memory peaks.
- ML Kit remains a compatibility fallback rather than the primary high-accuracy OCR engine.

## Privacy

PP-OCRv6 and ML Kit recognition both run on-device in this app's OCR pipeline. The app does not send OCR page images to the configured LLM API.
