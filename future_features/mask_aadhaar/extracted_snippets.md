# Extracted Snippets — Mask Aadhaar Card

The following code snippets were extracted from shared files before removal.

---

### 1. `app/src/main/java/com/example/ScanVault/MaskAndWatermarkEngine.kt`
Aadhaar detection and masking implementation:
```kotlin
    val AADHAAR_GROUPED_REGEX = Regex("""\b(\d{4})[\s-](\d{4})[\s-](\d{4})\b""")
    val AADHAAR_12_DIGIT_REGEX = Regex("""\b(\d{12})\b""")

    /**
     * Detects 12-digit Aadhaar numbers and overlays a solid mask with 'XXXX XXXX'
     * on the first 8 digits, leaving only the last 4 digits visible under UIDAI regulations.
     */
    suspend fun detectAndMaskAadhaar(context: Context, bitmap: Bitmap): Pair<Bitmap, Int> = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(inputImage).awaitTask()

            val maskBoxes = mutableListOf<Rect>()

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val lineText = line.text.trim()
                    val matchGrouped = AADHAAR_GROUPED_REGEX.find(lineText)
                    val match12 = AADHAAR_12_DIGIT_REGEX.find(lineText)

                    if (matchGrouped != null || match12 != null) {
                        // Priority 1: Check if line elements match 3 chunks of 4 digits
                        val digitElements = line.elements.filter { el ->
                            val digitsOnly = el.text.filter { it.isDigit() }
                            digitsOnly.length == 4
                        }

                        if (digitElements.size >= 3) {
                            val el0 = digitElements[0].boundingBox
                            val el1 = digitElements[1].boundingBox
                            if (el0 != null && el1 != null) {
                                val combined = Rect(
                                    minOf(el0.left, el1.left) - 4,
                                    minOf(el0.top, el1.top) - 3,
                                    maxOf(el0.right, el1.right) + 4,
                                    maxOf(el0.bottom, el1.bottom) + 3
                                )
                                maskBoxes.add(combined)
                                continue
                            }
                        }

                        // Priority 2: Relative proportion within line bounding box
                        val lineBox = line.boundingBox
                        if (lineBox != null && lineText.isNotEmpty()) {
                            val match = matchGrouped ?: match12
                            if (match != null) {
                                val startIdx = match.range.first
                                val eightDigitsEndIdx = startIdx + (if (match == matchGrouped) 9 else 8)
                                val leftRatio = (startIdx.toFloat() / lineText.length.toFloat()).coerceIn(0f, 1f)
                                val rightRatio = (eightDigitsEndIdx.toFloat() / lineText.length.toFloat()).coerceIn(0f, 1f)

                                val maskLeft = (lineBox.left + (lineBox.width() * leftRatio).toInt() - 4).coerceAtLeast(0)
                                val maskRight = (lineBox.left + (lineBox.width() * rightRatio).toInt() + 4).coerceAtMost(bitmap.width)
                                maskBoxes.add(Rect(maskLeft, (lineBox.top - 3).coerceAtLeast(0), maskRight, (lineBox.bottom + 3).coerceAtMost(bitmap.height)))
                            }
                        }
                    }
                }
            }

            if (maskBoxes.isEmpty()) {
                return@withContext Pair(bitmap, 0)
            }

            val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(outputBitmap)

            val bgPaint = Paint().apply {
                color = android.graphics.Color.BLACK
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            val textPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }

            for (box in maskBoxes) {
                canvas.drawRoundRect(RectF(box), 4f, 4f, bgPaint)
                textPaint.textSize = (box.height() * 0.65f).coerceIn(8f, 72f)
                val textY = box.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2)
                canvas.drawText("XXXX XXXX", box.centerX().toFloat(), textY, textPaint)
            }

            Pair(outputBitmap, maskBoxes.size)
        } catch (e: Exception) {
            android.util.Log.e("MaskAndWatermarkEngine", "Error detecting/masking Aadhaar: ${e.message}", e)
            Pair(bitmap, 0)
        } finally {
            recognizer.close()
        }
    }
```

---

### 2. `app/src/main/java/com/example/ScanVault/DocDetailScreen.kt`
In `showAdvancedPdfDialog`:
```kotlin
    var maskAadhaar by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { maskAadhaar = !maskAadhaar }
    ) {
        Checkbox(
            checked = maskAadhaar,
            onCheckedChange = { maskAadhaar = it },
            colors = CheckboxDefaults.colors(checkedColor = colors.accent)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text("🛡️ Mask Aadhaar (UIDAI Compliant)", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Masks first 8 digits", color = colors.textMuted, fontSize = 11.sp)
        }
    }
```

---

### 3. `app/src/main/java/com/example/ScanVault/DocCard.kt`
In `SharePdfRenameDialog`:
```kotlin
    var sharePdfMaskAadhaar by remember { mutableStateOf(false) }

    // Mask Aadhaar option
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { sharePdfMaskAadhaar = !sharePdfMaskAadhaar }
            .padding(vertical = 4.dp)
    ) {
        Checkbox(
            checked = sharePdfMaskAadhaar,
            onCheckedChange = { sharePdfMaskAadhaar = it },
            colors = CheckboxDefaults.colors(checkedColor = colors.accent)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text("🛡️ Mask Aadhaar (UIDAI Compliant)", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Hide first 8 digits (XXXX XXXX 1234)", color = colors.textMuted, fontSize = 11.sp)
        }
    }
```

---

### 4. `app/src/main/java/com/example/ScanVault/SecurityManager.kt`
In `PasswordPdfExporter.generatePdfFile`:
```kotlin
val bitmap = if (maskAadhaar || !watermarkText.isNullOrBlank()) {
    val (procBmp, _) = MaskAndWatermarkEngine.processImage(context, rawBitmap, maskAadhaar, watermarkText)
    if (procBmp != rawBitmap) rawBitmap.recycle()
    procBmp
} else {
    rawBitmap
}
```

---

### 5. `app/src/main/java/com/example/ScanVault/ToolsScreen.kt`
In the Mask & Watermark Dialog:
```kotlin
    var doMaskAadhaar by remember { mutableStateOf(true) }

    // Aadhaar Masking Card
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.cardSubtle),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { doMaskAadhaar = !doMaskAadhaar }
            ) {
                Checkbox(
                    checked = doMaskAadhaar,
                    onCheckedChange = { doMaskAadhaar = it },
                    colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        "Mask Aadhaar Number",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        "UIDAI compliant: hides first 8 digits (XXXX XXXX 1234)",
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
```


## Unit Tests: app/src/test/java/com/example/ScanVault/NewFeaturesAndFixesUnitTest.kt
```kotlin
    @Test
    fun testPhase10b_aadhaarRegexPatterns() {
        val groupedRegex = MaskAndWatermarkEngine.AADHAAR_GROUPED_REGEX
        val continuousRegex = MaskAndWatermarkEngine.AADHAAR_12_DIGIT_REGEX

        // Valid grouped format
        assertTrue("Grouped spaces should match", groupedRegex.containsMatchIn("Aadhaar: 1234 5678 9012 in doc"))
        assertTrue("Grouped dashes should match", groupedRegex.containsMatchIn("UID: 9876-5432-1098"))

        // Extract groups
        val match = groupedRegex.find("Card No: 5412 8765 4321")
        assertNotNull(match)
        assertEquals("5412", match!!.groups[1]?.value)
        assertEquals("8765", match.groups[2]?.value)
        assertEquals("4321", match.groups[3]?.value)

        // Valid continuous format
        assertTrue("Continuous 12 digits should match", continuousRegex.containsMatchIn("Aadhaar 123456789012"))
        val match12 = continuousRegex.find("Aadhaar 998877665544")
        assertNotNull(match12)
        assertEquals("998877665544", match12!!.value)

        // Invalid formats
        assertFalse("11 digits should not match continuous", continuousRegex.containsMatchIn("12345678901"))
        assertFalse("13 digits should not match continuous", continuousRegex.containsMatchIn("1234567890123"))
        assertFalse("Random text should not match grouped", groupedRegex.containsMatchIn("1234 5678 abcd"))
    }

    @Test
    fun testPhase10b_aadhaarMaskBoundingCalculations() {
        // Line text: "Aadhaar: 1234 5678 9012"
        val lineText = "Aadhaar: 1234 5678 9012"
        val match = MaskAndWatermarkEngine.AADHAAR_GROUPED_REGEX.find(lineText)
        assertNotNull(match)

        val startIdx = match!!.range.first // index of '1'
        val eightDigitsEndIdx = startIdx + 9 // '1234 5678 ' = 9 chars
        val leftRatio = (startIdx.toFloat() / lineText.length.toFloat()).coerceIn(0f, 1f)
        val rightRatio = (eightDigitsEndIdx.toFloat() / lineText.length.toFloat()).coerceIn(0f, 1f)

        // Verify the first 8 digits ratio is before the last 4 digits
        assertTrue("Mask start must be before end", leftRatio < rightRatio)
        assertTrue("Mask must start after prefix", leftRatio > 0.3f)
        assertTrue("Mask must end before full line end to leave last 4 visible", rightRatio < 0.95f)

        // Simulate bounding box calculation for 1000px line
        val lineLeft = 100
        val lineWidth = 800
        val maskLeft = lineLeft + (lineWidth * leftRatio).toInt()
        val maskRight = lineLeft + (lineWidth * rightRatio).toInt()
        val last4Left = maskRight

        assertTrue(maskLeft < maskRight)
        assertTrue("Last 4 digits remain to the right of mask", (lineLeft + lineWidth) > last4Left)
    }
```
