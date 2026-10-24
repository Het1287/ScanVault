# Extracted Snippets — QR Code & Barcode Scanner

The following code snippets were extracted from shared files before removal.

---

### 1. `app/build.gradle.kts`
Under `dependencies { ... }`:
```kotlin
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.0")
```

---

### 2. `app/src/main/java/com/example/ScanVault/ToolsScreen.kt`
Tool Tile in `directTools`:
```kotlin
ToolItem("QR & Barcode", "📷", "Scan QR codes, barcodes, UPI & links", "qr_scanner")
```

State and Launcher:
```kotlin
    var activeBarcodeResult by remember { mutableStateOf<BarcodeScanResult?>(null) }
    val barcodePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isProcessing = true
                try {
                    val bmp = withContext(Dispatchers.IO) {
                        activity.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }
                    if (bmp != null) {
                        val res = QrBarcodeScannerEngine.scanBitmap(bmp)
                        if (res.isNotEmpty()) {
                            activeBarcodeResult = res.first()
                        } else {
                            Toast.makeText(activity, "No QR or barcode detected in image", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(activity, "Failed to load selected image", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(activity, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isProcessing = false
                }
            }
        }
    }
```

Tool click handler:
```kotlin
"qr_scanner" -> barcodePickerLauncher.launch("image/*")
```

Dialog invocation at bottom of screen:
```kotlin
    activeBarcodeResult?.let { scanRes ->
        BarcodeResultDialog(
            result = scanRes,
            colors = colors,
            context = activity,
            onDismiss = { activeBarcodeResult = null }
        )
    }
```


## Unit Test: app/src/test/java/com/example/ScanVault/NewFeaturesAndFixesUnitTest.kt
```kotlin
    @Test
    fun testPhase10h_barcodeScanResultModel() {
        val upiResult = BarcodeScanResult(
            rawValue = "upi://pay?pa=merchant@upi&pn=SuperStore&am=250.00",
            displayValue = "upi://pay?pa=merchant@upi&pn=SuperStore&am=250.00",
            formatName = "QR Code",
            valueType = 1,
            isUrl = false,
            isUpi = true,
            upiVpa = "merchant@upi",
            upiName = "SuperStore",
            upiAmount = "250.00"
        )
        assertTrue(upiResult.isUpi)
        assertFalse(upiResult.isUrl)
        assertEquals("merchant@upi", upiResult.upiVpa)
        assertEquals("SuperStore", upiResult.upiName)
        assertEquals("250.00", upiResult.upiAmount)

        val urlResult = BarcodeScanResult(
            rawValue = "https://github.com/myproject",
            displayValue = "https://github.com/myproject",
            formatName = "QR Code",
            valueType = 8,
            isUrl = true,
            isUpi = false
        )
        assertTrue(urlResult.isUrl)
        assertFalse(urlResult.isUpi)
        assertEquals("https://github.com/myproject", urlResult.rawValue)

        val wifiResult = BarcodeScanResult(
            rawValue = "WIFI:S:OfficeGuest;T:WPA;P:guest123;;",
            displayValue = "WIFI:S:OfficeGuest;T:WPA;P:guest123;;",
            formatName = "QR Code",
            valueType = 9,
            isUrl = false,
            isUpi = false,
            wifiSsid = "OfficeGuest",
            wifiPassword = "guest123"
        )
        assertEquals("OfficeGuest", wifiResult.wifiSsid)
        assertEquals("guest123", wifiResult.wifiPassword)
    }
```
