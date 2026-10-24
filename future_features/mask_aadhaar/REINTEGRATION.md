# Reintegration Guide — Mask Aadhaar Card

## Overview
Mask Aadhaar automatically recognizes 12-digit UIDAI Aadhaar numbers (both 4-4-4 formatted and continuous 12-digit) via ML Kit Text Recognition and draws a compliant black pill mask with "XXXX XXXX" text over the first 8 digits, leaving only the last 4 digits visible.

## Files to Update / Reintegrate
1. Re-add `detectAndMaskAadhaar(...)`, `AADHAAR_GROUPED_REGEX`, and `AADHAAR_12_DIGIT_REGEX` into:
   `app/src/main/java/com/example/ScanVault/MaskAndWatermarkEngine.kt`
2. Re-add `maskAadhaar: Boolean = false` parameter to:
   - `MaskAndWatermarkEngine.processImage(...)`
   - `MaskAndWatermarkEngine.exportAsPdf(...)`
   - `MaskAndWatermarkEngine.exportAsJpg(...)`
   - `PasswordPdfExporter.exportPdf(...)` in `SecurityManager.kt`
   - `PasswordPdfExporter.generatePdfFile(...)` in `SecurityManager.kt`
   - `shareDocAsPdf(...)` in `FavoritesAndShare.kt`

## UI Entry Points to Re-Add
1. **`app/src/main/java/com/example/ScanVault/ToolsScreen.kt`**:
   In the Watermark dialog, re-add the "Mask Aadhaar Number" toggle card.
2. **`app/src/main/java/com/example/ScanVault/DocDetailScreen.kt`**:
   In `showAdvancedPdfDialog`, re-add the "🛡️ Mask Aadhaar (UIDAI Compliant)" checkbox row.
3. **`app/src/main/java/com/example/ScanVault/DocCard.kt`**:
   In `SharePdfRenameDialog`, re-add the "🛡️ Mask Aadhaar (UIDAI Compliant)" checkbox row.

## Dependencies & Permissions
- ML Kit Text Recognition: `com.google.android.gms:play-services-mlkit-text-recognition:19.0.0` (already included in app for OCR).
- No special permissions required.

## Known Issues / Why It Was Deferred
- On skewed, wrinkled, or low-light Aadhaar photocopies, ML Kit sometimes segments the 12 digits across multiple bounding blocks or misidentifies numbers near the UIDAI logo.
- Interactive user confirmation / preview with an adjustable manual mask box before saving/exporting is recommended for high reliability in government submissions.
