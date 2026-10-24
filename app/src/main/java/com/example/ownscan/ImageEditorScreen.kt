package com.example.ownscan

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// -------------------------------------------------------------
// ENUMS & MODELS
// -------------------------------------------------------------

enum class DocFilterMode(val label: String) {
    ORIGINAL("Original"),
    MAGIC("Magic Color"),
    BW("B&W"),
    GRAYSCALE("Grayscale"),
    CONTRAST_BOOST("Contrast Boost")
}

enum class EditorTool(val label: String, val icon: String) {
    FILTERS("Filters", "🎨"),
    CROP("Crop", "✂️"),
    DRAW("Pen", "✏️"),
    HIGHLIGHT("Highlight", "🖍️"),
    ERASER("Eraser", "🧽"),
    SIGNATURE("Signature", "✍️")
}

enum class CropAspectRatio(val label: String) {
    FREE("Free"),
    ORIGINAL("Original"),
    SQUARE("1:1"),
    RATIO_4_3("4:3"),
    A4("A4"),
    ID_CARD("ID Card")
}

enum class CropHandle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP, BOTTOM, LEFT, RIGHT,
    BODY, NONE
}

data class CropHistoryItem(
    val bitmap: Bitmap,
    val bakedRotation: Float,
    val appliedCropRectNorm: RectF?,
    val strokes: List<AnnotationStroke>
)

/**
 * Mathematically composes a child crop rect into an existing parent crop rect in normalized coordinates.
 */
fun combineCropRects(parent: RectF?, child: RectF): RectF {
    if (parent == null) return child
    val pw = parent.width()
    val ph = parent.height()
    val l = (parent.left + child.left * pw).coerceIn(0f, 1f)
    val t = (parent.top + child.top * ph).coerceIn(0f, 1f)
    val r = (parent.left + child.right * pw).coerceIn(l + 0.001f, 1f)
    val b = (parent.top + child.bottom * ph).coerceIn(t + 0.001f, 1f)
    return RectF(l, t, r, b)
}

/**
 * Returns the target aspect ratio (width / height) based on current bitmap dimensions and orientation.
 */
fun getRatioForOption(option: CropAspectRatio, bmpW: Int, bmpH: Int): Float? {
    val isPortrait = bmpW < bmpH
    return when (option) {
        CropAspectRatio.FREE -> null
        CropAspectRatio.ORIGINAL -> bmpW.toFloat() / bmpH.toFloat().coerceAtLeast(1f)
        CropAspectRatio.SQUARE -> 1f
        CropAspectRatio.RATIO_4_3 -> if (isPortrait) 3f / 4f else 4f / 3f
        CropAspectRatio.A4 -> if (isPortrait) 1f / 1.4142f else 1.4142f
        CropAspectRatio.ID_CARD -> if (isPortrait) 53.98f / 85.6f else 85.6f / 53.98f
    }
}

// -------------------------------------------------------------
// MAIN IMAGE EDITOR SCREEN
// -------------------------------------------------------------

@Composable
fun FullImageEditorScreen(
    activity: Activity,
    file: File,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val colors = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var activeTool by remember { mutableStateOf(EditorTool.FILTERS) }
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    var selectedFilter by remember { mutableStateOf(DocFilterMode.ORIGINAL) }
    var isSaving by remember { mutableStateOf(false) }
    var isCropping by remember { mutableStateOf(false) }

    // Zoom & Pan state (active in non-crop tools)
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }
    var isDraggingHandleOrSignature by remember { mutableStateOf(false) }

    var hasOriginalBackup by remember(file) {
        mutableStateOf(OriginalImageManager.hasOriginal(file))
    }

    // Downscaled base bitmap for fluid preview rendering (max dim 1600)
    var previewBaseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoadingPreview by remember { mutableStateOf(true) }

    LaunchedEffect(file) {
        isLoadingPreview = true
        withContext(Dispatchers.IO) {
            val sampled = IdCardGenerator.decodeUriWithExif(activity, Uri.fromFile(file), 1600, 1600)
                ?: BitmapFactory.decodeFile(file.absolutePath)
            previewBaseBitmap = sampled
        }
        isLoadingPreview = false
    }

    // Annotation state with Undo and Redo stacks
    val strokes = remember { mutableStateListOf<AnnotationStroke>() }
    val redoStrokes = remember { mutableStateListOf<AnnotationStroke>() }

    var currentDrawColor by remember { mutableStateOf(Color.Black) }
    var currentPenStrokeWidth by remember { mutableFloatStateOf(4f) }
    var isPenSliderActive by remember { mutableStateOf(false) }

    // Vibrant highlighter colors that blend multiplicatively over text
    val highlighterColors = listOf(
        Color(0xFFFFEB3B), // Bright Yellow
        Color(0xFF00E676), // Bright Green
        Color(0xFF00E5FF), // Bright Cyan
        Color(0xFFFF80AB)  // Vibrant Pink
    )
    var currentHighlightColor by remember { mutableStateOf(highlighterColors.first()) }
    var currentHighlightStrokeWidth by remember { mutableFloatStateOf(24f) }
    var isHighlightSliderActive by remember { mutableStateOf(false) }

    // Current gesture points for real-time stroke rendering
    val activeStrokePoints = remember { mutableStateListOf<Offset>() }

    // Phase 10c: Signature state
    var showSignaturePad by remember { mutableStateOf(false) }
    var showSignaturePicker by remember { mutableStateOf(false) }
    var savedSignaturesList by remember { mutableStateOf<List<SavedSignature>>(emptyList()) }
    var activePlacedSignature by remember { mutableStateOf<Bitmap?>(null) }
    var signaturePosNorm by remember { mutableStateOf(Offset(0.5f, 0.75f)) }
    var signatureScaleNorm by remember { mutableFloatStateOf(0.35f) }
    val placedSignatures = remember { mutableStateListOf<Pair<Bitmap, RectF>>() }


    // Crop state: survives configuration change / recomposition
    var cropRectLeftNorm by rememberSaveable { mutableFloatStateOf(0.05f) }
    var cropRectTopNorm by rememberSaveable { mutableFloatStateOf(0.05f) }
    var cropRectRightNorm by rememberSaveable { mutableFloatStateOf(0.95f) }
    var cropRectBottomNorm by rememberSaveable { mutableFloatStateOf(0.95f) }
    var selectedAspectRatio by rememberSaveable { mutableStateOf(CropAspectRatio.FREE) }

    // Full-resolution tracking: preserves high resolution on final save
    var appliedCropRectNorm by remember { mutableStateOf<RectF?>(null) }
    var bakedRotationDegrees by rememberSaveable { mutableFloatStateOf(0f) }

    // Undo / Redo history for crops
    val cropUndoStack = remember { mutableStateListOf<CropHistoryItem>() }
    val cropRedoStack = remember { mutableStateListOf<CropHistoryItem>() }

    // Display dimensions measured in viewport to ensure 1:1 preview-to-export scale
    var currentDisplayedWidthPx by remember { mutableFloatStateOf(0f) }
    var currentDisplayedHeightPx by remember { mutableFloatStateOf(0f) }

    val previewFilteredBitmap = remember(previewBaseBitmap, rotationDegrees, selectedFilter) {
        previewBaseBitmap?.let { renderProcessedBitmap(it, rotationDegrees, selectedFilter) }
    }

    // Helper to immediately adjust current crop rectangle when an aspect ratio is selected
    fun applyAspectRatioToCurrentRect(targetRatio: Float) {
        val imgW = currentDisplayedWidthPx
        val imgH = currentDisplayedHeightPx
        if (imgW <= 0f || imgH <= 0f) return

        val curNormW = (cropRectRightNorm - cropRectLeftNorm).coerceAtLeast(0.02f)
        val curNormH = (cropRectBottomNorm - cropRectTopNorm).coerceAtLeast(0.02f)
        val curAspect = (curNormW * imgW) / (curNormH * imgH)
        val centerX = (cropRectLeftNorm + cropRectRightNorm) / 2f
        val centerY = (cropRectTopNorm + cropRectBottomNorm) / 2f

        val newNormW: Float
        val newNormH: Float

        if (curAspect > targetRatio) {
            // Shrink width
            newNormH = curNormH
            newNormW = (newNormH * imgH * targetRatio) / imgW
        } else {
            // Shrink height
            newNormW = curNormW
            newNormH = (newNormW * imgW / targetRatio) / imgH
        }

        val clampedW = newNormW.coerceIn(0.05f, 1f)
        val clampedH = newNormH.coerceIn(0.05f, 1f)
        val halfW = clampedW / 2f
        val halfH = clampedH / 2f

        val left = (centerX - halfW).coerceIn(0f, 1f - clampedW)
        val top = (centerY - halfH).coerceIn(0f, 1f - clampedH)

        cropRectLeftNorm = left
        cropRectTopNorm = top
        cropRectRightNorm = left + clampedW
        cropRectBottomNorm = top + clampedH
    }

    // Applying rectangle crop
    val applyRectCrop: () -> Unit = {
        val base = previewBaseBitmap
        if (base != null && !isCropping) {
            isCropping = true
            coroutineScope.launch {
                // Key math: Compute pixel bounds strictly within bitmap boundaries
                val cropped = withContext(Dispatchers.Default) {
                    val bw = base.width
                    val bh = base.height
                    val left = (cropRectLeftNorm * bw).toInt().coerceIn(0, bw - 1)
                    val top = (cropRectTopNorm * bh).toInt().coerceIn(0, bh - 1)
                    val right = (cropRectRightNorm * bw).toInt().coerceIn(left + 1, bw)
                    val bottom = (cropRectBottomNorm * bh).toInt().coerceIn(top + 1, bh)
                    val width = (right - left).coerceIn(1, bw - left)
                    val height = (bottom - top).coerceIn(1, bh - top)
                    Bitmap.createBitmap(base, left, top, width, height)
                }

                // Push current state to undo history before applying crop
                cropUndoStack.add(
                    CropHistoryItem(
                        bitmap = base,
                        bakedRotation = bakedRotationDegrees,
                        appliedCropRectNorm = appliedCropRectNorm,
                        strokes = strokes.toList()
                    )
                )
                cropRedoStack.clear()

                // Chain cumulative normalized crop rect for full-resolution save
                val currentCropNorm = RectF(cropRectLeftNorm, cropRectTopNorm, cropRectRightNorm, cropRectBottomNorm)
                appliedCropRectNorm = combineCropRects(appliedCropRectNorm, currentCropNorm)

                previewBaseBitmap = cropped
                strokes.clear()
                redoStrokes.clear()

                // Reset crop rect for subsequent crops
                cropRectLeftNorm = 0.05f
                cropRectTopNorm = 0.05f
                cropRectRightNorm = 0.95f
                cropRectBottomNorm = 0.95f
                selectedAspectRatio = CropAspectRatio.FREE

                zoomScale = 1f
                panOffsetX = 0f
                panOffsetY = 0f
                isCropping = false
                Toast.makeText(activity, "Image cropped successfully!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Text("✕", fontSize = 20.sp, color = colors.textPrimary)
            }

            Text(
                text = "Edit Page",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Undo button: undos annotation strokes first, then undos image crops
                IconButton(
                    onClick = {
                        if (strokes.isNotEmpty()) {
                            val removed = strokes.removeAt(strokes.lastIndex)
                            redoStrokes.add(removed)
                        } else if (cropUndoStack.isNotEmpty()) {
                            val lastCrop = cropUndoStack.removeAt(cropUndoStack.lastIndex)
                            val curBmp = previewBaseBitmap
                            if (curBmp != null) {
                                cropRedoStack.add(
                                    CropHistoryItem(
                                        bitmap = curBmp,
                                        bakedRotation = bakedRotationDegrees,
                                        appliedCropRectNorm = appliedCropRectNorm,
                                        strokes = strokes.toList()
                                    )
                                )
                            }
                            previewBaseBitmap = lastCrop.bitmap
                            bakedRotationDegrees = lastCrop.bakedRotation
                            appliedCropRectNorm = lastCrop.appliedCropRectNorm
                            strokes.clear()
                            strokes.addAll(lastCrop.strokes)
                            redoStrokes.clear()
                            cropRectLeftNorm = 0.05f
                            cropRectTopNorm = 0.05f
                            cropRectRightNorm = 0.95f
                            cropRectBottomNorm = 0.95f
                            selectedAspectRatio = CropAspectRatio.FREE
                            zoomScale = 1f
                            panOffsetX = 0f
                            panOffsetY = 0f
                            Toast.makeText(activity, "Crop undone", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = strokes.isNotEmpty() || cropUndoStack.isNotEmpty()
                ) {
                    Text("↩️", fontSize = 16.sp)
                }

                // Redo button: redos annotation strokes or image crops
                IconButton(
                    onClick = {
                        if (redoStrokes.isNotEmpty()) {
                            val restored = redoStrokes.removeAt(redoStrokes.lastIndex)
                            strokes.add(restored)
                        } else if (cropRedoStack.isNotEmpty()) {
                            val nextCrop = cropRedoStack.removeAt(cropRedoStack.lastIndex)
                            val curBmp = previewBaseBitmap
                            if (curBmp != null) {
                                cropUndoStack.add(
                                    CropHistoryItem(
                                        bitmap = curBmp,
                                        bakedRotation = bakedRotationDegrees,
                                        appliedCropRectNorm = appliedCropRectNorm,
                                        strokes = strokes.toList()
                                    )
                                )
                            }
                            previewBaseBitmap = nextCrop.bitmap
                            bakedRotationDegrees = nextCrop.bakedRotation
                            appliedCropRectNorm = nextCrop.appliedCropRectNorm
                            strokes.clear()
                            strokes.addAll(nextCrop.strokes)
                            redoStrokes.clear()
                            cropRectLeftNorm = 0.05f
                            cropRectTopNorm = 0.05f
                            cropRectRightNorm = 0.95f
                            cropRectBottomNorm = 0.95f
                            selectedAspectRatio = CropAspectRatio.FREE
                            zoomScale = 1f
                            panOffsetX = 0f
                            panOffsetY = 0f
                            Toast.makeText(activity, "Crop redone", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = redoStrokes.isNotEmpty() || cropRedoStack.isNotEmpty()
                ) {
                    Text("↪️", fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.width(4.dp))

                Button(
                    onClick = {
                        if (previewFilteredBitmap != null && !isSaving) {
                            isSaving = true
                            coroutineScope.launch {
                                val currentPlaced = placedSignatures.toList() + if (activePlacedSignature != null) {
                                    val sigW = signatureScaleNorm
                                    val sigAspect = activePlacedSignature!!.height.toFloat() / activePlacedSignature!!.width.toFloat().coerceAtLeast(1f)
                                    val sigH = sigW * sigAspect * (currentDisplayedWidthPx / currentDisplayedHeightPx.coerceAtLeast(1f))
                                    val r = RectF(
                                        (signaturePosNorm.x - sigW / 2f).coerceIn(0f, 1f),
                                        (signaturePosNorm.y - sigH / 2f).coerceIn(0f, 1f),
                                        (signaturePosNorm.x + sigW / 2f).coerceIn(0f, 1f),
                                        (signaturePosNorm.y + sigH / 2f).coerceIn(0f, 1f)
                                    )
                                    listOf(activePlacedSignature!! to r)
                                } else emptyList()

                                val success = withContext(Dispatchers.IO) {
                                    saveEditorChanges(
                                        activity = activity,
                                        file = file,
                                        rotationDegrees = rotationDegrees,
                                        selectedFilter = selectedFilter,
                                        strokes = strokes.toList(),
                                        referenceDisplayWidth = currentDisplayedWidthPx,
                                        previewFallback = previewFilteredBitmap,
                                        placedSignatures = currentPlaced,
                                        appliedCropRectNorm = appliedCropRectNorm,
                                        bakedRotationDegrees = bakedRotationDegrees
                                    )
                                }
                                isSaving = false
                                if (success) {
                                    Toast.makeText(activity, "Changes saved successfully!", Toast.LENGTH_SHORT).show()
                                    onSaved()
                                } else {
                                    Toast.makeText(activity, "Failed to save changes", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    enabled = !isSaving && !isLoadingPreview && previewFilteredBitmap != null && !isCropping
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Main Viewport
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isLoadingPreview || previewFilteredBitmap == null) {
                    CircularProgressIndicator(color = colors.accent)
                } else {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val containerWidthPx = constraints.maxWidth.toFloat()
                        val containerHeightPx = constraints.maxHeight.toFloat()
                        val bmpW = previewFilteredBitmap.width.toFloat()
                        val bmpH = previewFilteredBitmap.height.toFloat()

                        // Letterbox & coordinate calculation: Fit image inside container
                        val fitRatio = minOf(containerWidthPx / bmpW, containerHeightPx / bmpH)
                        val displayedWidthPx = bmpW * fitRatio
                        val displayedHeightPx = bmpH * fitRatio
                        val letterboxOffsetX = (containerWidthPx - displayedWidthPx) / 2f
                        val letterboxOffsetY = (containerHeightPx - displayedHeightPx) / 2f

                        val displayedImageRect = RectF(
                            letterboxOffsetX,
                            letterboxOffsetY,
                            letterboxOffsetX + displayedWidthPx,
                            letterboxOffsetY + displayedHeightPx
                        )

                        currentDisplayedWidthPx = displayedWidthPx
                        currentDisplayedHeightPx = displayedHeightPx

                        fun mapToNorm(viewportOffset: Offset): Offset {
                            val normX = ((viewportOffset.x - panOffsetX) / (displayedWidthPx * zoomScale) + 0.5f * (1f - 1f / zoomScale)).coerceIn(0f, 1f)
                            val normY = ((viewportOffset.y - panOffsetY) / (displayedHeightPx * zoomScale) + 0.5f * (1f - 1f / zoomScale)).coerceIn(0f, 1f)
                            return Offset(normX, normY)
                        }

                        // Inner document rendering Box
                        Box(
                            modifier = Modifier
                                .size(
                                    width = with(density) { displayedWidthPx.toDp() },
                                    height = with(density) { displayedHeightPx.toDp() }
                                )
                                .graphicsLayer(
                                    scaleX = if (activeTool == EditorTool.CROP) 1f else zoomScale,
                                    scaleY = if (activeTool == EditorTool.CROP) 1f else zoomScale,
                                    translationX = if (activeTool == EditorTool.CROP) 0f else panOffsetX,
                                    translationY = if (activeTool == EditorTool.CROP) 0f else panOffsetY
                                )
                                .pointerInput(activeTool, zoomScale, panOffsetX, panOffsetY) {
                                    if (activeTool == EditorTool.CROP) {
                                        // Gestures in crop tool are exclusively handled by CropOverlay
                                        return@pointerInput
                                    }
                                    if (activeTool == EditorTool.FILTERS || activeTool == EditorTool.SIGNATURE) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            do {
                                                val event = awaitPointerEvent()
                                                val activePointers = event.changes.filter { it.pressed }
                                                if (activePointers.size > 1) {
                                                    val zoom = event.calculateZoom()
                                                    val pan = event.calculatePan()
                                                    if (zoom != 1f) {
                                                        val zoomMultiplier = 1.6f
                                                        val effectiveZoom = 1f + (zoom - 1f) * zoomMultiplier
                                                        val newScale = (zoomScale * effectiveZoom).coerceIn(1f, 6f)
                                                        zoomScale = newScale
                                                        event.changes.forEach { it.consume() }
                                                    }
                                                    if (zoomScale > 1.05f) {
                                                        val panMultiplier = 1.3f
                                                        val maxPanX = ((displayedWidthPx * (zoomScale - 1f)) / 2f + 80f).coerceAtLeast(0f)
                                                        val maxPanY = ((displayedHeightPx * (zoomScale - 1f)) / 2f + 80f).coerceAtLeast(0f)
                                                        panOffsetX = (panOffsetX + pan.x * panMultiplier).coerceIn(-maxPanX, maxPanX)
                                                        panOffsetY = (panOffsetY + pan.y * panMultiplier).coerceIn(-maxPanY, maxPanY)
                                                        event.changes.forEach { it.consume() }
                                                    } else {
                                                        panOffsetX = 0f
                                                        panOffsetY = 0f
                                                    }
                                                } else if (activePointers.size == 1 && !isDraggingHandleOrSignature) {
                                                    val pointer = activePointers.first()
                                                    if (zoomScale > 1.05f) {
                                                        val pan = pointer.position - pointer.previousPosition
                                                        if (pan.getDistance() > 0.5f) {
                                                            val panMultiplier = 1.3f
                                                            val maxPanX = ((displayedWidthPx * (zoomScale - 1f)) / 2f + 80f).coerceAtLeast(0f)
                                                            val maxPanY = ((displayedHeightPx * (zoomScale - 1f)) / 2f + 80f).coerceAtLeast(0f)
                                                            panOffsetX = (panOffsetX + pan.x * panMultiplier).coerceIn(-maxPanX, maxPanX)
                                                            panOffsetY = (panOffsetY + pan.y * panMultiplier).coerceIn(-maxPanY, maxPanY)
                                                            pointer.consume()
                                                        }
                                                    }
                                                }
                                            } while (activePointers.isNotEmpty())
                                        }
                                    } else {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            var isMultiTouch = false
                                            val downNorm = mapToNorm(down.position)

                                            if (activeTool == EditorTool.ERASER) {
                                                val hit = strokes.findLast { isStrokeHit(it, downNorm, 0.045f) }
                                                if (hit != null) {
                                                    strokes.remove(hit)
                                                    redoStrokes.add(hit)
                                                }
                                            } else {
                                                activeStrokePoints.add(downNorm)
                                            }

                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val activePointers = event.changes.filter { it.pressed }
                                                if (activePointers.isEmpty()) break

                                                if (activePointers.size > 1) {
                                                    isMultiTouch = true
                                                    activeStrokePoints.clear()

                                                    val zoom = event.calculateZoom()
                                                    val pan = event.calculatePan()
                                                    val zoomMultiplier = 1.6f
                                                    val effectiveZoom = 1f + (zoom - 1f) * zoomMultiplier
                                                    val newScale = (zoomScale * effectiveZoom).coerceIn(1f, 6f)
                                                    zoomScale = newScale
                                                    if (newScale > 1.05f) {
                                                        val panMultiplier = 1.45f
                                                        val maxPanX = ((displayedWidthPx * (newScale - 1f)) / 2f).coerceAtLeast(0f)
                                                        val maxPanY = ((displayedHeightPx * (newScale - 1f)) / 2f).coerceAtLeast(0f)
                                                        panOffsetX = (panOffsetX + pan.x * panMultiplier).coerceIn(-maxPanX, maxPanX)
                                                        panOffsetY = (panOffsetY + pan.y * panMultiplier).coerceIn(-maxPanY, maxPanY)
                                                    } else {
                                                        panOffsetX = 0f
                                                        panOffsetY = 0f
                                                    }
                                                    event.changes.forEach { it.consume() }
                                                } else if (!isMultiTouch) {
                                                    val pointer = activePointers.first()
                                                    pointer.consume()
                                                    val normPt = mapToNorm(pointer.position)

                                                    if (activeTool == EditorTool.ERASER) {
                                                        val hit = strokes.findLast { isStrokeHit(it, normPt, 0.045f) }
                                                        if (hit != null) {
                                                            strokes.remove(hit)
                                                            redoStrokes.add(hit)
                                                        }
                                                    } else {
                                                        activeStrokePoints.add(normPt)
                                                    }
                                                }
                                            }

                                            if (!isMultiTouch && activeStrokePoints.isNotEmpty() && activeTool != EditorTool.ERASER) {
                                                val isHighlight = activeTool == EditorTool.HIGHLIGHT
                                                val strokeColor = if (isHighlight) currentHighlightColor else currentDrawColor
                                                val strokeWidth = if (isHighlight) currentHighlightStrokeWidth else currentPenStrokeWidth
                                                strokes.add(
                                                    AnnotationStroke(
                                                        points = activeStrokePoints.toList(),
                                                        color = strokeColor,
                                                        strokeWidth = strokeWidth,
                                                        isHighlighter = isHighlight
                                                    )
                                                )
                                                redoStrokes.clear()
                                                activeStrokePoints.clear()
                                            }
                                        }
                                    }
                                }
                                .pointerInput(activeTool) {
                                    if (activeTool == EditorTool.FILTERS || activeTool == EditorTool.SIGNATURE) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                if (!isDraggingHandleOrSignature) {
                                                    if (zoomScale > 1.05f) {
                                                        zoomScale = 1f
                                                        panOffsetX = 0f
                                                        panOffsetY = 0f
                                                    } else {
                                                        zoomScale = 2.5f
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                        ) {
                            // Document Bitmap
                            Image(
                                bitmap = previewFilteredBitmap.asImageBitmap(),
                                contentDescription = "Document Page",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds
                            )

                            // Stamped signatures
                            placedSignatures.forEach { (sigBmp, rectNorm) ->
                                val leftPx = rectNorm.left * displayedWidthPx
                                val topPx = rectNorm.top * displayedHeightPx
                                val wPx = rectNorm.width() * displayedWidthPx
                                val hPx = rectNorm.height() * displayedHeightPx
                                Image(
                                    bitmap = sigBmp.asImageBitmap(),
                                    contentDescription = "Stamped Signature",
                                    modifier = Modifier
                                        .offset(
                                            x = with(density) { leftPx.toDp() },
                                            y = with(density) { topPx.toDp() }
                                        )
                                        .size(
                                            width = with(density) { wPx.toDp() },
                                            height = with(density) { hPx.toDp() }
                                        )
                                )
                            }

                            // Strokes Canvas (Pen, Dots & Multiply Blend Highlighter)
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                strokes.forEach { stroke ->
                                    val blend = if (stroke.isHighlighter) BlendMode.Multiply else BlendMode.SrcOver
                                    if (stroke.points.size == 1) {
                                        val pt = stroke.points[0]
                                        drawCircle(
                                            color = stroke.color,
                                            radius = stroke.strokeWidth / 2f,
                                            center = Offset(pt.x * size.width, pt.y * size.height),
                                            blendMode = blend
                                        )
                                    } else if (stroke.points.size > 1) {
                                        for (i in 0 until stroke.points.size - 1) {
                                            drawLine(
                                                color = stroke.color,
                                                start = Offset(stroke.points[i].x * size.width, stroke.points[i].y * size.height),
                                                end = Offset(stroke.points[i + 1].x * size.width, stroke.points[i + 1].y * size.height),
                                                strokeWidth = stroke.strokeWidth,
                                                cap = StrokeCap.Round,
                                                blendMode = blend
                                            )
                                        }
                                    }
                                }

                                if (activeStrokePoints.isNotEmpty()) {
                                    val isHighlight = activeTool == EditorTool.HIGHLIGHT
                                    val strokeColor = if (isHighlight) currentHighlightColor else currentDrawColor
                                    val strokeWidth = if (isHighlight) currentHighlightStrokeWidth else currentPenStrokeWidth
                                    val blend = if (isHighlight) BlendMode.Multiply else BlendMode.SrcOver

                                    if (activeStrokePoints.size == 1) {
                                        val pt = activeStrokePoints[0]
                                        drawCircle(
                                            color = strokeColor,
                                            radius = strokeWidth / 2f,
                                            center = Offset(pt.x * size.width, pt.y * size.height),
                                            blendMode = blend
                                        )
                                    } else {
                                        for (i in 0 until activeStrokePoints.size - 1) {
                                            drawLine(
                                                color = strokeColor,
                                                start = Offset(activeStrokePoints[i].x * size.width, activeStrokePoints[i].y * size.height),
                                                end = Offset(activeStrokePoints[i + 1].x * size.width, activeStrokePoints[i + 1].y * size.height),
                                                strokeWidth = strokeWidth,
                                                cap = StrokeCap.Round,
                                                blendMode = blend
                                            )
                                        }
                                    }
                                }
                            }

                            // Active draggable signature
                            if (activePlacedSignature != null) {
                                val sigW = signatureScaleNorm * displayedWidthPx
                                val sigAspect = activePlacedSignature!!.height.toFloat() / activePlacedSignature!!.width.toFloat().coerceAtLeast(1f)
                                val sigH = sigW * sigAspect
                                val leftPx = (signaturePosNorm.x * displayedWidthPx) - (sigW / 2f)
                                val topPx = (signaturePosNorm.y * displayedHeightPx) - (sigH / 2f)

                                Box(
                                    modifier = Modifier
                                        .offset(
                                            x = with(density) { leftPx.toDp() },
                                            y = with(density) { topPx.toDp() }
                                        )
                                        .size(
                                            width = with(density) { sigW.toDp() },
                                            height = with(density) { sigH.toDp() }
                                        )
                                        .border(1.5.dp, colors.accent, RoundedCornerShape(4.dp))
                                        .background(colors.accent.copy(alpha = 0.08f))
                                        .pointerInput(displayedWidthPx, displayedHeightPx) {
                                            detectDragGestures(
                                                onDragStart = { isDraggingHandleOrSignature = true },
                                                onDragEnd = { isDraggingHandleOrSignature = false },
                                                onDragCancel = { isDraggingHandleOrSignature = false }
                                            ) { change, dragAmount ->
                                                change.consume()
                                                val newNormX = (signaturePosNorm.x + (dragAmount.x / displayedWidthPx)).coerceIn(0.1f, 0.9f)
                                                val newNormY = (signaturePosNorm.y + (dragAmount.y / displayedHeightPx)).coerceIn(0.1f, 0.9f)
                                                signaturePosNorm = Offset(newNormX, newNormY)
                                            }
                                        }
                                ) {
                                    Image(
                                        bitmap = activePlacedSignature!!.asImageBitmap(),
                                        contentDescription = "Active Signature",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                        }

                        // Reliable Crop Overlay: Covers full container to handle letterboxing seamlessly
                        if (activeTool == EditorTool.CROP) {
                            val activeRatio = previewBaseBitmap?.let {
                                getRatioForOption(selectedAspectRatio, it.width, it.height)
                            }
                            CropOverlay(
                                displayedImageRect = displayedImageRect,
                                cropLeftNorm = cropRectLeftNorm,
                                cropTopNorm = cropRectTopNorm,
                                cropRightNorm = cropRectRightNorm,
                                cropBottomNorm = cropRectBottomNorm,
                                aspectRatio = activeRatio,
                                onCropChange = { l, t, r, b ->
                                    cropRectLeftNorm = l
                                    cropRectTopNorm = t
                                    cropRectRightNorm = r
                                    cropRectBottomNorm = b
                                },
                                onDragStateChange = { isDragging ->
                                    isDraggingHandleOrSignature = isDragging
                                },
                                accentColor = colors.accent,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // Cropping progress indicator
                if (isCropping) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surface,
                            border = BorderStroke(1.dp, colors.cardBorder),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                Text("Cropping image...", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                // Reset Zoom pill when zoomed in (non-crop tools)
                if (activeTool != EditorTool.CROP && zoomScale > 1.05f) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clickable {
                                zoomScale = 1f
                                panOffsetX = 0f
                                panOffsetY = 0f
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🔍 ${(zoomScale * 10).toInt() / 10f}x • Reset",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Stroke size preview circle
                if (isPenSliderActive || isHighlightSliderActive) {
                    val sizeVal = if (isPenSliderActive) currentPenStrokeWidth else currentHighlightStrokeWidth
                    val circleColor = if (isPenSliderActive) currentDrawColor else currentHighlightColor

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(sizeVal.dp.coerceIn(6.dp, 80.dp))
                                    .clip(CircleShape)
                                    .background(circleColor)
                                    .border(2.dp, Color.White, CircleShape)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${sizeVal.toInt()} px",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Bottom Tools Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .border(1.dp, colors.divider, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(14.dp)
        ) {
            when (activeTool) {
                EditorTool.FILTERS -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Filters & Rotation", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (hasOriginalBackup) {
                                OutlinedButton(
                                    onClick = {
                                        if (OriginalImageManager.revertToOriginal(file)) {
                                            previewBaseBitmap = IdCardGenerator.decodeUriWithExif(activity, Uri.fromFile(file), 1600, 1600)
                                                ?: BitmapFactory.decodeFile(file.absolutePath)
                                            rotationDegrees = 0f
                                            bakedRotationDegrees = 0f
                                            appliedCropRectNorm = null
                                            cropUndoStack.clear()
                                            cropRedoStack.clear()
                                            cropRectLeftNorm = 0.05f
                                            cropRectTopNorm = 0.05f
                                            cropRectRightNorm = 0.95f
                                            cropRectBottomNorm = 0.95f
                                            selectedAspectRatio = CropAspectRatio.FREE
                                            selectedFilter = DocFilterMode.ORIGINAL
                                            strokes.clear()
                                            redoStrokes.clear()
                                            zoomScale = 1f
                                            panOffsetX = 0f
                                            panOffsetY = 0f
                                            hasOriginalBackup = OriginalImageManager.hasOriginal(file)
                                            thumbnailMemoryCache.evictAll()
                                            Toast.makeText(activity, "Reverted to original image!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    border = BorderStroke(1.dp, colors.accentAmber),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("↺ Revert Original", color = colors.accentAmber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    rotationDegrees = (rotationDegrees + 90f) % 360f
                                    val rotatedStrokes = strokes.map { s ->
                                        s.copy(points = s.points.map { pt ->
                                            Offset((1f - pt.y).coerceIn(0f, 1f), pt.x.coerceIn(0f, 1f))
                                        })
                                    }
                                    strokes.clear()
                                    strokes.addAll(rotatedStrokes)

                                    val rotatedRedo = redoStrokes.map { s ->
                                        s.copy(points = s.points.map { pt ->
                                            Offset((1f - pt.y).coerceIn(0f, 1f), pt.x.coerceIn(0f, 1f))
                                        })
                                    }
                                    redoStrokes.clear()
                                    redoStrokes.addAll(rotatedRedo)

                                    // Reset crop handles to fit rotated dimensions
                                    cropRectLeftNorm = 0.05f
                                    cropRectTopNorm = 0.05f
                                    cropRectRightNorm = 0.95f
                                    cropRectBottomNorm = 0.95f
                                    selectedAspectRatio = CropAspectRatio.FREE

                                    zoomScale = 1f
                                    panOffsetX = 0f
                                    panOffsetY = 0f
                                },
                                border = BorderStroke(1.dp, colors.cardBorder),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("↻ Rotate 90°", color = colors.textPrimary, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(DocFilterMode.values()) { mode ->
                            FilterTabButton(
                                label = mode.label,
                                isSelected = selectedFilter == mode,
                                onClick = { selectedFilter = mode }
                            )
                        }
                    }
                }

                EditorTool.CROP -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Crop Image", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = {
                                        cropRectLeftNorm = 0.05f
                                        cropRectTopNorm = 0.05f
                                        cropRectRightNorm = 0.95f
                                        cropRectBottomNorm = 0.95f
                                        val bmp = previewBaseBitmap
                                        if (bmp != null) {
                                            val r = getRatioForOption(selectedAspectRatio, bmp.width, bmp.height)
                                            if (r != null) applyAspectRatioToCurrentRect(r)
                                        }
                                    },
                                    border = BorderStroke(1.dp, colors.cardBorder),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Reset", color = colors.textPrimary, fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        cropRectLeftNorm = 0.05f
                                        cropRectTopNorm = 0.05f
                                        cropRectRightNorm = 0.95f
                                        cropRectBottomNorm = 0.95f
                                        selectedAspectRatio = CropAspectRatio.FREE
                                        activeTool = EditorTool.FILTERS
                                    },
                                    border = BorderStroke(1.dp, colors.cardBorder),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Cancel", color = colors.textMuted, fontSize = 11.sp)
                                }

                                Button(
                                    onClick = { applyRectCrop() },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                    enabled = !isCropping && previewBaseBitmap != null
                                ) {
                                    if (isCropping) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text("✓ Apply", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Aspect ratio selector row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CropAspectRatio.values().forEach { option ->
                                val isSelected = selectedAspectRatio == option
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) colors.accent else colors.cardSubtle,
                                    border = BorderStroke(1.dp, if (isSelected) colors.accent else colors.cardBorder),
                                    modifier = Modifier.clickable {
                                        selectedAspectRatio = option
                                        val bmp = previewBaseBitmap
                                        if (bmp != null) {
                                            val r = getRatioForOption(option, bmp.width, bmp.height)
                                            if (r != null) {
                                                applyAspectRatioToCurrentRect(r)
                                            }
                                        }
                                    }
                                ) {
                                    Text(
                                        text = option.label,
                                        color = if (isSelected) Color.White else colors.textPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                EditorTool.DRAW -> {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Pen Color:", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                listOf(Color.Black, Color.Blue, Color.Red, Color(0xFF2E7D32)).forEach { col ->
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(col)
                                            .border(2.dp, if (currentDrawColor == col) colors.accent else Color.Transparent, CircleShape)
                                            .clickable { currentDrawColor = col }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Size: ${currentPenStrokeWidth.toInt()} px",
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.width(75.dp)
                            )
                            Slider(
                                value = currentPenStrokeWidth,
                                onValueChange = {
                                    currentPenStrokeWidth = it
                                    isPenSliderActive = true
                                },
                                onValueChangeFinished = {
                                    isPenSliderActive = false
                                },
                                valueRange = 2f..24f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = currentDrawColor,
                                    activeTrackColor = colors.accent
                                )
                            )
                        }
                    }
                }

                EditorTool.HIGHLIGHT -> {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Highlighter Color:", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                highlighterColors.forEach { col ->
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(col)
                                            .border(2.dp, if (currentHighlightColor == col) colors.accent else Color.Transparent, CircleShape)
                                            .clickable { currentHighlightColor = col }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Size: ${currentHighlightStrokeWidth.toInt()} px",
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.width(75.dp)
                            )
                            Slider(
                                value = currentHighlightStrokeWidth,
                                onValueChange = {
                                    currentHighlightStrokeWidth = it
                                    isHighlightSliderActive = true
                                },
                                onValueChangeFinished = {
                                    isHighlightSliderActive = false
                                },
                                valueRange = 10f..60f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = currentHighlightColor,
                                    activeTrackColor = colors.accent
                                )
                            )
                        }
                    }
                }

                EditorTool.ERASER -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Eraser: Tap or drag over strokes to remove", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (strokes.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    redoStrokes.addAll(strokes)
                                    strokes.clear()
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Clear All", color = colors.accentRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                EditorTool.SIGNATURE -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Signature Tool", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            savedSignaturesList = SignatureEngine.getSavedSignatures(activity)
                                            if (savedSignaturesList.isNotEmpty()) {
                                                showSignaturePicker = true
                                            } else {
                                                showSignaturePad = true
                                            }
                                        }
                                    },
                                    border = BorderStroke(1.dp, colors.accent),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Text("+ Add Signature", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (activePlacedSignature != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Text("Size: ", color = colors.textMuted, fontSize = 11.sp)
                                    Slider(
                                        value = signatureScaleNorm,
                                        onValueChange = { signatureScaleNorm = it },
                                        valueRange = 0.15f..0.7f,
                                        modifier = Modifier.weight(1f),
                                        colors = SliderDefaults.colors(
                                            thumbColor = colors.accent,
                                            activeTrackColor = colors.accent
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Button(
                                    onClick = {
                                        val sigW = signatureScaleNorm
                                        val sigAspect = activePlacedSignature!!.height.toFloat() / activePlacedSignature!!.width.toFloat().coerceAtLeast(1f)
                                        val sigH = sigW * sigAspect * (currentDisplayedWidthPx / currentDisplayedHeightPx.coerceAtLeast(1f))
                                        val r = RectF(
                                            (signaturePosNorm.x - sigW / 2f).coerceIn(0f, 1f),
                                            (signaturePosNorm.y - sigH / 2f).coerceIn(0f, 1f),
                                            (signaturePosNorm.x + sigW / 2f).coerceIn(0f, 1f),
                                            (signaturePosNorm.y + sigH / 2f).coerceIn(0f, 1f)
                                        )
                                        placedSignatures.add(activePlacedSignature!! to r)
                                        activePlacedSignature = null
                                        Toast.makeText(activity, "Signature stamped!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("✓ Stamp", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                TextButton(
                                    onClick = { activePlacedSignature = null },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Remove", color = colors.accentRed, fontSize = 11.sp)
                                }
                            }
                        } else {
                            Text("Drag to reposition signature on page. Tap ✓ Stamp to finalize.", color = colors.textMuted, fontSize = 11.sp)
                        }
                    }
                }

            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tool Switcher Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EditorTool.values().forEach { tool ->
                    val isSelected = activeTool == tool
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) colors.accent else colors.cardSubtle,
                        border = BorderStroke(1.dp, if (isSelected) colors.accent else colors.cardBorder),
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (tool == EditorTool.CROP && activeTool != EditorTool.CROP) {
                                    // Apply rotation to the bitmap first so crop rectangle matches what user sees
                                    val currentBmp = previewBaseBitmap
                                    if (currentBmp != null && rotationDegrees != 0f) {
                                        val rotated = renderProcessedBitmap(currentBmp, rotationDegrees, DocFilterMode.ORIGINAL)
                                        previewBaseBitmap = rotated
                                        bakedRotationDegrees = (bakedRotationDegrees + rotationDegrees) % 360f
                                        rotationDegrees = 0f
                                    }
                                    // Reset zoom/pan when entering crop tool
                                    zoomScale = 1f
                                    panOffsetX = 0f
                                    panOffsetY = 0f
                                }
                                activeTool = tool
                            }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp)
                        ) {
                            Text(tool.icon, fontSize = 14.sp)
                            Text(
                                text = tool.label,
                                color = if (isSelected) Color.White else colors.textPrimary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSignaturePad) {
        SignaturePadDialog(
            colors = colors,
            onDismiss = { showSignaturePad = false },
            onSignatureConfirmed = { bmp, saveToLib ->
                showSignaturePad = false
                activePlacedSignature = bmp
                signaturePosNorm = Offset(0.5f, 0.75f)
                activeTool = EditorTool.SIGNATURE
                if (saveToLib) {
                    coroutineScope.launch {
                        SignatureEngine.saveSignature(
                            activity,
                            bmp,
                            "Signature ${SimpleDateFormat("ddMMM HH:mm", Locale.getDefault()).format(Date())}"
                        )
                    }
                }
            }
        )
    }

    if (showSignaturePicker) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showSignaturePicker = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✍️", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose Signature", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Select a saved signature or draw a new one:", color = colors.textMuted, fontSize = 13.sp)

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(savedSignaturesList) { sig ->
                            val sigBmp = remember(sig.id) { SignatureEngine.loadSignatureBitmap(sig.file) }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = colors.cardSubtle,
                                border = BorderStroke(1.dp, colors.cardBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSignaturePicker = false
                                        if (sigBmp != null) {
                                            activePlacedSignature = sigBmp
                                            signaturePosNorm = Offset(0.5f, 0.75f)
                                            activeTool = EditorTool.SIGNATURE
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (sigBmp != null) {
                                        Image(
                                            bitmap = sigBmp.asImageBitmap(),
                                            contentDescription = sig.name,
                                            modifier = Modifier
                                                .height(44.dp)
                                                .width(100.dp)
                                                .background(Color.White, RoundedCornerShape(4.dp))
                                                .padding(2.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                        Text(sig.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text(
                                            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(sig.timestamp)),
                                            color = colors.textMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                SignatureEngine.deleteSignature(activity, sig.id)
                                                savedSignaturesList = SignatureEngine.getSavedSignatures(activity)
                                            }
                                        }
                                    ) {
                                        Text("🗑️", fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSignaturePicker = false
                        showSignaturePad = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("+ Draw New Signature", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignaturePicker = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }
}

// -------------------------------------------------------------
// FILTER BUTTON COMPONENT
// -------------------------------------------------------------

@Composable
fun FilterTabButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) colors.accent else colors.cardSubtle,
        border = BorderStroke(1.dp, if (isSelected) colors.accent else colors.cardBorder),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

// -------------------------------------------------------------
// RELIABLE CROP OVERLAY COMPONENT
// -------------------------------------------------------------

@Composable
fun CropOverlay(
    displayedImageRect: RectF,
    cropLeftNorm: Float,
    cropTopNorm: Float,
    cropRightNorm: Float,
    cropBottomNorm: Float,
    aspectRatio: Float?,
    onCropChange: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var activeHandle by remember { mutableStateOf(CropHandle.NONE) }

    Canvas(
        modifier = modifier.pointerInput(displayedImageRect, cropLeftNorm, cropTopNorm, cropRightNorm, cropBottomNorm, aspectRatio) {
            val imgW = displayedImageRect.width()
            val imgH = displayedImageRect.height()
            if (imgW <= 0f || imgH <= 0f) return@pointerInput

            detectDragGestures(
                onDragStart = { offset ->
                    val cropL = displayedImageRect.left + cropLeftNorm * imgW
                    val cropT = displayedImageRect.top + cropTopNorm * imgH
                    val cropR = displayedImageRect.left + cropRightNorm * imgW
                    val cropB = displayedImageRect.top + cropBottomNorm * imgH

                    // Touch target size >= 48dp: radius 32dp (64dp diameter) for corners, 24dp for edges
                    val cornerHitRadius = with(density) { 32.dp.toPx() }
                    val edgeHitDist = with(density) { 24.dp.toPx() }

                    fun distSq(x1: Float, y1: Float, x2: Float, y2: Float) = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
                    val cornerSq = cornerHitRadius * cornerHitRadius

                    activeHandle = when {
                        distSq(offset.x, offset.y, cropL, cropT) <= cornerSq -> CropHandle.TOP_LEFT
                        distSq(offset.x, offset.y, cropR, cropT) <= cornerSq -> CropHandle.TOP_RIGHT
                        distSq(offset.x, offset.y, cropR, cropB) <= cornerSq -> CropHandle.BOTTOM_RIGHT
                        distSq(offset.x, offset.y, cropL, cropB) <= cornerSq -> CropHandle.BOTTOM_LEFT
                        abs(offset.y - cropT) <= edgeHitDist && offset.x in (cropL - 10f .. cropR + 10f) -> CropHandle.TOP
                        abs(offset.y - cropB) <= edgeHitDist && offset.x in (cropL - 10f .. cropR + 10f) -> CropHandle.BOTTOM
                        abs(offset.x - cropL) <= edgeHitDist && offset.y in (cropT - 10f .. cropB + 10f) -> CropHandle.LEFT
                        abs(offset.x - cropR) <= edgeHitDist && offset.y in (cropT - 10f .. cropB + 10f) -> CropHandle.RIGHT
                        offset.x in cropL..cropR && offset.y in cropT..cropB -> CropHandle.BODY
                        else -> CropHandle.NONE
                    }
                    if (activeHandle != CropHandle.NONE) {
                        onDragStateChange(true)
                    }
                },
                onDragEnd = {
                    activeHandle = CropHandle.NONE
                    onDragStateChange(false)
                },
                onDragCancel = {
                    activeHandle = CropHandle.NONE
                    onDragStateChange(false)
                }
            ) { change, dragAmount ->
                if (activeHandle == CropHandle.NONE) return@detectDragGestures
                change.consume()

                val dx = dragAmount.x
                val dy = dragAmount.y

                // Minimum crop size: 48dp
                val minSizePx = with(density) { 48.dp.toPx() }

                var curL = cropLeftNorm * imgW
                var curT = cropTopNorm * imgH
                var curR = cropRightNorm * imgW
                var curB = cropBottomNorm * imgH
                val curW = curR - curL
                val curH = curB - curT

                val ratio = aspectRatio

                if (ratio == null) {
                    // Free aspect ratio dragging
                    when (activeHandle) {
                        CropHandle.TOP_LEFT -> {
                            curL = (curL + dx).coerceIn(0f, curR - minSizePx)
                            curT = (curT + dy).coerceIn(0f, curB - minSizePx)
                        }
                        CropHandle.TOP_RIGHT -> {
                            curR = (curR + dx).coerceIn(curL + minSizePx, imgW)
                            curT = (curT + dy).coerceIn(0f, curB - minSizePx)
                        }
                        CropHandle.BOTTOM_RIGHT -> {
                            curR = (curR + dx).coerceIn(curL + minSizePx, imgW)
                            curB = (curB + dy).coerceIn(curT + minSizePx, imgH)
                        }
                        CropHandle.BOTTOM_LEFT -> {
                            curL = (curL + dx).coerceIn(0f, curR - minSizePx)
                            curB = (curB + dy).coerceIn(curT + minSizePx, imgH)
                        }
                        CropHandle.TOP -> {
                            curT = (curT + dy).coerceIn(0f, curB - minSizePx)
                        }
                        CropHandle.BOTTOM -> {
                            curB = (curB + dy).coerceIn(curT + minSizePx, imgH)
                        }
                        CropHandle.LEFT -> {
                            curL = (curL + dx).coerceIn(0f, curR - minSizePx)
                        }
                        CropHandle.RIGHT -> {
                            curR = (curR + dx).coerceIn(curL + minSizePx, imgW)
                        }
                        CropHandle.BODY -> {
                            val clampedDx = dx.coerceIn(-curL, imgW - curR)
                            val clampedDy = dy.coerceIn(-curT, imgH - curB)
                            curL += clampedDx
                            curR += clampedDx
                            curT += clampedDy
                            curB += clampedDy
                        }
                        CropHandle.NONE -> {}
                    }
                } else {
                    // Locked aspect ratio dragging (R = width / height)
                    val R = ratio.coerceAtLeast(0.01f)
                    val minW = max(minSizePx, minSizePx * R)
                    val minH = minW / R

                    when (activeHandle) {
                        CropHandle.BOTTOM_RIGHT -> {
                            val maxW = min(imgW - curL, (imgH - curT) * R)
                            val candidateW = if (abs(dx) >= abs(dy * R)) curW + dx else (curH + dy) * R
                            val newW = candidateW.coerceIn(minW, max(minW, maxW))
                            val newH = newW / R
                            curR = curL + newW
                            curB = curT + newH
                        }
                        CropHandle.TOP_LEFT -> {
                            val maxW = min(curR, curB * R)
                            val candidateW = if (abs(dx) >= abs(dy * R)) curW - dx else (curH - dy) * R
                            val newW = candidateW.coerceIn(minW, max(minW, maxW))
                            val newH = newW / R
                            curL = curR - newW
                            curT = curB - newH
                        }
                        CropHandle.TOP_RIGHT -> {
                            val maxW = min(imgW - curL, curB * R)
                            val candidateW = if (abs(dx) >= abs(dy * R)) curW + dx else (curH - dy) * R
                            val newW = candidateW.coerceIn(minW, max(minW, maxW))
                            val newH = newW / R
                            curR = curL + newW
                            curT = curB - newH
                        }
                        CropHandle.BOTTOM_LEFT -> {
                            val maxW = min(curR, (imgH - curT) * R)
                            val candidateW = if (abs(dx) >= abs(dy * R)) curW - dx else (curH + dy) * R
                            val newW = candidateW.coerceIn(minW, max(minW, maxW))
                            val newH = newW / R
                            curL = curR - newW
                            curB = curT + newH
                        }
                        CropHandle.RIGHT -> {
                            val maxW = min(imgW - curL, imgH * R)
                            val newW = (curW + dx).coerceIn(minW, max(minW, maxW))
                            val newH = newW / R
                            curR = curL + newW
                            val centerY = (curT + curB) / 2f
                            curT = (centerY - newH / 2f).coerceIn(0f, imgH - newH)
                            curB = curT + newH
                        }
                        CropHandle.LEFT -> {
                            val maxW = min(curR, imgH * R)
                            val newW = (curW - dx).coerceIn(minW, max(minW, maxW))
                            val newH = newW / R
                            curL = curR - newW
                            val centerY = (curT + curB) / 2f
                            curT = (centerY - newH / 2f).coerceIn(0f, imgH - newH)
                            curB = curT + newH
                        }
                        CropHandle.BOTTOM -> {
                            val maxH = min(imgH - curT, imgW / R)
                            val newH = (curH + dy).coerceIn(minH, max(minH, maxH))
                            val newW = newH * R
                            curB = curT + newH
                            val centerX = (curL + curR) / 2f
                            curL = (centerX - newW / 2f).coerceIn(0f, imgW - newW)
                            curR = curL + newW
                        }
                        CropHandle.TOP -> {
                            val maxH = min(curB, imgW / R)
                            val newH = (curH - dy).coerceIn(minH, max(minH, maxH))
                            val newW = newH * R
                            curT = curB - newH
                            val centerX = (curL + curR) / 2f
                            curL = (centerX - newW / 2f).coerceIn(0f, imgW - newW)
                            curR = curL + newW
                        }
                        CropHandle.BODY -> {
                            val clampedDx = dx.coerceIn(-curL, imgW - curR)
                            val clampedDy = dy.coerceIn(-curT, imgH - curB)
                            curL += clampedDx
                            curR += clampedDx
                            curT += clampedDy
                            curB += clampedDy
                        }
                        CropHandle.NONE -> {}
                    }
                }

                onCropChange(
                    (curL / imgW).coerceIn(0f, 1f),
                    (curT / imgH).coerceIn(0f, 1f),
                    (curR / imgW).coerceIn(0f, 1f),
                    (curB / imgH).coerceIn(0f, 1f)
                )
            }
        }
    ) {
        val imgW = displayedImageRect.width()
        val imgH = displayedImageRect.height()
        val cropL = displayedImageRect.left + cropLeftNorm * imgW
        val cropT = displayedImageRect.top + cropTopNorm * imgH
        val cropR = displayedImageRect.left + cropRightNorm * imgW
        val cropB = displayedImageRect.top + cropBottomNorm * imgH
        val cropW = cropR - cropL
        val cropH = cropB - cropT

        val cW = size.width
        val cH = size.height

        // 1. Dimmed outer overlay (covering entire container including letterboxing)
        val maskColor = Color.Black.copy(alpha = 0.55f)
        drawRect(maskColor, Offset(0f, 0f), Size(cW, cropT))
        drawRect(maskColor, Offset(0f, cropB), Size(cW, cH - cropB))
        drawRect(maskColor, Offset(0f, cropT), Size(cropL, cropH))
        drawRect(maskColor, Offset(cropR, cropT), Size(cW - cropR, cropH))

        // 2. Crop border
        drawRect(
            color = Color.White.copy(alpha = 0.9f),
            topLeft = Offset(cropL, cropT),
            size = Size(cropW, cropH),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // 3. Rule of thirds grid
        val thirdW = cropW / 3f
        val thirdH = cropH / 3f
        val gridColor = Color.White.copy(alpha = 0.35f)
        val gridStroke = 1.dp.toPx()
        drawLine(gridColor, Offset(cropL + thirdW, cropT), Offset(cropL + thirdW, cropB), gridStroke)
        drawLine(gridColor, Offset(cropL + 2f * thirdW, cropT), Offset(cropL + 2f * thirdW, cropB), gridStroke)
        drawLine(gridColor, Offset(cropL, cropT + thirdH), Offset(cropR, cropT + thirdH), gridStroke)
        drawLine(gridColor, Offset(cropL, cropT + 2f * thirdH), Offset(cropR, cropT + 2f * thirdH), gridStroke)

        // 4. L-shaped corner brackets
        val bracketLen = 18.dp.toPx().coerceAtMost(min(cropW, cropH) / 3f)
        val bracketStroke = 3.dp.toPx()
        // TL
        drawLine(Color.White, Offset(cropL, cropT), Offset(cropL + bracketLen, cropT), bracketStroke)
        drawLine(Color.White, Offset(cropL, cropT), Offset(cropL, cropT + bracketLen), bracketStroke)
        // TR
        drawLine(Color.White, Offset(cropR - bracketLen, cropT), Offset(cropR, cropT), bracketStroke)
        drawLine(Color.White, Offset(cropR, cropT), Offset(cropR, cropT + bracketLen), bracketStroke)
        // BR
        drawLine(Color.White, Offset(cropR - bracketLen, cropB), Offset(cropR, cropB), bracketStroke)
        drawLine(Color.White, Offset(cropR, cropB - bracketLen), Offset(cropR, cropB), bracketStroke)
        // BL
        drawLine(Color.White, Offset(cropL, cropB), Offset(cropL + bracketLen, cropB), bracketStroke)
        drawLine(Color.White, Offset(cropL, cropB - bracketLen), Offset(cropL, cropB), bracketStroke)

        // Corner circles
        val handleRadius = 6.dp.toPx()
        listOf(
            Offset(cropL, cropT),
            Offset(cropR, cropT),
            Offset(cropR, cropB),
            Offset(cropL, cropB)
        ).forEach { pt ->
            drawCircle(Color.White, radius = handleRadius, center = pt)
            drawCircle(accentColor, radius = handleRadius, center = pt, style = Stroke(2.dp.toPx()))
        }

        // 5. Edge handles (midpoint rounded bars)
        val edgeBarLen = 22.dp.toPx().coerceAtMost(min(cropW, cropH) / 3f)
        val edgeBarStroke = 3.5.dp.toPx()
        // Top
        val midTopX = (cropL + cropR) / 2f
        drawLine(Color.White, Offset(midTopX - edgeBarLen / 2f, cropT), Offset(midTopX + edgeBarLen / 2f, cropT), edgeBarStroke, StrokeCap.Round)
        // Bottom
        val midBotX = (cropL + cropR) / 2f
        drawLine(Color.White, Offset(midBotX - edgeBarLen / 2f, cropB), Offset(midBotX + edgeBarLen / 2f, cropB), edgeBarStroke, StrokeCap.Round)
        // Left
        val midLeftY = (cropT + cropB) / 2f
        drawLine(Color.White, Offset(cropL, midLeftY - edgeBarLen / 2f), Offset(cropL, midLeftY + edgeBarLen / 2f), edgeBarStroke, StrokeCap.Round)
        // Right
        val midRightY = (cropT + cropB) / 2f
        drawLine(Color.White, Offset(cropR, midRightY - edgeBarLen / 2f), Offset(cropR, midRightY + edgeBarLen / 2f), edgeBarStroke, StrokeCap.Round)
    }
}

// -------------------------------------------------------------
// FILTER COLOR MATRIX PROCESSING
// -------------------------------------------------------------

fun renderProcessedBitmap(source: Bitmap, degrees: Float, filterMode: DocFilterMode): Bitmap {
    val matrix = Matrix()
    if (degrees != 0f) {
        matrix.postRotate(degrees)
    }

    val rotated = if (degrees != 0f) {
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    } else {
        source
    }

    if (filterMode == DocFilterMode.ORIGINAL) {
        return if (rotated == source) {
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        } else {
            rotated
        }
    }

    val output = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    val colorMatrix = ColorMatrix()
    when (filterMode) {
        DocFilterMode.GRAYSCALE -> colorMatrix.setSaturation(0f)
        DocFilterMode.MAGIC -> {
            colorMatrix.set(
                floatArrayOf(
                    1.3f, 0f, 0f, 0f, -15f,
                    0f, 1.3f, 0f, 0f, -15f,
                    0f, 0f, 1.3f, 0f, -15f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        DocFilterMode.BW -> {
            val gray = ColorMatrix().apply { setSaturation(0f) }
            val contrast = ColorMatrix(
                floatArrayOf(
                    2.8f, 0f, 0f, 0f, -125f,
                    0f, 2.8f, 0f, 0f, -125f,
                    0f, 0f, 2.8f, 0f, -125f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            colorMatrix.setConcat(contrast, gray)
        }
        DocFilterMode.CONTRAST_BOOST -> {
            val scale = 1.4f
            val translate = (-0.5f * scale + 0.5f) * 255f
            colorMatrix.set(
                floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        else -> {}
    }

    paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
    canvas.drawBitmap(rotated, 0f, 0f, paint)

    if (rotated != source && rotated != output) {
        rotated.recycle()
    }
    return output
}

/**
 * Flattens placed signatures, pen, highlighter strokes, and single-tap dots onto the full-resolution bitmap.
 */
fun renderFlattenedComposite(
    baseBitmap: Bitmap,
    strokes: List<AnnotationStroke>,
    referenceDisplayWidth: Float = 0f,
    placedSignatures: List<Pair<Bitmap, RectF>> = emptyList()
): Bitmap {
    val output = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    val bmpWidth = output.width.toFloat()
    val bmpHeight = output.height.toFloat()

    val scale = if (referenceDisplayWidth > 0f) {
        bmpWidth / referenceDisplayWidth
    } else {
        bmpWidth / 360f
    }

    // Draw placed signatures first
    val sigPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    placedSignatures.forEach { (sigBmp, rectNorm) ->
        val dest = RectF(
            rectNorm.left * bmpWidth,
            rectNorm.top * bmpHeight,
            rectNorm.right * bmpWidth,
            rectNorm.bottom * bmpHeight
        )
        canvas.drawBitmap(sigBmp, null, dest, sigPaint)
    }

    // Render strokes with exact preview-equivalent dimensions & blend modes
    strokes.forEach { stroke ->
        val exportStrokeWidth = (stroke.strokeWidth * scale).coerceAtLeast(1f)
        val paint = Paint().apply {
            color = stroke.color.toArgb()
            strokeWidth = exportStrokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            if (stroke.isHighlighter) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
        }

        if (stroke.points.size == 1) {
            val pt = stroke.points[0]
            paint.style = Paint.Style.FILL
            canvas.drawCircle(pt.x * bmpWidth, pt.y * bmpHeight, exportStrokeWidth / 2f, paint)
        } else if (stroke.points.size > 1) {
            paint.style = Paint.Style.STROKE
            val path = android.graphics.Path()
            path.moveTo(stroke.points[0].x * bmpWidth, stroke.points[0].y * bmpHeight)
            for (i in 1 until stroke.points.size) {
                path.lineTo(stroke.points[i].x * bmpWidth, stroke.points[i].y * bmpHeight)
            }
            canvas.drawPath(path, paint)
        }
    }

    return output
}

/**
 * Evaluates whether an eraser touch point intersects or falls within radius of a stroke.
 */
fun isStrokeHit(stroke: AnnotationStroke, touch: Offset, radius: Float): Boolean {
    val rSq = radius * radius
    if (stroke.points.size == 1) {
        val pt = stroke.points[0]
        val dx = pt.x - touch.x
        val dy = pt.y - touch.y
        return (dx * dx + dy * dy) <= rSq
    }
    for (i in 0 until stroke.points.size - 1) {
        val p1 = stroke.points[i]
        val p2 = stroke.points[i + 1]
        if (distToSegmentSq(touch, p1, p2) <= rSq) return true
    }
    return false
}

private fun distToSegmentSq(p: Offset, v: Offset, w: Offset): Float {
    val l2 = (w.x - v.x) * (w.x - v.x) + (w.y - v.y) * (w.y - v.y)
    if (l2 == 0f) {
        val dx = p.x - v.x
        val dy = p.y - v.y
        return dx * dx + dy * dy
    }
    val t = ((p.x - v.x) * (w.x - v.x) + (p.y - v.y) * (w.y - v.y)) / l2
    val clampedT = t.coerceIn(0f, 1f)
    val projX = v.x + clampedT * (w.x - v.x)
    val projY = v.y + clampedT * (w.y - v.y)
    val dx = p.x - projX
    val dy = p.y - projY
    return dx * dx + dy * dy
}

fun findRealDocFolder(context: Context, file: File): File {
    val parent = file.parentFile ?: context.filesDir
    if (parent.name == "unlocked_sessions") {
        val grandParent = parent.parentFile ?: context.filesDir
        val candidate = File(grandParent, file.nameWithoutExtension)
        if (candidate.exists() && candidate.isDirectory) return candidate
    }
    return parent
}

/**
 * Full-resolution atomic save preserving high-res crop, rotation, filters, signatures, and annotations.
 */
private suspend fun saveEditorChanges(
    activity: Activity,
    file: File,
    rotationDegrees: Float,
    selectedFilter: DocFilterMode,
    strokes: List<AnnotationStroke>,
    referenceDisplayWidth: Float,
    previewFallback: Bitmap,
    placedSignatures: List<Pair<Bitmap, RectF>> = emptyList(),
    appliedCropRectNorm: RectF? = null,
    bakedRotationDegrees: Float = 0f
): Boolean {
    return try {
        OriginalImageManager.ensureOriginalPreserved(file)

        val fullResBitmap = IdCardGenerator.decodeUriWithExif(activity, Uri.fromFile(file), 4000, 4000)
            ?: BitmapFactory.decodeFile(file.absolutePath)

        val processedFullRes = if (fullResBitmap != null) {
            var currentBitmap = fullResBitmap

            // 1. Apply rotation baked before or during crop
            if (bakedRotationDegrees != 0f) {
                val mat = Matrix().apply { postRotate(bakedRotationDegrees) }
                val rotated = Bitmap.createBitmap(currentBitmap, 0, 0, currentBitmap.width, currentBitmap.height, mat, true)
                if (rotated != currentBitmap) {
                    currentBitmap.recycle()
                }
                currentBitmap = rotated
            }

            // 2. Crop from full resolution
            if (appliedCropRectNorm != null) {
                val bw = currentBitmap.width
                val bh = currentBitmap.height
                val cl = (appliedCropRectNorm.left * bw).toInt().coerceIn(0, bw - 1)
                val ct = (appliedCropRectNorm.top * bh).toInt().coerceIn(0, bh - 1)
                val cr = (appliedCropRectNorm.right * bw).toInt().coerceIn(cl + 1, bw)
                val cb = (appliedCropRectNorm.bottom * bh).toInt().coerceIn(ct + 1, bh)
                val cw = (cr - cl).coerceIn(1, bw - cl)
                val ch = (cb - ct).coerceIn(1, bh - ct)
                val cropped = Bitmap.createBitmap(currentBitmap, cl, ct, cw, ch)
                if (cropped != currentBitmap) {
                    currentBitmap.recycle()
                }
                currentBitmap = cropped
            }

            // 3. Apply active rotation and color filter to the full-res cropped bitmap
            val filtered = renderProcessedBitmap(currentBitmap, rotationDegrees, selectedFilter)
            if (filtered != currentBitmap && currentBitmap != fullResBitmap) {
                currentBitmap.recycle()
            }
            filtered
        } else {
            previewFallback
        }

        val flattened = renderFlattenedComposite(
            baseBitmap = processedFullRes,
            strokes = strokes,
            referenceDisplayWidth = referenceDisplayWidth,
            placedSignatures = placedSignatures
        )

        val tmpFile = File(file.parentFile, "${file.name}.edit_tmp")
        FileOutputStream(tmpFile).use { out ->
            flattened.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.flush()
        }

        if (tmpFile.exists() && tmpFile.length() > 0) {
            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
        } else {
            return false
        }

        if (processedFullRes != flattened && processedFullRes != previewFallback) processedFullRes.recycle()
        if (flattened != previewFallback) flattened.recycle()
        if (fullResBitmap != null && fullResBitmap != processedFullRes && !fullResBitmap.isRecycled) fullResBitmap.recycle()

        val docFolder = findRealDocFolder(activity, file)
        val isLockedDoc = File(docFolder, ".dek.enc").exists()

        if (isLockedDoc) {
            val candidateBaseName = file.name.substringBefore(".jpg").substringBefore(".png").substringBefore(".jpeg")
            val encFile = File(docFolder, "$candidateBaseName.jpg.enc")
            val legacyEncFile = File(docFolder, "$candidateBaseName.enc")
            val lockTmp = File(docFolder, encFile.name + ".lock_tmp")
            try {
                LockedDocCryptoManager.encryptFile(file, lockTmp)
                val dek = LockedDocCryptoManager.getOrDecryptDek(docFolder)
                if (!LockedDocCryptoManager.verifyEncryptedChunk(lockTmp, dek)) {
                    throw IllegalStateException("Encrypted chunk verification failed for lock_tmp")
                }
                val renamed = if (lockTmp.renameTo(encFile)) {
                    true
                } else {
                    lockTmp.copyTo(encFile, overwrite = true)
                    lockTmp.delete()
                    true
                }
                if (renamed && legacyEncFile.exists() && legacyEncFile.absolutePath != encFile.absolutePath) {
                    legacyEncFile.delete()
                }
            } catch (e: Exception) {
                if (lockTmp.exists()) {
                    lockTmp.delete()
                }
                android.util.Log.e("ImageEditorScreen", "Error re-encrypting page: ${e.message}", e)
            }
        }

        try {
            OcrManager.processDocumentOcr(activity, docFolder)
            thumbnailMemoryCache.evictAll()
        } catch (e: Exception) {
            android.util.Log.e("ImageEditorScreen", "Post-save sync failed: ${e.message}", e)
        }

        true
    } catch (e: Exception) {
        android.util.Log.e("ImageEditorScreen", "Save failed: ${e.message}", e)
        false
    }
}

