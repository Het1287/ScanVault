# Reintegration Guide — ID Card Mode

## Overview
ID Card Mode captures front and back images of standard ISO/IEC 7810 ID cards (Aadhaar, PAN, Voter ID, driving license) and renders them with exact 85.6 x 54 mm physical dimensions onto a single print-ready A4 canvas with center cutting guide.

## Files to Copy Back
1. Copy `future_features/id_card_mode/com/example/ScanVault/IdCardEngine.kt` back to:
   `app/src/main/java/com/example/ScanVault/IdCardEngine.kt`
   *(Note: Ensure shared methods `decodeUriWithExif`, `calculateInSampleSize`, and `applyExifFromStream` merge seamlessly with any updates made to `ImageDecoderUtils` or `IdCardGenerator` in the main codebase).*

## Changes to Re-Add to Shared Files
1. **`app/src/main/res/values/strings.xml`**:
   Add back the string keys:
   ```xml
   <string name="id_card_mode_title">ID Card Mode</string>
   <string name="id_card_mode_description">Combine Front &amp; Back of an ID onto a single A4 page with standard 85.6 x 54 mm physical dimensions. Aspect ratios are preserved without stretching.</string>
   <string name="pick_front">+ Pick Front</string>
   <string name="pick_back">+ Pick Back</string>
   ```

2. **`app/src/main/java/com/example/ScanVault/MainApp.kt`**:
   - Re-add state:
     ```kotlin
     var showIdCardDialog by remember { mutableStateOf(false) }
     ```
   - Re-add the dialog composable:
     ```kotlin
     if (showIdCardDialog) {
         IdCardModeDialog(
             activity = activity,
             targetFolder = activeFolder,
             onDismiss = { showIdCardDialog = false },
             onSavedSuccessfully = {
                 showIdCardDialog = false
                 refreshDocs()
                 triggerAutoSyncIfEnabled()
             }
         )
     }
     ```
   - Pass `onIdCardTrigger = { showIdCardDialog = true }` into `FilesScreenDesign`.

3. **`app/src/main/java/com/example/ScanVault/FilesScreen.kt`**:
   - Re-add `onIdCardTrigger: () -> Unit = {}` to the parameters of `FilesScreenDesign`.
   - Re-add the `🪪 ID Card` button in the top action bar next to `+ Scan`.

## Permissions & Dependencies
- Standard camera / gallery read permissions (already in app).
- No extra Gradle dependencies required (uses native Android `Canvas` and `Bitmap`).

## Known Issues / Why It Was Deferred
- Camera photo aspect ratios vary widely across phone sensors; users requested automated edge detection or auto-crop specifically tailored to credit card / ID card boundaries before placing onto the A4 sheet.
- Direct ML Kit Document Scanner integration for separate two-sided capture should be explored to replace manual image picking.
