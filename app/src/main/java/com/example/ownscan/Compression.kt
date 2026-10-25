package com.example.ownscan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

// -------------------------------------------------------------
// CUSTOM COMPRESSION (PRESETS, TARGET KB & SLIDER) - PHASE 10a
// -------------------------------------------------------------

enum class CompressionMode(val title: String) {
    PRESETS("Govt Presets"),
    CUSTOM_KB("Target KB"),
    SLIDER("Quality %")
}

enum class CompressionPreset(val label: String, val badge: String, val description: String, val targetBytes: Long?) {
    NONE("Normal", "", "Standard compression with quality slider", null),
    SIGNATURE("Signature", "10-20 KB", "3.5×1.5 cm (7:3 ratio), ≤18 KB. Fits Govt exam portals", 18 * 1024L),
    UNDER_50_KB("< 50 KB", "Max 48 KB", "Compress strictly under 50 KB for strict upload portals", 48 * 1024L),
    UNDER_100_KB("< 100 KB", "Max 95 KB", "Compress strictly under 100 KB for standard web forms", 95 * 1024L),
    UNDER_200_KB("< 200 KB", "Max 190 KB", "Compress strictly under 200 KB for PDF/docs", 190 * 1024L)
}

fun cropToAspectRatio(source: Bitmap, targetAspectWidth: Float, targetAspectHeight: Float): Bitmap {
    val srcWidth = source.width.toFloat()
    val srcHeight = source.height.toFloat()
    val targetRatio = targetAspectWidth / targetAspectHeight
    val srcRatio = srcWidth / srcHeight

    val cropWidth: Int
    val cropHeight: Int
    if (srcRatio > targetRatio) {
        cropHeight = source.height
        cropWidth = (cropHeight * targetRatio).toInt().coerceAtMost(source.width)
    } else {
        cropWidth = source.width
        cropHeight = (cropWidth / targetRatio).toInt().coerceAtMost(source.height)
    }

    val startX = ((source.width - cropWidth) / 2).coerceAtLeast(0)
    val startY = ((source.height - cropHeight) / 2).coerceAtLeast(0)

    return Bitmap.createBitmap(source, startX, startY, cropWidth, cropHeight)
}

fun performCustomCompression(
    context: Context,
    uri: Uri,
    desiredName: String,
    qualityPercent: Int = 60,
    targetBytesLimit: Long? = null,
    preset: CompressionPreset = CompressionPreset.NONE
): ToolCompletionResult? {
    val mimeType = context.contentResolver.getType(uri) ?: ""
    val originalBytes = getUriByteSize(context, uri)

    val effectiveTargetBytes = when {
        targetBytesLimit != null && targetBytesLimit > 0L -> targetBytesLimit
        preset.targetBytes != null -> preset.targetBytes
        else -> if (originalBytes > 0) {
            val targetRatio = (qualityPercent / 100f).coerceIn(0.15f, 0.85f)
            ((originalBytes * targetRatio) * 0.80f).toLong().coerceAtLeast(1024L)
        } else 0L
    }

    if (mimeType.contains("pdf", ignoreCase = true)) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        try {
            PDFBoxResourceLoader.init(context)
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            renderer = PdfRenderer(pfd)
            val numPages = renderer.pageCount.coerceAtLeast(1)

            val targetBytes = if (effectiveTargetBytes > 0L) {
                if (originalBytes > 0L) effectiveTargetBytes.coerceAtMost((originalBytes * 0.95f).toLong().coerceAtLeast(1024L)) else effectiveTargetBytes
            } else 0L

            fun buildCompressedPdf(scaleMult: Float, qualityVal: Int): ByteArray {
                val doc = PDDocument()
                try {
                    val scaleFactor = ((0.50f + (qualityVal / 100f) * 0.65f) * scaleMult).coerceIn(0.35f, 1.4f)
                    val jpegQuality = (qualityVal * 0.9f).toInt().coerceIn(12, 80)

                    for (i in 0 until numPages) {
                        val page = renderer.openPage(i)
                        val pw = (page.width * scaleFactor).toInt().coerceAtLeast(1)
                        val ph = (page.height * scaleFactor).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pageWidthPt = page.width.toFloat()
                        val pageHeightPt = page.height.toFloat()
                        page.close()

                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                        bitmap.recycle()
                        val jpegBytes = baos.toByteArray()

                        val pdPage = PDPage(PDRectangle(pageWidthPt, pageHeightPt))
                        doc.addPage(pdPage)
                        val pdImage = JPEGFactory.createFromStream(doc, ByteArrayInputStream(jpegBytes))
                        val cs = PDPageContentStream(doc, pdPage)
                        cs.drawImage(pdImage, 0f, 0f, pageWidthPt, pageHeightPt)
                        cs.close()
                    }

                    val outBaos = ByteArrayOutputStream()
                    doc.save(outBaos)
                    return outBaos.toByteArray()
                } finally {
                    doc.close()
                }
            }

            var compressedBytes = buildCompressedPdf(1.0f, qualityPercent)

            var pass = 0
            while (pass < 3 && originalBytes > 0 && (compressedBytes.size >= originalBytes || (targetBytes > 0 && compressedBytes.size > targetBytes * 1.10f))) {
                pass++
                val mult = if (pass == 1) 0.70f else 0.45f
                val adjQuality = (qualityPercent * (if (pass == 1) 0.75f else 0.45f)).toInt().coerceIn(10, 60)
                val retryBytes = buildCompressedPdf(mult, adjQuality)
                if (retryBytes.isNotEmpty()) {
                    compressedBytes = retryBytes
                    if (compressedBytes.size < originalBytes && (targetBytes == 0L || compressedBytes.size <= targetBytes * 1.05f)) {
                        break
                    }
                }
            }

            val fileName = "$desiredName.pdf"
            var targetUri: Uri? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScanVault")
                }
                targetUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (targetUri != null) {
                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(compressedBytes)
                    }
                }
            } else {
                val targetDir = getExportTargetDir(Environment.DIRECTORY_DOWNLOADS)
                val outFile = File(targetDir, fileName)
                outFile.outputStream().use { out -> out.write(compressedBytes) }
                targetUri = Uri.fromFile(outFile)
            }

            val newBytes = if (targetUri != null) getUriByteSize(context, targetUri) else 0L

            val resultTitle = if (preset != CompressionPreset.NONE) "${preset.label} PDF Created!" else "PDF Compressed!"

            return if (targetUri != null) {
                ToolCompletionResult(
                    title = resultTitle,
                    fileName = fileName,
                    uri = targetUri,
                    mimeType = "application/pdf",
                    originalBytes = originalBytes,
                    newBytes = newBytes
                )
            } else null
        } catch (e: Exception) {
            android.util.Log.e("ScanVault", "performCustomCompression failed", e)
            return null
        } finally {
            renderer?.close()
            pfd?.close()
        }
    } else {
        try {
            var baseBitmap = IdCardGenerator.decodeUriWithExif(context, uri, 3000, 3000) ?: return null

            if (preset == CompressionPreset.SIGNATURE) {
                // 3.5 x 1.5 cm (7:3 ratio)
                val cropped = cropToAspectRatio(baseBitmap, 3.5f, 1.5f)
                if (cropped != baseBitmap) baseBitmap.recycle()
                val maxW = 560
                val maxH = 240
                baseBitmap = if (cropped.width > maxW || cropped.height > maxH) {
                    val f = Math.min(maxW.toFloat() / cropped.width, maxH.toFloat() / cropped.height)
                    val scaled = Bitmap.createScaledBitmap(cropped, (cropped.width * f).toInt().coerceAtLeast(1), (cropped.height * f).toInt().coerceAtLeast(1), true)
                    if (scaled != cropped) cropped.recycle()
                    scaled
                } else {
                    cropped
                }
            } else {
                val maxDim = if (effectiveTargetBytes in 1..102400) 1024 else (720 + (qualityPercent * 12)).coerceIn(480, 2048)
                if (baseBitmap.width > maxDim || baseBitmap.height > maxDim) {
                    val f = maxDim.toFloat() / Math.max(baseBitmap.width, baseBitmap.height)
                    val scaled = Bitmap.createScaledBitmap(baseBitmap, (baseBitmap.width * f).toInt().coerceAtLeast(1), (baseBitmap.height * f).toInt().coerceAtLeast(1), true)
                    if (scaled != baseBitmap) baseBitmap.recycle()
                    baseBitmap = scaled
                }
            }

            val targetBytes = if (effectiveTargetBytes > 0L) {
                if (originalBytes > 0L) effectiveTargetBytes.coerceAtMost((originalBytes * 0.95f).toLong().coerceAtLeast(1024L)) else effectiveTargetBytes
            } else {
                ((originalBytes * (qualityPercent / 100f) * 0.85f).toLong().coerceAtLeast(1024L))
            }

            var scale = 1.0f
            var quality = if (preset == CompressionPreset.SIGNATURE) 78 else qualityPercent.coerceIn(15, 85)
            var bestBytes = ByteArray(0)

            for (iteration in 0..7) {
                val currentW = (baseBitmap.width * scale).toInt().coerceAtLeast(1)
                val currentH = (baseBitmap.height * scale).toInt().coerceAtLeast(1)
                val workingBitmap = if (currentW != baseBitmap.width || currentH != baseBitmap.height) {
                    Bitmap.createScaledBitmap(baseBitmap, currentW, currentH, true)
                } else {
                    baseBitmap
                }

                val baos = ByteArrayOutputStream()
                workingBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                if (workingBitmap != baseBitmap) {
                    workingBitmap.recycle()
                }
                bestBytes = baos.toByteArray()

                val isSmallerThanOriginal = originalBytes <= 0 || bestBytes.size < originalBytes
                val isUnderTarget = targetBytes <= 0 || bestBytes.size <= targetBytes

                if (isSmallerThanOriginal && isUnderTarget) {
                    break
                }

                if (quality > 25) {
                    quality = (quality - 15).coerceAtLeast(12)
                } else {
                    scale = (scale * 0.75f).coerceAtLeast(0.15f)
                    quality = 18
                }
            }

            baseBitmap.recycle()

            if (bestBytes.isEmpty()) return null

            val fileName = "$desiredName.jpg"
            var targetUri: Uri? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScanVault")
                }
                targetUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (targetUri != null) {
                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(bestBytes)
                    }
                }
            } else {
                val targetDir = getExportTargetDir(Environment.DIRECTORY_DOWNLOADS)
                val outFile = File(targetDir, fileName)
                outFile.outputStream().use { out -> out.write(bestBytes) }
                targetUri = Uri.fromFile(outFile)
            }

            val newBytes = if (targetUri != null) getUriByteSize(context, targetUri) else 0L

            val resultTitle = when (preset) {
                CompressionPreset.SIGNATURE -> "Signature Formatted!"
                CompressionPreset.UNDER_50_KB, CompressionPreset.UNDER_100_KB, CompressionPreset.UNDER_200_KB -> "${preset.label} File Ready!"
                else -> "Image Compressed!"
            }

            return if (targetUri != null) {
                ToolCompletionResult(
                    title = resultTitle,
                    fileName = fileName,
                    uri = targetUri,
                    mimeType = "image/jpeg",
                    originalBytes = originalBytes,
                    newBytes = newBytes
                )
            } else null
        } catch (e: Exception) {
            android.util.Log.e("ScanVault", "performCustomCompression failed", e)
            return null
        }
    }
}

