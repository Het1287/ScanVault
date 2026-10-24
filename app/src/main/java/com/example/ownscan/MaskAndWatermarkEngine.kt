package com.example.ownscan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object MaskAndWatermarkEngine {

    val WATERMARK_PRESETS = listOf(
        "FOR KYC ONLY",
        "CONFIDENTIAL",
        "OFFICIAL USE ONLY",
        "VERIFIED COPY",
        "SELF ATTESTED"
    )

    /**
     * Applies a semi-transparent diagonal watermark across the bitmap.
     * Tiled to ensure full document protection against unauthorized use or cropping.
     */
    fun applyWatermark(
        source: Bitmap,
        watermarkText: String,
        opacityPercent: Int = 26,
        isTiled: Boolean = true
    ): Bitmap {
        if (watermarkText.isBlank()) return source

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val w = output.width.toFloat()
        val h = output.height.toFloat()

        val alpha = (255 * opacityPercent / 100).coerceIn(20, 180)
        val paint = Paint().apply {
            color = android.graphics.Color.argb(alpha, 80, 80, 80)
            textSize = (w / 14f).coerceIn(24f, 120f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        canvas.save()
        canvas.rotate(-35f, w / 2f, h / 2f)

        // Center primary watermark
        canvas.drawText(watermarkText.uppercase(), w / 2f, h / 2f, paint)

        if (isTiled) {
            val spacing = paint.textSize * 3.8f
            canvas.drawText(watermarkText.uppercase(), w / 2f, (h / 2f) - spacing, paint)
            canvas.drawText(watermarkText.uppercase(), w / 2f, (h / 2f) + spacing, paint)
        }

        canvas.restore()
        return output
    }

    /**
     * Processes an image bitmap with optional watermark.
     */
    fun processImage(
        context: Context,
        source: Bitmap,
        watermarkText: String?,
        watermarkOpacity: Int = 26
    ): Bitmap {
        return if (!watermarkText.isNullOrBlank()) {
            applyWatermark(source, watermarkText, watermarkOpacity, isTiled = true)
        } else {
            source
        }
    }

    /**
     * Processes any input Uri (PDF or image) with watermarking,
     * and exports as a protected PDF to Downloads/ScanVault.
     */
    suspend fun exportAsPdf(
        context: Context,
        inputUri: Uri,
        desiredName: String,
        watermarkText: String?,
        watermarkOpacity: Int = 26
    ): ToolCompletionResult? = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(inputUri) ?: ""
        val originalBytes = getUriByteSize(context, inputUri)
        val fileName = "$desiredName.pdf"

        try {
            PDFBoxResourceLoader.init(context)
            val pdDoc = PDDocument()

            if (mimeType.contains("pdf", ignoreCase = true)) {
                val pfd = context.contentResolver.openFileDescriptor(inputUri, "r") ?: return@withContext null
                val renderer = PdfRenderer(pfd)
                try {
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        val w = page.width
                        val h = page.height
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val c = Canvas(bmp)
                        c.drawColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()

                        val processedBmp = processImage(context, bmp, watermarkText, watermarkOpacity)
                        if (processedBmp != bmp) bmp.recycle()

                        val pdPage = PDPage(PDRectangle(w.toFloat(), h.toFloat()))
                        pdDoc.addPage(pdPage)

                        val baos = ByteArrayOutputStream()
                        processedBmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                        processedBmp.recycle()

                        val pdImage = JPEGFactory.createFromStream(pdDoc, ByteArrayInputStream(baos.toByteArray()))
                        val cs = PDPageContentStream(pdDoc, pdPage)
                        cs.drawImage(pdImage, 0f, 0f, w.toFloat(), h.toFloat())
                        cs.close()
                    }
                } finally {
                    renderer.close()
                    pfd.close()
                }
            } else {
                // Image to PDF
                val bitmap = IdCardGenerator.decodeUriWithExif(context, inputUri, 3000, 3000) ?: return@withContext null
                val processedBmp = processImage(context, bitmap, watermarkText, watermarkOpacity)
                if (processedBmp != bitmap) bitmap.recycle()

                val a4Width = 595f
                val a4Height = 842f
                val isLandscape = processedBmp.width > processedBmp.height
                val pageW = if (isLandscape) a4Height else a4Width
                val pageH = if (isLandscape) a4Width else a4Height

                val pdPage = PDPage(PDRectangle(pageW, pageH))
                pdDoc.addPage(pdPage)

                val bmpAspect = processedBmp.width.toFloat() / processedBmp.height.toFloat()
                val pageAspect = pageW / pageH
                val (destW, destH) = if (bmpAspect > pageAspect) {
                    pageW to (pageW / bmpAspect)
                } else {
                    (pageH * bmpAspect) to pageH
                }
                val left = (pageW - destW) / 2f
                val top = (pageH - destH) / 2f

                val baos = ByteArrayOutputStream()
                processedBmp.compress(Bitmap.CompressFormat.JPEG, 88, baos)
                processedBmp.recycle()

                val pdImage = JPEGFactory.createFromStream(pdDoc, ByteArrayInputStream(baos.toByteArray()))
                val cs = PDPageContentStream(pdDoc, pdPage)
                cs.drawImage(pdImage, left, top, destW, destH)
                cs.close()
            }

            val outBaos = ByteArrayOutputStream()
            pdDoc.save(outBaos)
            pdDoc.close()
            val pdfBytes = outBaos.toByteArray()

            var targetUri: Uri? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScanVault")
                }
                targetUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (targetUri != null) {
                    context.contentResolver.openOutputStream(targetUri)?.use { it.write(pdfBytes) }
                }
            } else {
                val targetDir = getExportTargetDir(Environment.DIRECTORY_DOWNLOADS)
                val outFile = File(targetDir, fileName)
                outFile.outputStream().use { it.write(pdfBytes) }
                targetUri = Uri.fromFile(outFile)
            }

            val newBytes = if (targetUri != null) getUriByteSize(context, targetUri) else 0L

            if (targetUri != null) {
                ToolCompletionResult(
                    title = "Watermarked PDF Ready!",
                    fileName = fileName,
                    uri = targetUri,
                    mimeType = "application/pdf",
                    originalBytes = originalBytes,
                    newBytes = newBytes
                )
            } else null
        } catch (e: Exception) {
            android.util.Log.e("MaskAndWatermarkEngine", "Export as PDF failed: ${e.message}", e)
            null
        }
    }

    /**
     * Processes an image Uri with watermarking,
     * and exports as a JPEG to Downloads/ScanVault.
     */
    suspend fun exportAsJpg(
        context: Context,
        inputUri: Uri,
        desiredName: String,
        watermarkText: String?,
        watermarkOpacity: Int = 26
    ): ToolCompletionResult? = withContext(Dispatchers.IO) {
        val originalBytes = getUriByteSize(context, inputUri)
        val fileName = "$desiredName.jpg"

        try {
            val bitmap = IdCardGenerator.decodeUriWithExif(context, inputUri, 3000, 3000) ?: return@withContext null
            val processedBmp = processImage(context, bitmap, watermarkText, watermarkOpacity)
            if (processedBmp != bitmap) bitmap.recycle()

            val baos = ByteArrayOutputStream()
            processedBmp.compress(Bitmap.CompressFormat.JPEG, 92, baos)
            processedBmp.recycle()
            val jpgBytes = baos.toByteArray()

            var targetUri: Uri? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScanVault")
                }
                targetUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (targetUri != null) {
                    context.contentResolver.openOutputStream(targetUri)?.use { it.write(jpgBytes) }
                }
            } else {
                val targetDir = getExportTargetDir(Environment.DIRECTORY_DOWNLOADS)
                val outFile = File(targetDir, fileName)
                outFile.outputStream().use { it.write(jpgBytes) }
                targetUri = Uri.fromFile(outFile)
            }

            val newBytes = if (targetUri != null) getUriByteSize(context, targetUri) else 0L

            if (targetUri != null) {
                ToolCompletionResult(
                    title = "Watermarked Image Ready!",
                    fileName = fileName,
                    uri = targetUri,
                    mimeType = "image/jpeg",
                    originalBytes = originalBytes,
                    newBytes = newBytes
                )
            } else null
        } catch (e: Exception) {
            android.util.Log.e("MaskAndWatermarkEngine", "Export as JPG failed: ${e.message}", e)
            null
        }
    }
}
