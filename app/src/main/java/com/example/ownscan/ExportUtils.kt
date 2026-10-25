package com.example.ownscan

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// -------------------------------------------------------------
// EXPORT PIPELINE
// -------------------------------------------------------------

fun decodeFileCapped(path: String, maxSide: Int = 4096): Bitmap? {
    val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, boundsOptions)
    val width = boundsOptions.outWidth
    val height = boundsOptions.outHeight
    if (width <= 0 || height <= 0) return null

    val longestSide = maxOf(width, height)
    var sampleSize = 1
    if (longestSide > maxSide) {
        while ((longestSide / sampleSize) > maxSide) {
            sampleSize *= 2
        }
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
    }
    return BitmapFactory.decodeFile(path, decodeOptions)
}

fun exportPagesAsPdf(context: Context, baseName: String, files: List<File>): Uri? {
    if (files.isEmpty()) return null

    val pdfDocument = PdfDocument()
    try {
        val a4Width = 595
        val a4Height = 842

        files.forEachIndexed { index, file ->
            val bitmap = decodeFileCapped(file.absolutePath) ?: return@forEachIndexed
            try {
                val isLandscape = bitmap.width > bitmap.height
                val pageWidth = if (isLandscape) a4Height else a4Width
                val pageHeight = if (isLandscape) a4Width else a4Height

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                val bmpAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                val pageAspect = pageWidth.toFloat() / pageHeight.toFloat()
                val (destW, destH) = if (bmpAspect > pageAspect) {
                    pageWidth.toFloat() to (pageWidth.toFloat() / bmpAspect)
                } else {
                    (pageHeight.toFloat() * bmpAspect) to pageHeight.toFloat()
                }
                val left = (pageWidth - destW) / 2f
                val top = (pageHeight - destH) / 2f

                page.canvas.drawColor(android.graphics.Color.WHITE)
                page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + destW, top + destH), null)
                pdfDocument.finishPage(page)
            } finally {
                bitmap.recycle()
            }
        }

        val fileName = "$baseName.pdf"
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScanVault")
            }
            val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (targetUri != null) {
                resolver.openOutputStream(targetUri)?.use { out ->
                    pdfDocument.writeTo(out)
                }
            }
            return targetUri
        } else {
            val targetDir = getExportTargetDir(Environment.DIRECTORY_DOWNLOADS)
            targetDir.mkdirs()
            val file = File(targetDir, fileName)
            file.outputStream().use { out ->
                pdfDocument.writeTo(out)
            }
            return Uri.fromFile(file)
        }
    } catch (e: Exception) {
        android.util.Log.e("ScanVault", "exportPagesAsPdf failed", e)
        return null
    } finally {
        pdfDocument.close()
    }
}

fun exportSinglePageJpg(context: Context, baseName: String, file: File, pageNumber: Int): Uri? {
    return try {
        val fileName = "${baseName}_page_${pageNumber}.jpg"
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScanVault")
            }
            val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (targetUri != null) {
                resolver.openOutputStream(targetUri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
            }
            targetUri
        } else {
            val targetDir = getExportTargetDir(Environment.DIRECTORY_DOWNLOADS)
            targetDir.mkdirs()
            val outFile = File(targetDir, fileName)
            outFile.outputStream().use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            Uri.fromFile(outFile)
        }
    } catch (e: Exception) {
        android.util.Log.e("ScanVault", "exportSinglePageJpg failed", e)
        null
    }
}

fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
    if (file.name.endsWith(".enc", ignoreCase = true)) return null
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        var sampleSize = 1
        while (options.outWidth / sampleSize > reqWidth || options.outHeight / sampleSize > reqHeight) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    } catch (e: Exception) {
        null
    }
}

fun openUri(activity: Activity, uri: Uri, mimeType: String) {
    try {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(viewIntent, "Open with..."))
    } catch (e: Exception) {
        Toast.makeText(activity, "No viewer app found", Toast.LENGTH_SHORT).show()
    }
}

fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Just now"
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    return formatter.format(date)
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 KB"
    val kb = bytes / 1024.0     
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.getDefault(), "%.1f MB", mb)
    } else {
        String.format(Locale.getDefault(), "%.0f KB", kb)
    }
}       