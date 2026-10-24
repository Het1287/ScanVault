package com.example.ownscan

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// -------------------------------------------------------------
// ID CARD PRESETS & PHYSICAL RATIOS
// ISO/IEC 7810 ID-1 standard: 85.60 x 53.98 mm (~1.586 aspect ratio)
// A4 size: 210 x 297 mm
// -------------------------------------------------------------

enum class IdScalingMode(val label: String) {
    CENTER_CROP("Center Crop (Fill)"),
    LETTERBOX("Fit Entire Card (Letterbox)")
}

enum class IdCardPreset(
    val displayName: String,
    val widthMm: Float,
    val heightMm: Float,
    val defaultDocName: String,
    val defaultTags: List<String>,
    val requiresBackSide: Boolean
) {
    AADHAAR(
        displayName = "Aadhaar Card",
        widthMm = 85.6f,
        heightMm = 54.0f,
        defaultDocName = "Aadhaar Card",
        defaultTags = listOf("ID", "Aadhaar"),
        requiresBackSide = true
    ),
    PAN(
        displayName = "PAN Card",
        widthMm = 85.6f,
        heightMm = 54.0f,
        defaultDocName = "PAN Card",
        defaultTags = listOf("ID", "PAN"),
        requiresBackSide = false
    ),
    COLLEGE_ID(
        displayName = "College / Student ID",
        widthMm = 85.6f,
        heightMm = 54.0f,
        defaultDocName = "College ID",
        defaultTags = listOf("ID", "Student"),
        requiresBackSide = false
    ),
    CUSTOM(
        displayName = "Standard Card (85.6x54mm)",
        widthMm = 85.6f,
        heightMm = 54.0f,
        defaultDocName = "ID Card",
        defaultTags = listOf("ID"),
        requiresBackSide = false
    )
}

object IdCardGenerator {

    // Standard ISO/IEC 7810 ID-1: 85.60 x 53.98 mm (rounded to 85.6 x 54.0 mm)
    // A4 sheet: 210 x 297 mm
    // Full export at 300 DPI:
    const val A4_WIDTH_300DPI = 2480
    const val A4_HEIGHT_300DPI = 3508

    // Lightweight preview scaling (~100 DPI) for real-time responsive UI
    const val PREVIEW_SCALE = 0.3333f
    const val A4_PREVIEW_WIDTH = 826
    const val A4_PREVIEW_HEIGHT = 1169

    // Backwards-compatible aliases
    const val A4_WIDTH_PX = A4_WIDTH_300DPI
    const val A4_HEIGHT_PX = A4_HEIGHT_300DPI

    /**
     * Renders front and back images onto a single A4 page canvas with true physical dimensions (85.6 x 54 mm).
     * @param isPreview If true, generates downscaled preview (~100 DPI) for memory-safe instant UI rendering.
     *                  If false, produces full 300 DPI print-ready A4 composite.
     */
    fun createA4Composite(
        context: Context,
        frontUri: Uri?,
        backUri: Uri?,
        preset: IdCardPreset,
        scalingMode: IdScalingMode = IdScalingMode.CENTER_CROP,
        isPreview: Boolean = false
    ): Bitmap? {
        if (frontUri == null && backUri == null) return null

        val reqDim = if (isPreview) 800 else 2400
        val frontBitmap = frontUri?.let { decodeUriWithExif(context, it, reqDim, reqDim) }
        val backBitmap = backUri?.let { decodeUriWithExif(context, it, reqDim, reqDim) }

        if (frontBitmap == null && backBitmap == null) return null

        val scale = if (isPreview) PREVIEW_SCALE else 1.0f
        val a4Width = (A4_WIDTH_300DPI * scale).toInt()
        val a4Height = (A4_HEIGHT_300DPI * scale).toInt()

        val a4Bitmap = try {
            Bitmap.createBitmap(a4Width, a4Height, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            frontBitmap?.recycle()
            backBitmap?.recycle()
            System.gc()
            return null
        }

        val canvas = Canvas(a4Bitmap)
        canvas.drawColor(AndroidColor.WHITE)

        // True physical card dimensions at 300 DPI:
        // 85.6 mm * 300 / 25.4 = 1011 px
        // 54.0 mm * 300 / 25.4 = 638 px
        val cardWidthPx = ((preset.widthMm * 300f / 25.4f) * scale).toInt()
        val cardHeightPx = ((preset.heightMm * 300f / 25.4f) * scale).toInt()

        val leftPos = (a4Width - cardWidthPx) / 2f
        val cornerRadius = 38f * scale // Standard ISO card 3.18 mm corner radius (~38 px at 300 DPI)

        val borderPaint = Paint().apply {
            color = AndroidColor.parseColor("#CCCCCC")
            style = Paint.Style.STROKE
            strokeWidth = 3f * scale
            isAntiAlias = true
        }

        // Front Card position (Upper section centered)
        val frontTop = (a4Height * 0.26f) - (cardHeightPx / 2f)
        val frontRectF = RectF(leftPos, frontTop, leftPos + cardWidthPx, frontTop + cardHeightPx)

        if (frontBitmap != null) {
            drawPreservedAspectBitmap(canvas, frontBitmap, frontRectF, cornerRadius, scalingMode)
            canvas.drawRoundRect(frontRectF, cornerRadius, cornerRadius, borderPaint)
        } else {
            drawPlaceholderCard(canvas, frontRectF, "Front Side (Not Provided)", cornerRadius, scale)
        }

        // Separation cutting guide dashed line
        val midY = a4Height / 2f
        val linePaint = Paint().apply {
            color = AndroidColor.parseColor("#CCCCCC")
            strokeWidth = 2.5f * scale
            style = Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(25f * scale, 25f * scale), 0f)
            isAntiAlias = true
        }
        canvas.drawLine(leftPos, midY, leftPos + cardWidthPx, midY, linePaint)

        // Back Card position (Lower section centered)
        val backTop = (a4Height * 0.74f) - (cardHeightPx / 2f)
        val backRectF = RectF(leftPos, backTop, leftPos + cardWidthPx, backTop + cardHeightPx)

        if (backBitmap != null) {
            drawPreservedAspectBitmap(canvas, backBitmap, backRectF, cornerRadius, scalingMode)
            canvas.drawRoundRect(backRectF, cornerRadius, cornerRadius, borderPaint)
        } else {
            val placeholderText = if (preset.requiresBackSide) "Back Side (Optional / Not Scanned)" else "Back Side (Single-sided Card)"
            drawPlaceholderCard(canvas, backRectF, placeholderText, cornerRadius, scale)
        }

        // Recycle source bitmaps promptly to avoid memory spikes
        frontBitmap?.recycle()
        backBitmap?.recycle()

        return a4Bitmap
    }

    /**
     * Draws bitmap into destRect without stretching/distorting the aspect ratio.
     * Supports CENTER_CROP (fills card bounds with no letterbox bars) and LETTERBOX (shows entire photo).
     */
    private fun drawPreservedAspectBitmap(
        canvas: Canvas,
        srcBitmap: Bitmap,
        destRect: RectF,
        cornerRadius: Float,
        scalingMode: IdScalingMode
    ) {
        val destW = destRect.width()
        val destH = destRect.height()
        val destAspect = destW / destH
        val srcW = srcBitmap.width.toFloat()
        val srcH = srcBitmap.height.toFloat()
        val srcAspect = srcW / srcH

        canvas.save()
        val clipPath = android.graphics.Path().apply {
            addRoundRect(destRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(clipPath)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        when (scalingMode) {
            IdScalingMode.CENTER_CROP -> {
                val srcRect = if (srcAspect > destAspect) {
                    // Source is wider than target card: crop left and right
                    val cropW = (srcH * destAspect).toInt()
                    val cropLeft = ((srcBitmap.width - cropW) / 2).coerceAtLeast(0)
                    Rect(cropLeft, 0, cropLeft + cropW, srcBitmap.height)
                } else {
                    // Source is taller than target card: crop top and bottom
                    val cropH = (srcW / destAspect).toInt()
                    val cropTop = ((srcBitmap.height - cropH) / 2).coerceAtLeast(0)
                    Rect(0, cropTop, srcBitmap.width, cropTop + cropH)
                }
                canvas.drawBitmap(srcBitmap, srcRect, destRect, paint)
            }
            IdScalingMode.LETTERBOX -> {
                // Background fill for letterbox margin
                val bgPaint = Paint().apply {
                    color = AndroidColor.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawRect(destRect, bgPaint)

                val (fitW, fitH) = if (srcAspect > destAspect) {
                    destW to (destW / srcAspect)
                } else {
                    (destH * srcAspect) to destH
                }
                val fitLeft = destRect.left + (destW - fitW) / 2f
                val fitTop = destRect.top + (destH - fitH) / 2f
                val fitDestRect = RectF(fitLeft, fitTop, fitLeft + fitW, fitTop + fitH)
                val srcRect = Rect(0, 0, srcBitmap.width, srcBitmap.height)
                canvas.drawBitmap(srcBitmap, srcRect, fitDestRect, paint)
            }
        }

        canvas.restore()
    }

    fun drawPlaceholderCard(canvas: Canvas, rect: RectF, text: String, cornerRadius: Float, scale: Float = 1.0f) {
        val fillPaint = Paint().apply {
            color = AndroidColor.parseColor("#F8F9FA")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val borderPaint = Paint().apply {
            color = AndroidColor.parseColor("#E0E0E0")
            style = Paint.Style.STROKE
            strokeWidth = 2f * scale
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * scale, 10f * scale), 0f)
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            color = AndroidColor.GRAY
            textSize = 20f * scale
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
        canvas.drawText(text, rect.centerX(), rect.centerY() + (7f * scale), textPaint)
    }

    /**
     * Two-pass decoding with inSampleSize and EXIF orientation correction.
     * Prevents OutOfMemoryError on large camera photos.
     */
    fun decodeUriWithExif(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            // Pass 1: Decode dimensions only
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            // Calculate sample size
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            // Pass 2: Decode sampled bitmap
            val sampledBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null

            // Apply EXIF orientation
            val rotatedBitmap = applyExifFromStream(context, uri, sampledBitmap)
            rotatedBitmap
        } catch (oom: OutOfMemoryError) {
            System.gc()
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun applyExifFromStream(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bitmap
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }
}

// -------------------------------------------------------------
// ID CARD CAPTURE & COMPOSITION DIALOG UI
// -------------------------------------------------------------

@Composable
fun IdCardModeDialog(
    activity: Activity,
    targetFolder: String?,
    onDismiss: () -> Unit,
    onSavedSuccessfully: () -> Unit
) {
    val colors = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()

    var selectedPreset by remember { mutableStateOf(IdCardPreset.AADHAAR) }
    var scalingMode by remember { mutableStateOf(IdScalingMode.CENTER_CROP) }
    var customDocName by remember { mutableStateOf("") }

    var frontUri by remember { mutableStateOf<Uri?>(null) }
    var backUri by remember { mutableStateOf<Uri?>(null) }
    var isFrontActiveTarget by remember { mutableStateOf(true) }

    var previewComposite by remember { mutableStateOf<Bitmap?>(null) }
    var isGeneratingPreview by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            if (isFrontActiveTarget) {
                frontUri = uri
            } else {
                backUri = uri
            }
        }
    }

    LaunchedEffect(frontUri, backUri, selectedPreset, scalingMode) {
        if (frontUri != null || backUri != null) {
            isGeneratingPreview = true
            val bmp = withContext(Dispatchers.IO) {
                IdCardGenerator.createA4Composite(activity, frontUri, backUri, selectedPreset, scalingMode, isPreview = true)
            }
            val old = previewComposite
            previewComposite = bmp
            old?.recycle()
            isGeneratingPreview = false
        } else {
            val old = previewComposite
            previewComposite = null
            old?.recycle()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(20.dp)),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.cardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🪪", fontSize = 22.sp, modifier = Modifier.semantics { contentDescription = "ID Card" })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ID Card Mode",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(28.dp)
                            .semantics { contentDescription = "Close" }
                    ) {
                        Text("✕", color = colors.textMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Combine Front & Back of an ID onto a single A4 page with standard 85.6 x 54 mm physical dimensions. Aspect ratios are preserved without stretching.",
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )

                    // Presets
                    Text("Select Preset:", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IdCardPreset.values().forEach { preset ->
                            val isSelected = selectedPreset == preset
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) colors.accent else colors.cardSubtle,
                                border = BorderStroke(1.dp, if (isSelected) colors.accent else colors.cardBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedPreset = preset }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = preset.displayName.replace(" Card", ""),
                                        color = if (isSelected) Color.White else colors.textPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Scaling mode toggle (Center Crop vs Letterbox)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Photo Fit:", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        IdScalingMode.values().forEach { mode ->
                            val isChosen = scalingMode == mode
                            FilterChip(
                                selected = isChosen,
                                onClick = { scalingMode = mode },
                                label = { Text(mode.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    // Card Slots (Front & Back)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Front Slot
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(115.dp)
                                .clickable {
                                    isFrontActiveTarget = true
                                    imagePicker.launch("image/*")
                                },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSubtle),
                            border = BorderStroke(1.dp, if (frontUri != null) colors.accentGreen else colors.cardBorder)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (frontUri != null) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        Text("✓ Front Picked", color = colors.accentGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Tap to change", color = colors.textMuted, fontSize = 10.sp)
                                    }
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("📷", fontSize = 24.sp, modifier = Modifier.semantics { contentDescription = "Camera" })
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("+ Pick Front", color = colors.accent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        // Back Slot
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(115.dp)
                                .clickable {
                                    isFrontActiveTarget = false
                                    imagePicker.launch("image/*")
                                },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSubtle),
                            border = BorderStroke(1.dp, if (backUri != null) colors.accentGreen else colors.cardBorder)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (backUri != null) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        Text("✓ Back Picked", color = colors.accentGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Tap to change", color = colors.textMuted, fontSize = 10.sp)
                                    }
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("📷", fontSize = 24.sp, modifier = Modifier.semantics { contentDescription = "Camera" })
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            if (selectedPreset.requiresBackSide) "+ Pick Back" else "+ Back (Optional)",
                                            color = colors.accent,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Document Name TextField
                    OutlinedTextField(
                        value = customDocName,
                        onValueChange = { customDocName = it },
                        placeholder = { Text("${selectedPreset.defaultDocName} – ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())}", fontSize = 12.sp) },
                        label = { Text("Document Name (Optional)", color = colors.textMuted, fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // A4 Preview
                    Text("A4 Output Preview:", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.DarkGray.copy(alpha = 0.15f))
                            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isGeneratingPreview) {
                            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(28.dp))
                        } else if (previewComposite != null) {
                            Image(
                                bitmap = previewComposite!!.asImageBitmap(),
                                contentDescription = "A4 ID Card Composite Sheet Preview",
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(210f / 297f)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text(
                                text = "Select Front (and optional Back) to preview A4 sheet",
                                color = colors.textMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, colors.cardBorder)
                    ) {
                        Text("Cancel", color = colors.textPrimary)
                    }

                    Button(
                        onClick = {
                            if (previewComposite != null && !isSaving) {
                                isSaving = true
                                coroutineScope.launch {
                                    val dateStr = SimpleDateFormat("ddMMM_yyyy", Locale.getDefault()).format(Date())
                                    val docDisplayName = customDocName.trim().ifEmpty {
                                        "${selectedPreset.defaultDocName} – $dateStr"
                                    }
                                    val savedFolder = withContext(Dispatchers.IO) {
                                        val fullResComposite = IdCardGenerator.createA4Composite(
                                            activity,
                                            frontUri,
                                            backUri,
                                            selectedPreset,
                                            scalingMode,
                                            isPreview = false
                                        ) ?: previewComposite
                                        if (fullResComposite != null) {
                                            val folder = saveCompositeToDoc(activity, fullResComposite, docDisplayName, targetFolder)
                                            if (fullResComposite != previewComposite) {
                                                fullResComposite.recycle()
                                            }
                                            folder
                                        } else null
                                    }
                                    if (savedFolder != null) {
                                        val initialTags = selectedPreset.defaultTags
                                        val meta = DocMeta.readFromDir(savedFolder).copy(tags = initialTags)
                                        DocMeta.writeToDir(savedFolder, meta)

                                        OcrManager.processDocumentOcr(activity, savedFolder)

                                        Toast.makeText(activity, "'$docDisplayName' saved successfully!", Toast.LENGTH_SHORT).show()
                                        onSavedSuccessfully()
                                    } else {
                                        Toast.makeText(activity, "Failed to save ID Card", Toast.LENGTH_SHORT).show()
                                        isSaving = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1.5f),
                        enabled = previewComposite != null && !isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                    ) {
                        Text(if (isSaving) "Saving..." else "Save as Document", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun saveCompositeToDoc(
    context: Context,
    compositeBitmap: Bitmap,
    displayName: String,
    targetFolder: String?
): File? {
    return try {
        val timestamp = System.currentTimeMillis()
        val sanitized = sanitizeName(displayName).ifEmpty { "ID_Card_$timestamp" }
        val folderName = "${timestamp}__$sanitized"
        val parentDir = if (targetFolder != null) {
            File(getUserFoldersDir(context), targetFolder)
        } else {
            getPrivateDocsDir(context)
        }
        if (!parentDir.exists()) parentDir.mkdirs()

        val docFolder = File(parentDir, folderName)
        docFolder.mkdirs()

        val imageFile = File(docFolder, "img_a4_id_card.jpg")
        FileOutputStream(imageFile).use { out ->
            compositeBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        savePageOrder(docFolder, listOf(imageFile))
        DocMeta.writeToDir(docFolder, DocMeta())
        docFolder
    } catch (e: Exception) {
        null
    }
}
