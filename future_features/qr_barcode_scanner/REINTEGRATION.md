# Reintegration Guide — QR Code & Barcode Scanner

## Overview
Scans images for standard 1D/2D barcodes (QR Code, Data Matrix, Aztec, PDF-417, EAN-13, Code 128, etc.), parses structured payloads (UPI payments with VPA/amount/name, Wi-Fi configuration credentials, URLs), and presents actions to pay with UPI, open links, copy, or share.

## Files to Copy Back
1. Copy `future_features/qr_barcode_scanner/com/example/ScanVault/QrBarcodeScannerEngine.kt` to:
   `app/src/main/java/com/example/ScanVault/QrBarcodeScannerEngine.kt`

## Gradle Dependency to Re-Add
In `app/build.gradle.kts`:
```kotlin
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.0")
```

## Changes to Re-Add to Shared Files
1. **`app/src/main/java/com/example/ScanVault/ToolsScreen.kt`**:
   - Re-add the tool tile in `directTools`:
     ```kotlin
     ToolItem("QR & Barcode", "📷", "Scan QR codes, barcodes, UPI & links", "qr_scanner")
     ```
   - Re-add `activeBarcodeResult` state and `barcodePickerLauncher`.
   - Re-add click handler: `"qr_scanner" -> barcodePickerLauncher.launch("image/*")`.
   - Re-add `BarcodeResultDialog` invocation when `activeBarcodeResult != null`.

## Permissions
- Camera / Gallery read permission (already present).

## Known Issues / Why It Was Deferred
- Users requested a live viewfinder camera preview (CameraX stream analyzer) rather than only picking static gallery photos.
- Offline scan history table was planned so users can revisit previous barcodes without re-scanning.
