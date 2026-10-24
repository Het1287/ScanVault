package com.example.ownscan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Locale

data class SearchableTextElement(
    val text: String,
    val rectInImage: RectF,
    val fontSize: Float
)

object SearchablePdfEngine {

    private const val TAG = "SearchablePdfEngine"

    /**
     * Runs ML Kit OCR on the bitmap to detect all lines/words with their bounding boxes.
     * Supports both Latin and Devanagari scripts.
     */
    suspend fun extractTextElements(context: Context, bitmap: Bitmap): List<SearchableTextElement> = withContext(Dispatchers.Default) {
        val elements = mutableListOf<SearchableTextElement>()
        val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val devanagariRecognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            // 1. Process Latin
            val latinResult = try {
                latinRecognizer.process(inputImage).awaitTask()
            } catch (e: Exception) {
                Log.w(TAG, "Latin OCR extraction failed: ${e.message}")
                null
            }

            // 2. Process Devanagari
            val devResult = try {
                devanagariRecognizer.process(inputImage).awaitTask()
            } catch (e: Exception) {
                Log.w(TAG, "Devanagari OCR extraction failed: ${e.message}")
                null
            }

            fun addFromBlocks(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>) {
                for (block in blocks) {
                    for (line in block.lines) {
                        val lineBox = line.boundingBox
                        val lineText = line.text.trim()
                        if (lineText.isNotEmpty() && lineBox != null) {
                            val h = lineBox.height().toFloat()
                            elements.add(
                                SearchableTextElement(
                                    text = lineText,
                                    rectInImage = RectF(lineBox),
                                    fontSize = (h * 0.85f).coerceIn(8f, 72f)
                                )
                            )
                        }
                    }
                }
            }

            if (latinResult != null && latinResult.textBlocks.isNotEmpty()) {
                addFromBlocks(latinResult.textBlocks)
            } else if (devResult != null && devResult.textBlocks.isNotEmpty()) {
                addFromBlocks(devResult.textBlocks)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting OCR text elements: ${e.message}", e)
        } finally {
            latinRecognizer.close()
            devanagariRecognizer.close()
        }

        elements
    }

    /**
     * Generates a Searchable PDF file with invisible selectable OCR text overlay
     * placed over each page image.
     */
    suspend fun generateSearchablePdfFile(
        context: Context,
        baseName: String,
        imageFiles: List<File>,
        maskAadhaar: Boolean = false,
        watermarkText: String? = null,
        watermarkOpacity: Int = 26
    ): File? = withContext(Dispatchers.IO) {
        if (imageFiles.isEmpty()) return@withContext null

        val pdfDocument = PdfDocument()
        val tempPdf = File(context.cacheDir, "searchable_${System.currentTimeMillis()}_${SecureRandom().nextInt(100000)}.pdf")

        try {
            val a4Width = 595
            val a4Height = 842

            imageFiles.forEachIndexed { index, file ->
                val rawBitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@forEachIndexed

                // Apply Aadhaar masking or watermarking if requested
                val pageBitmap = if (maskAadhaar || !watermarkText.isNullOrBlank()) {
                    val (procBmp, _) = MaskAndWatermarkEngine.processImage(
                        context = context,
                        source = rawBitmap,
                        maskAadhaar = maskAadhaar,
                        watermarkText = watermarkText,
                        watermarkOpacity = watermarkOpacity
                    )
                    if (procBmp != rawBitmap) rawBitmap.recycle()
                    procBmp
                } else {
                    rawBitmap
                }

                // Extract OCR elements before drawing
                val ocrElements = extractTextElements(context, pageBitmap)

                try {
                    val isLandscape = pageBitmap.width > pageBitmap.height
                    val pageWidth = if (isLandscape) a4Height else a4Width
                    val pageHeight = if (isLandscape) a4Width else a4Height

                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas

                    // Calculate aspect-fit image coordinates
                    val bmpAspect = pageBitmap.width.toFloat() / pageBitmap.height.toFloat()
                    val pageAspect = pageWidth.toFloat() / pageHeight.toFloat()
                    val (destW, destH) = if (bmpAspect > pageAspect) {
                        pageWidth.toFloat() to (pageWidth.toFloat() / bmpAspect)
                    } else {
                        (pageHeight.toFloat() * bmpAspect) to pageHeight.toFloat()
                    }
                    val left = (pageWidth - destW) / 2f
                    val top = (pageHeight - destH) / 2f

                    // Draw white background + page bitmap
                    canvas.drawColor(Color.WHITE)
                    canvas.drawBitmap(pageBitmap, null, RectF(left, top, left + destW, top + destH), null)

                    // Overlay invisible selectable text
                    if (ocrElements.isNotEmpty()) {
                        val scaleX = destW / pageBitmap.width.toFloat()
                        val scaleY = destH / pageBitmap.height.toFloat()

                        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            // Transparent ink renders selectable glyphs in Skia PDF without obscuring image
                            color = Color.TRANSPARENT
                        }

                        for (el in ocrElements) {
                            val pdfX = left + (el.rectInImage.left * scaleX)
                            val pdfY = top + (el.rectInImage.bottom * scaleY) // Baseline approximate
                            val scaledFontSize = (el.fontSize * scaleY).coerceIn(6f, 48f)

                            textPaint.textSize = scaledFontSize
                            canvas.drawText(el.text, pdfX, pdfY, textPaint)
                        }
                    }

                    pdfDocument.finishPage(page)
                } finally {
                    pageBitmap.recycle()
                }
            }

            FileOutputStream(tempPdf).use { fos ->
                pdfDocument.writeTo(fos)
            }
            tempPdf
        } catch (e: Exception) {
            Log.e(TAG, "Error building searchable PDF: ${e.message}", e)
            tempPdf.delete()
            null
        } finally {
            pdfDocument.close()
        }
    }

    /**
     * Exports the Searchable PDF to Downloads/ScanVault.
     */
    suspend fun exportSearchablePdf(
        context: Context,
        baseName: String,
        imageFiles: List<File>,
        maskAadhaar: Boolean = false,
        watermarkText: String? = null,
        watermarkOpacity: Int = 26
    ): Uri? = withContext(Dispatchers.IO) {
        val generatedFile = generateSearchablePdfFile(
            context = context,
            baseName = baseName,
            imageFiles = imageFiles,
            maskAadhaar = maskAadhaar,
            watermarkText = watermarkText,
            watermarkOpacity = watermarkOpacity
        ) ?: return@withContext null

        try {
            PasswordPdfExporter.savePdfToDownloads(context, generatedFile, "$baseName.pdf")
        } finally {
            if (generatedFile.exists()) generatedFile.delete()
        }
    }
}
