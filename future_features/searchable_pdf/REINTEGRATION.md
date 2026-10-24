# Reintegration Guide — Searchable PDF (OCR Text Layer)

## Overview
Searchable PDF extracts words and lines from page bitmaps using ML Kit Latin and Devanagari text recognition, maps bounding boxes into PDF coordinate space, and embeds an invisible (`Color.TRANSPARENT`) text layer over the rendered page bitmap in Android's native `PdfDocument`, allowing text selection, copy-pasting, and search via `Ctrl+F` across PDF viewers.

## Files to Copy Back
1. Copy `future_features/searchable_pdf/com/example/ScanVault/SearchablePdfEngine.kt` to:
   `app/src/main/java/com/example/ScanVault/SearchablePdfEngine.kt`

## Changes to Re-Add to Shared Files
1. **`app/src/main/java/com/example/ScanVault/ToolsScreen.kt`**:
   - Re-add the tool tile in `directTools`:
     ```kotlin
     ToolItem("Searchable PDF", "🔍", "Convert scanned pages into selectable & searchable PDF", "searchable_pdf")
     ```
   - Re-add `searchablePdfLauncher` and `showSearchablePdfDialog` composable dialog.
   - Re-add the `"searchable_pdf" -> searchablePdfLauncher.launch("image/*")` click handler.
2. **`app/src/main/java/com/example/ScanVault/DocDetailScreen.kt`**:
   - Re-add `searchablePdf` checkbox row in `showAdvancedPdfDialog`.
   - Pass `searchablePdf` to `PasswordPdfExporter.exportPdf(...)`.
3. **`app/src/main/java/com/example/ScanVault/DocCard.kt`**:
   - Re-add `sharePdfSearchable` checkbox in `SharePdfRenameDialog`.
   - Pass `searchablePdf` to `shareDocAsPdf(...)`.
4. **`app/src/main/java/com/example/ScanVault/SecurityManager.kt`**:
   - Re-add `searchablePdf: Boolean = false` to `PasswordPdfExporter.exportPdf()` and `generatePdfFile()`.
   - Re-add the branch calling `SearchablePdfEngine.generateSearchablePdfFile()`.
5. **`app/src/main/java/com/example/ScanVault/FavoritesAndShare.kt`**:
   - Re-add `searchablePdf: Boolean = false` parameter in `shareDocAsPdf(...)`.

## Dependencies & Permissions
- ML Kit Latin text recognition: `com.google.android.gms:play-services-mlkit-text-recognition:19.0.0` (retained in app).
- ML Kit Devanagari text recognition: `com.google.mlkit:text-recognition-devanagari:16.0.1` (retained in app).
- Apache PDFBox Android: `com.tom-roush:pdfbox-android:2.0.27.0` (retained in app).

## Known Issues / Why It Was Deferred
- Skia PDF's native font rendering with `Color.TRANSPARENT` paint does not perfectly align character widths on all third-party PDF viewers (e.g. Adobe Acrobat vs Google Drive Viewer vs Chrome), leading to slight highlighting misalignments.
- Multi-page documents with 20+ pages require substantial memory and background processing time; needs a foreground notification worker with a progress bar before shipping.
