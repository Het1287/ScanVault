# Extracted Snippets — Perspective Crop & Warp

The following code snippets were extracted from shared files before removal.

---

### 1. `app/src/main/java/com/example/ScanVault/ImageEditorScreen.kt`
Tool mode enum value in `EditorTool`:
```kotlin
PERSPECTIVE("Perspective", "📐")
```

State variables in `ImageEditorScreen`:
```kotlin
    // Perspective crop state
    var hasPerspectiveCropped by remember { mutableStateOf(false) }
    var cornerTL by remember { mutableStateOf(Offset(0.04f, 0.04f)) }
    var cornerTR by remember { mutableStateOf(Offset(0.96f, 0.04f)) }
    var cornerBR by remember { mutableStateOf(Offset(0.96f, 0.96f)) }
    var cornerBL by remember { mutableStateOf(Offset(0.04f, 0.96f)) }
```

Pointer input event handling:
```kotlin
if (activeTool == EditorTool.FILTERS || activeTool == EditorTool.SIGNATURE || activeTool == EditorTool.PERSPECTIVE) { ... }
```

Canvas rendering 4 draggable corner pins and quadrilateral polygon overlay:
```kotlin
    // Perspective Crop Quadrilateral & Corner Pins
    if (activeTool == EditorTool.PERSPECTIVE) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val pTL = Offset(cornerTL.x * w, cornerTL.y * h)
            val pTR = Offset(cornerTR.x * w, cornerTR.y * h)
            val pBR = Offset(cornerBR.x * w, cornerBR.y * h)
            val pBL = Offset(cornerBL.x * w, cornerBL.y * h)

            val polyPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(pTL.x, pTL.y)
                lineTo(pTR.x, pTR.y)
                lineTo(pBR.x, pBR.y)
                lineTo(pBL.x, pBL.y)
                close()
            }

            drawPath(polyPath, color = colors.accent.copy(alpha = 0.15f))
            drawPath(
                path = polyPath,
                color = colors.accent,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(15f, 15f))
                )
            )

            val pinRadius = 14.dp.toPx()
            listOf(pTL, pTR, pBR, pBL).forEach { pt ->
                drawCircle(color = Color.White, radius = pinRadius, center = pt)
                drawCircle(color = colors.accent, radius = pinRadius, center = pt, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                drawCircle(color = colors.accent, radius = 4.dp.toPx(), center = pt)
            }
        }

        // Draggable touch handle boxes on top of corner pins
        val touchBoxSizeDp = 48.dp
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (cornerTL.x * displayedWidthPx - 24.dp.toPx()).roundToInt(),
                        (cornerTL.y * displayedHeightPx - 24.dp.toPx()).roundToInt()
                    )
                }
                .size(touchBoxSizeDp)
                .pointerInput(displayedWidthPx, displayedHeightPx) {
                    detectDragGestures(
                        onDragStart = { isDraggingHandleOrSignature = true },
                        onDragEnd = { isDraggingHandleOrSignature = false },
                        onDragCancel = { isDraggingHandleOrSignature = false }
                    ) { change, dragAmount ->
                        change.consume()
                        cornerTL = Offset(
                            (cornerTL.x + dragAmount.x / displayedWidthPx).coerceIn(0f, cornerTR.x - 0.05f),
                            (cornerTL.y + dragAmount.y / displayedHeightPx).coerceIn(0f, cornerBL.y - 0.05f)
                        )
                    }
                }
        )

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (cornerTR.x * displayedWidthPx - 24.dp.toPx()).roundToInt(),
                        (cornerTR.y * displayedHeightPx - 24.dp.toPx()).roundToInt()
                    )
                }
                .size(touchBoxSizeDp)
                .pointerInput(displayedWidthPx, displayedHeightPx) {
                    detectDragGestures(
                        onDragStart = { isDraggingHandleOrSignature = true },
                        onDragEnd = { isDraggingHandleOrSignature = false },
                        onDragCancel = { isDraggingHandleOrSignature = false }
                    ) { change, dragAmount ->
                        change.consume()
                        cornerTR = Offset(
                            (cornerTR.x + dragAmount.x / displayedWidthPx).coerceIn(cornerTL.x + 0.05f, 1f),
                            (cornerTR.y + dragAmount.y / displayedHeightPx).coerceIn(0f, cornerBR.y - 0.05f)
                        )
                    }
                }
        )

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (cornerBR.x * displayedWidthPx - 24.dp.toPx()).roundToInt(),
                        (cornerBR.y * displayedHeightPx - 24.dp.toPx()).roundToInt()
                    )
                }
                .size(touchBoxSizeDp)
                .pointerInput(displayedWidthPx, displayedHeightPx) {
                    detectDragGestures(
                        onDragStart = { isDraggingHandleOrSignature = true },
                        onDragEnd = { isDraggingHandleOrSignature = false },
                        onDragCancel = { isDraggingHandleOrSignature = false }
                    ) { change, dragAmount ->
                        change.consume()
                        cornerBR = Offset(
                            (cornerBR.x + dragAmount.x / displayedWidthPx).coerceIn(cornerBL.x + 0.05f, 1f),
                            (cornerBR.y + dragAmount.y / displayedHeightPx).coerceIn(cornerTR.y + 0.05f, 1f)
                        )
                    }
                }
        )

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (cornerBL.x * displayedWidthPx - 24.dp.toPx()).roundToInt(),
                        (cornerBL.y * displayedHeightPx - 24.dp.toPx()).roundToInt()
                    )
                }
                .size(touchBoxSizeDp)
                .pointerInput(displayedWidthPx, displayedHeightPx) {
                    detectDragGestures(
                        onDragStart = { isDraggingHandleOrSignature = true },
                        onDragEnd = { isDraggingHandleOrSignature = false },
                        onDragCancel = { isDraggingHandleOrSignature = false }
                    ) { change, dragAmount ->
                        change.consume()
                        cornerBL = Offset(
                            (cornerBL.x + dragAmount.x / displayedWidthPx).coerceIn(0f, cornerBR.x - 0.05f),
                            (cornerBL.y + dragAmount.y / displayedHeightPx).coerceIn(cornerTL.y + 0.05f, 1f)
                        )
                    }
                }
        )
    }
```

Bottom bar UI control panel:
```kotlin
    EditorTool.PERSPECTIVE -> {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Perspective Crop & Warp", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            cornerTL = Offset(0.04f, 0.04f)
                            cornerTR = Offset(0.96f, 0.04f)
                            cornerBR = Offset(0.96f, 0.96f)
                            cornerBL = Offset(0.04f, 0.96f)
                        },
                        border = BorderStroke(1.dp, colors.cardBorder),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Reset Pins", color = colors.textPrimary, fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            val base = previewBaseBitmap
                            if (base != null) {
                                val warped = applyPerspectiveCrop(base, cornerTL, cornerTR, cornerBR, cornerBL)
                                previewBaseBitmap = warped
                                hasPerspectiveCropped = true
                                strokes.clear()
                                redoStrokes.clear()
                                cornerTL = Offset(0.04f, 0.04f)
                                cornerTR = Offset(0.96f, 0.04f)
                                cornerBR = Offset(0.96f, 0.96f)
                                cornerBL = Offset(0.04f, 0.96f)
                                Toast.makeText(activity, "Perspective corrected & cropped!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text("Apply Crop", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Text("Drag the 4 corner pins to align with the 4 document corners.", color = colors.textMuted, fontSize = 11.sp)
        }
    }
```

Perspective warp calculation function:
```kotlin
/**
 * 4-Corner perspective projective warp crop using Matrix.setPolyToPoly.
 */
fun applyPerspectiveCrop(bitmap: Bitmap, tl: Offset, tr: Offset, br: Offset, bl: Offset): Bitmap {
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()

    val pTL = Offset(tl.x * w, tl.y * h)
    val pTR = Offset(tr.x * w, tr.y * h)
    val pBR = Offset(br.x * w, br.y * h)
    val pBL = Offset(bl.x * w, bl.y * h)

    val topW = kotlin.math.hypot(pTR.x - pTL.x, pTR.y - pTL.y)
    val botW = kotlin.math.hypot(pBR.x - pBL.x, pBR.y - pBL.y)
    val targetW = maxOf(topW, botW).toInt().coerceAtLeast(100)

    val leftH = kotlin.math.hypot(pBL.x - pTL.x, pBL.y - pTL.y)
    val rightH = kotlin.math.hypot(pBR.x - pTR.x, pBR.y - pTR.y)
    val targetH = maxOf(leftH, rightH).toInt().coerceAtLeast(100)

    val src = floatArrayOf(
        pTL.x, pTL.y,
        pTR.x, pTR.y,
        pBR.x, pBR.y,
        pBL.x, pBL.y
    )

    val dst = floatArrayOf(
        0f, 0f,
        targetW.toFloat(), 0f,
        targetW.toFloat(), targetH.toFloat(),
        0f, targetH.toFloat()
    )

    val matrix = Matrix()
    matrix.setPolyToPoly(src, 0, dst, 0, 4)

    val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(bitmap, matrix, paint)
    return output
}
```

Save parameter and branch:
```kotlin
hasPerspectiveCrop: Boolean = false
...
val fullResBitmap = if (!hasPerspectiveCrop) {
    IdCardGenerator.decodeUriWithExif(activity, Uri.fromFile(file), 4000, 4000)
        ?: BitmapFactory.decodeFile(file.absolutePath)
} else {
    null // saved from preview fallback
}
```


## Unit Test: app/src/test/java/com/example/ScanVault/NewFeaturesAndFixesUnitTest.kt
```kotlin
    @Test
    fun testPhase10e_perspectiveCropDimensionMath() {
        // Perspective quad: skewed document on table
        val tl = androidx.compose.ui.geometry.Offset(100f, 150f)
        val tr = androidx.compose.ui.geometry.Offset(800f, 120f)
        val br = androidx.compose.ui.geometry.Offset(850f, 950f)
        val bl = androidx.compose.ui.geometry.Offset(80f, 920f)

        val topW = kotlin.math.hypot(tr.x - tl.x, tr.y - tl.y)
        val botW = kotlin.math.hypot(br.x - bl.x, br.y - bl.y)
        val leftH = kotlin.math.hypot(bl.x - tl.x, bl.y - tl.y)
        val rightH = kotlin.math.hypot(br.x - tr.x, br.y - tr.y)

        val dstW = maxOf(topW, botW).toInt().coerceAtLeast(100)
        val dstH = maxOf(leftH, rightH).toInt().coerceAtLeast(100)

        assertTrue(dstW >= 700 && dstW <= 850)
        assertTrue(dstH >= 750 && dstH <= 850)
    }
```
