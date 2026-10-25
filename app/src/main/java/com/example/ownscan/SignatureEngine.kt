package com.example.ownscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SavedSignature(
    val id: String,
    val file: File,
    val timestamp: Long,
    val name: String
)

data class SignaturePath(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float
)

object SignatureEngine {

    fun getSignaturesDir(context: Context): File {
        val dir = File(context.filesDir, "signatures")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun getSavedSignatures(context: Context): List<SavedSignature> = withContext(Dispatchers.IO) {
        val dir = getSignaturesDir(context)
        dir.listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }
            ?.map { file ->
                val nameWithoutExt = file.nameWithoutExtension
                val parts = nameWithoutExt.split("_")
                val name = if (parts.size > 2) parts.drop(2).joinToString(" ") else "Signature"
                val ts = file.lastModified()
                SavedSignature(
                    id = file.name,
                    file = file,
                    timestamp = ts,
                    name = name
                )
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    suspend fun saveSignature(context: Context, bitmap: Bitmap, name: String = "Signature"): SavedSignature = withContext(Dispatchers.IO) {
        val dir = getSignaturesDir(context)
        val id = "sig_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.png"
        val file = File(dir, id)
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        SavedSignature(
            id = id,
            file = file,
            timestamp = System.currentTimeMillis(),
            name = name
        )
    }

    suspend fun deleteSignature(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(getSignaturesDir(context), id)
        if (file.exists()) file.delete() else false
    }

    fun loadSignatureBitmap(file: File): Bitmap? {
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    /**
     * Renders a list of SignaturePaths into a trimmed transparent Bitmap.
     */
    fun pathsToBitmap(paths: List<SignaturePath>, canvasWidth: Int, canvasHeight: Int): Bitmap? {
        if (paths.isEmpty() || canvasWidth <= 0 || canvasHeight <= 0) return null

        val fullBmp = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fullBmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        var minX = canvasWidth.toFloat()
        var minY = canvasHeight.toFloat()
        var maxX = 0f
        var maxY = 0f

        for (path in paths) {
            if (path.points.size < 2) continue
            paint.color = android.graphics.Color.argb(
                (path.color.alpha * 255).toInt(),
                (path.color.red * 255).toInt(),
                (path.color.green * 255).toInt(),
                (path.color.blue * 255).toInt()
            )
            paint.strokeWidth = path.strokeWidth

            val androidPath = android.graphics.Path()
            androidPath.moveTo(path.points[0].x, path.points[0].y)
            minX = minOf(minX, path.points[0].x)
            minY = minOf(minY, path.points[0].y)
            maxX = maxOf(maxX, path.points[0].x)
            maxY = maxOf(maxY, path.points[0].y)

            for (i in 1 until path.points.size) {
                val pt = path.points[i]
                val prev = path.points[i - 1]
                androidPath.quadTo(prev.x, prev.y, (prev.x + pt.x) / 2f, (prev.y + pt.y) / 2f)
                minX = minOf(minX, pt.x)
                minY = minOf(minY, pt.y)
                maxX = maxOf(maxX, pt.x)
                maxY = maxOf(maxY, pt.y)
            }
            androidPath.lineTo(path.points.last().x, path.points.last().y)
            canvas.drawPath(androidPath, paint)
        }

        // Add padding around crop
        val padding = 16
        val cropLeft = (minX.toInt() - padding).coerceAtLeast(0)
        val cropTop = (minY.toInt() - padding).coerceAtLeast(0)
        val cropRight = (maxX.toInt() + padding).coerceAtMost(canvasWidth)
        val cropBottom = (maxY.toInt() + padding).coerceAtMost(canvasHeight)

        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop

        return if (cropW > 10 && cropH > 10) {
            val cropped = Bitmap.createBitmap(fullBmp, cropLeft, cropTop, cropW, cropH)
            if (cropped != fullBmp) fullBmp.recycle()
            cropped
        } else {
            fullBmp
        }
    }
}

/**
 * Signature Pad Dialog where users draw their signature, pick ink color, undo, clear,
 * and either save or apply it to the document.
 */
@Composable
fun SignaturePadDialog(
    colors: AppColors,
    onDismiss: () -> Unit,
    onSignatureConfirmed: (Bitmap, Boolean) -> Unit
) {
    var paths by remember { mutableStateOf(listOf<SignaturePath>()) }
    var currentPath by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var penColor by remember { mutableStateOf(Color.Black) }
    var strokeWidth by remember { mutableFloatStateOf(5f) }
    var saveToLibrary by remember { mutableStateOf(true) }

    val colorOptions = listOf(
        Color(0xFF000000), // Black
        Color(0xFF0D47A1), // Navy Blue
        Color(0xFF1B5E20), // Dark Green
        Color(0xFFB71C1C)  // Dark Red
    )

    var canvasSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 16.dp)
                .systemBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✍️", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Draw Signature",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                if (paths.isNotEmpty()) {
                                    paths = paths.dropLast(1)
                                }
                            },
                            enabled = paths.isNotEmpty()
                        ) {
                            Text("↩️ Undo", color = if (paths.isNotEmpty()) colors.accent else colors.textMuted, fontSize = 12.sp)
                        }

                        TextButton(
                            onClick = {
                                paths = emptyList()
                                currentPath = emptyList()
                            },
                            enabled = paths.isNotEmpty() || currentPath.isNotEmpty()
                        ) {
                            Text("Clear", color = if (paths.isNotEmpty() || currentPath.isNotEmpty()) colors.accentRed else colors.textMuted, fontSize = 12.sp)
                        }
                    }
                }

                // Drawing Pad Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.5.dp, colors.accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(penColor, strokeWidth) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        currentPath = listOf(offset)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        currentPath = currentPath + change.position
                                    },
                                    onDragEnd = {
                                        if (currentPath.size >= 2) {
                                            paths = paths + SignaturePath(currentPath, penColor, strokeWidth)
                                        }
                                        currentPath = emptyList()
                                    },
                                    onDragCancel = {
                                        currentPath = emptyList()
                                    }
                                )
                            }
                    ) {
                        canvasSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())

                        // Baseline guide
                        val baselineY = size.height * 0.78f
                        drawLine(
                            color = Color(0xFFD0D7DE),
                            start = Offset(24f, baselineY),
                            end = Offset(size.width - 24f, baselineY),
                            strokeWidth = 1.dp.toPx()
                        )

                        // Completed paths
                        for (sp in paths) {
                            if (sp.points.size >= 2) {
                                val p = Path().apply {
                                    moveTo(sp.points[0].x, sp.points[0].y)
                                    for (i in 1 until sp.points.size) {
                                        val prev = sp.points[i - 1]
                                        val curr = sp.points[i]
                                        quadraticBezierTo(prev.x, prev.y, (prev.x + curr.x) / 2f, (prev.y + curr.y) / 2f)
                                    }
                                    lineTo(sp.points.last().x, sp.points.last().y)
                                }
                                drawPath(
                                    path = p,
                                    color = sp.color,
                                    style = Stroke(
                                        width = sp.strokeWidth,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }

                        // Current active path
                        if (currentPath.size >= 2) {
                            val cp = Path().apply {
                                moveTo(currentPath[0].x, currentPath[0].y)
                                for (i in 1 until currentPath.size) {
                                    val prev = currentPath[i - 1]
                                    val curr = currentPath[i]
                                    quadraticBezierTo(prev.x, prev.y, (prev.x + curr.x) / 2f, (prev.y + curr.y) / 2f)
                                }
                                lineTo(currentPath.last().x, currentPath.last().y)
                            }
                            drawPath(
                                path = cp,
                                color = penColor,
                                style = Stroke(
                                    width = strokeWidth,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }

                    if (paths.isEmpty() && currentPath.isEmpty()) {
                        Text(
                            text = "Sign on the line above",
                            color = Color(0xFFAAAAAA),
                            fontSize = 13.sp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 14.dp)
                        )
                    }
                }

                // Color picker & stroke width
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Ink:", color = colors.textMuted, fontSize = 12.sp)
                        colorOptions.forEach { col ->
                            val isSel = penColor == col
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(col)
                                    .border(
                                        width = if (isSel) 2.5.dp else 1.dp,
                                        color = if (isSel) colors.accent else Color.LightGray,
                                        shape = CircleShape
                                    )
                                    .clickable { penColor = col }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(3f to "Fine", 5f to "Med", 8f to "Bold").forEach { (w, lbl) ->
                            val isSel = strokeWidth == w
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) colors.accent.copy(alpha = 0.2f) else colors.cardSubtle)
                                    .border(1.dp, if (isSel) colors.accent else colors.cardBorder, RoundedCornerShape(6.dp))
                                    .clickable { strokeWidth = w }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(lbl, color = if (isSel) colors.accent else colors.textPrimary, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Save to library checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { saveToLibrary = !saveToLibrary }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = saveToLibrary,
                        onCheckedChange = { saveToLibrary = it },
                        colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save to My Signatures for future use", color = colors.textPrimary, fontSize = 12.sp)
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                    ) {
                        Text("Cancel", color = colors.textPrimary)
                    }

                    Button(
                        onClick = {
                            val w = canvasSize.width.takeIf { it > 0 } ?: 600
                            val h = canvasSize.height.takeIf { it > 0 } ?: 300
                            val bmp = SignatureEngine.pathsToBitmap(paths, w, h)
                            if (bmp != null) {
                                onSignatureConfirmed(bmp, saveToLibrary)
                            }
                        },
                        enabled = paths.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                    ) {
                        Text("Use Signature", color = Color.White)
                    }
                }
            }
        }
    }
}
