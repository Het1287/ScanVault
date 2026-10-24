# Reintegration Guide — Passport Photo Maker

## Overview
Passport Photo Maker crops portrait images to government passport standards (3.5 × 4.5 cm / 7:9 ratio, max 413 × 531 px at standard DPI) and compresses output strictly between 20-50 KB (default target $\le$ 45 KB) to satisfy UPSC, SSC, state PSC, and Passport Seva portal upload limits.

## Files to Update
1. **`app/src/main/java/com/example/ScanVault/Compression.kt`**:
   - Re-add `CompressionPreset.PASSPORT_PHOTO` to `CompressionPreset` enum.
   - Re-add the 3.5×4.5 cm aspect ratio crop and sizing block inside `performCustomCompression(...)`.
2. **`app/src/main/java/com/example/ScanVault/ToolsScreen.kt`**:
   - Re-add `CompressionPreset.PASSPORT_PHOTO` to the preset choices.
   - Update the tool item title back to "Passport & Signature" (or offer a dedicated "Passport Photo Maker" tile).
   - Re-add the custom dialog title and confirm button label branches for `PASSPORT_PHOTO`.

## Dependencies & Permissions
- Standard Android graphics utilities (already in app).

## Known Issues / Why It Was Deferred
- Automatic face detection and head centering: Centering currently relies on simple center-cropping. When a photo is taken slightly off-center or from chest level, faces were sometimes cut at the forehead.
- Background removal / replacement with pure white or light grey background (mandated by passport standards) needs ML Kit Selfie Segmentation before official launch.
