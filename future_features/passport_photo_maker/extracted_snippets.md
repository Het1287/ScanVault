# Extracted Snippets — Passport Photo Maker

The following code snippets were extracted from shared files before removal.

---

### 1. `app/src/main/java/com/example/ScanVault/Compression.kt`
Enum entry in `CompressionPreset`:
```kotlin
PASSPORT_PHOTO("Passport Photo", "20-50 KB", "3.5×4.5 cm (7:9 ratio), ≤45 KB. Fits UPSC, SSC & Passport Seva portals", 45 * 1024L),
```

Aspect ratio cropping and scaling branch in `performCustomCompression`:
```kotlin
            if (preset == CompressionPreset.PASSPORT_PHOTO) {
                // 3.5 x 4.5 cm (7:9 ratio)
                val cropped = cropToAspectRatio(baseBitmap, 3.5f, 4.5f)
                if (cropped != baseBitmap) baseBitmap.recycle()
                val maxW = 413
                val maxH = 531
                baseBitmap = if (cropped.width > maxW || cropped.height > maxH) {
                    val f = Math.min(maxW.toFloat() / cropped.width, maxH.toFloat() / cropped.height)
                    val scaled = Bitmap.createScaledBitmap(cropped, (cropped.width * f).toInt().coerceAtLeast(1), (cropped.height * f).toInt().coerceAtLeast(1), true)
                    if (scaled != cropped) cropped.recycle()
                    scaled
                } else {
                    cropped
                }
            }
```

---

### 2. `app/src/main/java/com/example/ScanVault/ToolsScreen.kt`
Tool tile trigger state handling:
```kotlin
            if (initialCompressionPreset == CompressionPreset.PASSPORT_PHOTO) {
                compressCustomName = "${originalBase}_passport_photo"
                selectedPreset = CompressionPreset.PASSPORT_PHOTO
                compressionMode = CompressionMode.PRESETS
            }
```

Presets list entry:
```kotlin
CompressionPreset.PASSPORT_PHOTO,
```

Dialog title:
```kotlin
val dialogTitle = when {
    compressionMode == CompressionMode.PRESETS && selectedPreset == CompressionPreset.PASSPORT_PHOTO -> "Passport Photo Maker"
    compressionMode == CompressionMode.PRESETS && selectedPreset == CompressionPreset.SIGNATURE -> "Signature Formatter"
    else -> "Compress & Format"
}
```

Button label:
```kotlin
val buttonLabel = when {
    compressionMode == CompressionMode.PRESETS && selectedPreset == CompressionPreset.PASSPORT_PHOTO -> "Create Passport Photo"
    compressionMode == CompressionMode.PRESETS && selectedPreset == CompressionPreset.SIGNATURE -> "Format Signature"
    else -> "Compress & Save"
}
```

Tool tile (was "Passport & Signature", modified to "Signature Formatter" to keep signatures functional):
```kotlin
ToolItem("Passport & Signature", "📸", "Format 3.5×4.5cm photo or signature for Govt portals", "passport_preset"),
```


## Unit Test: app/src/test/java/com/example/ScanVault/NewFeaturesAndFixesUnitTest.kt
```kotlin
        // Passport photo standard: 20-50 KB -> target 45 KB
        val passportTarget = CompressionPreset.PASSPORT_PHOTO.targetBytes
        assertNotNull(passportTarget)
        assertTrue(passportTarget!! in (20 * 1024L)..(50 * 1024L))
        assertEquals(45 * 1024L, passportTarget)
```
