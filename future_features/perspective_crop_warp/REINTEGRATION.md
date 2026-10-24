# Reintegration Guide — Perspective Crop & Warp

## Overview
Perspective Crop & Warp provides quadrilateral document corner de-skewing: users place 4 interactive corner pins on document edges, and `Matrix.setPolyToPoly` computes a projective perspective transformation to unwarp trapezoidal/angled camera captures into a flattened rectangular page.

## Files to Update
1. **`app/src/main/java/com/example/ScanVault/ImageEditorScreen.kt`**:
   - Re-add `EditorTool.PERSPECTIVE("Perspective", "📐")` to `EditorTool` enum.
   - Re-add the 4 corner offset states (`cornerTL`, `cornerTR`, `cornerBR`, `cornerBL`).
   - Re-add the canvas polygon overlay and touch handles.
   - Re-add the `EditorTool.PERSPECTIVE` bottom action panel.
   - Re-add `fun applyPerspectiveCrop(...)`.
   - Re-add `hasPerspectiveCrop` parameter in `saveEditedImageToFile(...)`.

## Dependencies & Permissions
- Standard Android graphics `Matrix`, `Canvas`, and `Bitmap` (already in app).

## Known Issues / Why It Was Deferred
- Resolution loss on save: The initial prototype saved the unwarped page from `previewBaseBitmap` (preview resolution) instead of applying the projective matrix to the full 4000x4000 source bitmap.
- Touch gesture ambiguity: Near corners, multi-touch pinch-to-zoom clashed with the corner drag handles. A magnifying loupe / corner zoom bubble is needed for precise pin placement on mobile touchscreens.
- Automatic document corner detection (edge quad detection) is needed so users don't have to position all 4 pins manually from scratch.
