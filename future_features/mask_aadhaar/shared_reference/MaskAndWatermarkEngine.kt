package com.example.ownscan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

    val AADHAAR_GROUPED_REGEX = Regex("""\b(\d{4})[\s-](\d{4})[\s-](\d{4})\b""")
    val AADHAAR_12_DIGIT_REGEX = Regex("""\b(\d{12})\b""")

    val WATERMARK_PRESETS = listOf(
        "FOR KYC ONLY",
        "CONFIDENTIAL",
        "OFFICIAL USE ONLY",
        "VERIFIED COPY",
        "SELF ATTESTED"
    )

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
     * Processes an image bitmap with optional Aadhaar masking and optional watermark.
     */
    suspend fun processImage(
        context: Context,
        source: Bitmap,
        maskAadhaar: Boolean,
        watermarkText: String?,
        watermarkOpacity: Int = 26
    ): Pair<Bitmap, Int> {
        val (maskedBitmap, maskCount) = if (maskAadhaar) {
            detectAndMaskAadhaar(context, source)
        } else {
            Pair(source, 0)
        }

        val finalBitmap = if (!watermarkText.isNullOrBlank()) {
            val watermarked = applyWatermark(maskedBitmap, watermarkText, watermarkOpacity, isTiled = true)
            if (watermarked != maskedBitmap && maskedBitmap != source) {
                maskedBitmap.recycle()
            }
            watermarked
        } else {
            maskedBitmap
        }

        return Pair(finalBitmap, maskCount)
    }

    /**
     * Processes any input Uri (PDF or image) with Aadhaar masking and watermarking,
     * and exports as a protected PDF to Downloads/ScanVault.
     */
    suspend fun exportAsPdf(
        context: Context,
        inputUri: Uri,
        desiredName: String,
        maskAadhaar: Boolean,
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

                        val (processedBmp, _) = processImage(context, bmp, maskAadhaar, watermarkText, watermarkOpacity)
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
                val (processedBmp, _) = processImage(context, bitmap, maskAadhaar, watermarkText, watermarkOpacity)
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
                val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ScanVault").apply { mkdirs() }
                val outFile = File(targetDir, fileName)
                outFile.outputStream().use { it.write(pdfBytes) }
                targetUri = Uri.fromFile(outFile)
            }

            val newBytes = if (targetUri != null) getUriByteSize(context, targetUri) else 0L

            if (targetUri != null) {
                val title = if (maskAadhaar && !watermarkText.isNullOrBlank()) "Masked & Watermarked PDF!"
                else if (maskAadhaar) "Masked Aadhaar PDF Ready!"
                else "Watermarked PDF Ready!"

                ToolCompletionResult(
                    title = title,
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
     * Processes an image Uri with Aadhaar masking and watermarking,
     * and exports as a JPEG to Downloads/ScanVault.
     */
    suspend fun exportAsJpg(
        context: Context,
        inputUri: Uri,
        desiredName: String,
        maskAadhaar: Boolean,
        watermarkText: String?,
        watermarkOpacity: Int = 26
    ): ToolCompletionResult? = withContext(Dispatchers.IO) {
        val originalBytes = getUriByteSize(context, inputUri)
        val fileName = "$desiredName.jpg"

        try {
            val bitmap = IdCardGenerator.decodeUriWithExif(context, inputUri, 3000, 3000) ?: return@withContext null
            val (processedBmp, _) = processImage(context, bitmap, maskAadhaar, watermarkText, watermarkOpacity)
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
                val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ScanVault").apply { mkdirs() }
                val outFile = File(targetDir, fileName)
                outFile.outputStream().use { it.write(jpgBytes) }
                targetUri = Uri.fromFile(outFile)
            }

            val newBytes = if (targetUri != null) getUriByteSize(context, targetUri) else 0L

            if (targetUri != null) {
                val title = if (maskAadhaar && !watermarkText.isNullOrBlank()) "Masked & Watermarked Image!"
                else if (maskAadhaar) "Masked Aadhaar Image Ready!"
                else "Watermarked Image Ready!"

                ToolCompletionResult(
                    title = title,
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
