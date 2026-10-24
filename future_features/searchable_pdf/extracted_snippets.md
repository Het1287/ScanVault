# Extracted Snippets — Searchable PDF (OCR Text Layer)

The following code snippets were extracted from shared files before removal.

---

### 1. `app/src/main/java/com/example/ScanVault/ToolsScreen.kt`
Tool Tile definition in `directTools`:
```kotlin
ToolItem("Searchable PDF", "🔍", "Convert scanned pages into selectable & searchable PDF", "searchable_pdf"),
```

Launcher definition:
```kotlin
    val searchablePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val defaultName = if (uris.size == 1) queryOriginalFileName(activity, uris[0])
            else "SearchableDoc_${SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())}"
            pendingSearchablePdfUris = uris
            searchablePdfCustomName = defaultName
            showSearchablePdfDialog = true
        }
    }
```

Tool click handling:
```kotlin
"searchable_pdf" -> searchablePdfLauncher.launch("image/*")
```

Dialog UI:
```kotlin
    if (showSearchablePdfDialog && pendingSearchablePdfUris.isNotEmpty()) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showSearchablePdfDialog = false },
            title = { Text("Searchable PDF", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Converts ${pendingSearchablePdfUris.size} page(s) into a PDF with an invisible OCR text layer (ML Kit Latin + Devanagari) so text can be searched and selected.",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = searchablePdfCustomName,
                        onValueChange = { searchablePdfCustomName = it },
                        singleLine = true,
                        label = { Text("PDF File Name", color = colors.textMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.cardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val chosenName = sanitizeName(searchablePdfCustomName).ifEmpty { "SearchableDoc_${System.currentTimeMillis()}" }
                        showSearchablePdfDialog = false
                        isProcessing = true
                        coroutineScope.launch {
                            val targetUri = withContext(Dispatchers.IO) {
                                try {
                                    val tempDir = File(activity.cacheDir, "searchable_tmp_${System.currentTimeMillis()}")
                                    tempDir.mkdirs()
                                    val imageFiles = pendingSearchablePdfUris.mapIndexed { idx, u ->
                                        val f = File(tempDir, "page_$idx.jpg")
                                        activity.contentResolver.openInputStream(u)?.use { inp ->
                                            f.outputStream().use { out -> inp.copyTo(out) }
                                        }
                                        f
                                    }.filter { it.exists() && it.length() > 0 }

                                    val uri = SearchablePdfEngine.exportSearchablePdf(
                                        context = activity,
                                        baseName = chosenName,
                                        imageFiles = imageFiles
                                    )
                                    tempDir.deleteRecursively()
                                    uri
                                } catch (e: Exception) {
                                    android.util.Log.e("ToolsScreen", "Searchable PDF error: ${e.message}")
                                    null
                                }
                            }
                            isProcessing = false
                            onRefresh()
                            if (targetUri != null) {
                                val bytes = withContext(Dispatchers.IO) { getUriByteSize(activity, targetUri) }
                                activeCompletionResult = ToolCompletionResult(
                                    title = "Searchable PDF Created",
                                    fileName = "$chosenName.pdf",
                                    uri = targetUri,
                                    mimeType = "application/pdf",
                                    newBytes = bytes
                                )
                            } else {
                                Toast.makeText(activity, "Failed to create Searchable PDF", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Generate PDF", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSearchablePdfDialog = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }
```

---

### 2. `app/src/main/java/com/example/ScanVault/DocDetailScreen.kt`
In `showAdvancedPdfDialog`:
```kotlin
    var searchablePdf by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { searchablePdf = !searchablePdf }
    ) {
        Checkbox(
            checked = searchablePdf,
            onCheckedChange = { searchablePdf = it },
            colors = CheckboxDefaults.colors(checkedColor = colors.accent)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text("🔍 Searchable PDF (Selectable Text)", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Embeds OCR text layer", color = colors.textMuted, fontSize = 11.sp)
        }
    }
```

---

### 3. `app/src/main/java/com/example/ScanVault/DocCard.kt`
In `SharePdfRenameDialog`:
```kotlin
    var sharePdfSearchable by remember { mutableStateOf(true) }

    // Searchable PDF (OCR text layer) option
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { sharePdfSearchable = !sharePdfSearchable }
            .padding(vertical = 4.dp)
    ) {
        Checkbox(
            checked = sharePdfSearchable,
            onCheckedChange = { sharePdfSearchable = it },
            colors = CheckboxDefaults.colors(checkedColor = colors.accent)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text("🔍 Searchable PDF (Selectable text)", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Select, copy & search text with Ctrl+F", color = colors.textMuted, fontSize = 11.sp)
        }
    }
```

---

### 4. `app/src/main/java/com/example/ScanVault/SecurityManager.kt`
In `PasswordPdfExporter.generatePdfFile`:
```kotlin
val tempRawPdf: File = if (searchablePdf) {
    SearchablePdfEngine.generateSearchablePdfFile(
        context = context,
        baseName = baseName,
        imageFiles = imageFiles,
        maskAadhaar = maskAadhaar,
        watermarkText = watermarkText
    ) ?: return@withContext null
} else { ... }
```


## Unit Test: app/src/test/java/com/example/ScanVault/NewFeaturesAndFixesUnitTest.kt
```kotlin
    @Test
    fun testPhase10d_searchableTextElementScaling() {
        val rect = RectF(100f, 200f, 500f, 260f)
        val element = SearchableTextElement(
            text = "INVOICE #98234",
            rectInImage = rect,
            fontSize = 40f
        )
        assertEquals("INVOICE #98234", element.text)
        assertEquals(40f, element.fontSize, 0.001f)

        val elemLeft = 100f
        val elemTop = 200f
        val elemRight = 500f
        val elemBottom = 260f

        val bmpW = 1000f
        val bmpH = 2000f
        val pdfPageW = 595f
        val pdfPageH = 842f
        val scale = minOf(pdfPageW / bmpW, pdfPageH / bmpH)
        val offsetX = (pdfPageW - bmpW * scale) / 2f
        val offsetY = (pdfPageH - bmpH * scale) / 2f

        val pdfLeft = offsetX + elemLeft * scale
        val pdfTop = offsetY + elemTop * scale
        val pdfRight = offsetX + elemRight * scale
        val pdfBottom = offsetY + elemBottom * scale

        assertTrue(pdfLeft >= 0f)
        assertTrue(pdfLeft <= pdfPageW)
        assertTrue(pdfRight > pdfLeft)
        assertTrue(pdfRight <= pdfPageW)
        assertTrue(pdfTop >= 0f)
        assertTrue(pdfTop <= pdfPageH)
        assertTrue(pdfBottom > pdfTop)
        assertTrue(pdfBottom <= pdfPageH)
    }
```
