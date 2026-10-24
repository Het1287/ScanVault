package com.example.ownscan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// -------------------------------------------------------------
// PDF UNPACKING & MERGE WORKSPACE
// -------------------------------------------------------------

fun unpackPdfAsInAppDoc(context: Context, pdfUri: Uri, targetFolder: String?, customName: String? = null): LocalDoc? {
    try {
        val time = System.currentTimeMillis()
        val displayName = customName ?: "PDF_${SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())}"
        val parentDir = if (targetFolder != null) File(getUserFoldersDir(context), targetFolder) else getPrivateDocsDir(context)
        val docFolder = File(parentDir, "${time}__$displayName").apply { mkdirs() }

        val pfd = context.contentResolver.openFileDescriptor(pdfUri, "r") ?: return null
        val renderer = PdfRenderer(pfd)
        val createdFiles = mutableListOf<File>()

        for (i in 0 until renderer.pageCount) {
            val page = renderer.openPage(i)
            val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val pageFile = File(docFolder, "img_${UUID.randomUUID()}.jpg")
            pageFile.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            createdFiles.add(pageFile)
            bitmap.recycle()
        }
        renderer.close()
        pfd.close()

        savePageOrder(docFolder, createdFiles)
        return LocalDoc(
            folderName = docFolder.name,
            displayName = displayName,
            timestamp = time,
            pageCount = createdFiles.size,
            imageFiles = createdFiles,
            parentFolder = targetFolder,
            directory = docFolder
        )
    } catch (e: Exception) {
        android.util.Log.e("ScanVault", "unpackPdfAsInAppDoc failed", e)
        return null
    }
}

fun createMergeWorkspaceDoc(context: Context, pdfUris: List<Uri>, customName: String): LocalDoc? {
    try {
        val time = System.currentTimeMillis()
        val displayName = customName.ifEmpty { "Merge_${SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())}" }
        val parentDir = getPrivateDocsDir(context)
        val docFolder = File(parentDir, "${time}__$displayName").apply { mkdirs() }
        val createdFiles = mutableListOf<File>()

        pdfUris.forEach { uri ->
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val renderer = PdfRenderer(pfd)
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    val pageFile = File(docFolder, "img_${UUID.randomUUID()}.jpg")
                    pageFile.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                    createdFiles.add(pageFile)
                    bitmap.recycle()
                }
                renderer.close()
                pfd.close()
            }
        }

        savePageOrder(docFolder, createdFiles)
        return LocalDoc(
            folderName = docFolder.name,
            displayName = displayName,
            timestamp = time,
            pageCount = createdFiles.size,
            imageFiles = createdFiles,
            parentFolder = null,
            directory = docFolder
        )
    } catch (e: Exception) {
        android.util.Log.e("ScanVault", "createMergeWorkspaceDoc failed", e)
        return null
    }
}

fun exportUriListAsPdf(context: Context, baseName: String, uris: List<Uri>): Uri? {
    if (uris.isEmpty()) return null
    val pdfDocument = PdfDocument()
    try {
        val a4Width = 595
        val a4Height = 842

        uris.forEachIndexed { index, uri ->
            val bitmap = IdCardGenerator.decodeUriWithExif(context, uri, 2048, 2048) ?: return@forEachIndexed
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScanVault")
            }
            val targetUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (targetUri != null) {
                context.contentResolver.openOutputStream(targetUri)?.use { out ->
                    pdfDocument.writeTo(out)
                }
            }
            return targetUri
        } else {
            val targetDir = getExportTargetDir(Environment.DIRECTORY_DOWNLOADS)
            val file = File(targetDir, fileName)
            file.outputStream().use { out -> pdfDocument.writeTo(out) }
            return Uri.fromFile(file)
        }
    } catch (e: Exception) {
        android.util.Log.e("ScanVault", "exportUriListAsPdf failed", e)
        return null
    } finally {
        pdfDocument.close()
    }
}

fun importExternalFiles(context: Context, uris: List<Uri>, targetFolder: String?): Int {
    var importedCount = 0
    uris.forEach { uri ->
        val originalName = sanitizeName(queryOriginalFileName(context, uri)).ifEmpty {
            "Doc_${SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())}"
        }
        val mimeType = context.contentResolver.getType(uri) ?: ""
        if (mimeType.contains("pdf", ignoreCase = true)) {
            val doc = unpackPdfAsInAppDoc(context, uri, targetFolder, originalName)
            if (doc != null) importedCount++
        } else {
            savePagesToAppStorage(context, listOf(uri), originalName, targetFolder)
            importedCount++
        }
    }
    return importedCount
}

fun appendImageFileToDoc(
    context: Context,
    docFolder: File,
    baseName: String,
    writePlaintext: (File) -> Unit
): File {
    val meta = DocMeta.readFromDir(docFolder)
    val isLocked = meta.isLocked || File(docFolder, ".dek.enc").exists()

    if (!isLocked) {
        val destFile = File(docFolder, baseName)
        writePlaintext(destFile)
        return destFile
    }

    // Locked document: write to temp file first
    val tempPlain = File(docFolder, "$baseName.tmp")
    writePlaintext(tempPlain)

    // Copy immediately to active session cache if unlocked session exists
    val sessionDir = LockedDocCryptoManager.getUnlockedSessionDir(context, docFolder.name)
    if (sessionDir != null && sessionDir.exists()) {
        try {
            tempPlain.copyTo(File(sessionDir, baseName), overwrite = true)
        } catch (e: Exception) {
            android.util.Log.e("AppendPage", "Failed copying to session dir: ${e.message}")
        }
    }

    val dek = try {
        LockedDocCryptoManager.getOrDecryptDek(docFolder)
    } catch (e: Exception) {
        android.util.Log.e("AppendPage", "Failed to get DEK for locked doc: ${e.message}")
        null
    }

    if (dek != null) {
        val tempEnc = File(docFolder, "$baseName.lock_tmp")
        val finalEnc = File(docFolder, "$baseName.enc")
        try {
            LockedDocCryptoManager.encryptFile(tempPlain, tempEnc, dek)
            if (tempEnc.exists() && LockedDocCryptoManager.verifyEncryptedChunk(tempEnc, dek)) {
                val renamed = if (tempEnc.renameTo(finalEnc)) {
                    true
                } else {
                    tempEnc.copyTo(finalEnc, overwrite = true)
                    tempEnc.delete()
                    true
                }
                if (renamed && finalEnc.exists()) {
                    tempPlain.delete()
                    return finalEnc
                }
            } else {
                tempEnc.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("AppendPage", "Failed encrypting appended page: ${e.message}")
        }
    }

    // Safe fallback: keep unencrypted file as baseName so user data is never lost
    val fallbackFile = File(docFolder, baseName)
    if (tempPlain.exists()) {
        tempPlain.renameTo(fallbackFile)
    }
    return fallbackFile
}

suspend fun movePageToTrash(
    context: Context,
    doc: LocalDoc,
    fileToDelete: File,
    pageIndex: Int
) {
    val stamp = System.currentTimeMillis()
    val trashedFolderName = "${stamp}__${doc.displayName}_Page_${pageIndex + 1}"
    val trashedDir = File(getPrivateDocsDir(context), trashedFolderName)
    trashedDir.mkdirs()

    if (doc.isLocked) {
        val encFile = File(doc.directory, "${fileToDelete.name}.enc")
        if (encFile.exists()) {
            val destEnc = File(trashedDir, "${fileToDelete.name}.enc")
            encFile.renameTo(destEnc)
        }
        val plainInDoc = File(doc.directory, fileToDelete.name)
        if (plainInDoc.exists()) {
            plainInDoc.renameTo(File(trashedDir, fileToDelete.name))
        }
        fileToDelete.delete()

        // Copy .dek.enc into trashedDir so the trashed page can be unlocked if restored
        val dekFile = File(doc.directory, ".dek.enc")
        if (dekFile.exists()) {
            dekFile.copyTo(File(trashedDir, ".dek.enc"), overwrite = true)
        }

        File(trashedDir, "order.txt").writeText(fileToDelete.name)
        DocMeta.writeToDir(trashedDir, DocMeta(isLocked = true, trashedDate = stamp))
    } else {
        val destFile = File(trashedDir, fileToDelete.name)
        fileToDelete.renameTo(destFile)
        File(trashedDir, "order.txt").writeText(fileToDelete.name)
        DocMeta.writeToDir(trashedDir, DocMeta(isLocked = false, trashedDate = stamp))
    }

    DocumentRepository.indexExistingDocs(context)
}

suspend fun movePagesToTrash(
    context: Context,
    doc: LocalDoc,
    pagesToDelete: List<File>
) {
    if (pagesToDelete.isEmpty()) return
    val stamp = System.currentTimeMillis()
    val trashedFolderName = "${stamp}__${doc.displayName}_${pagesToDelete.size}_Pages"
    val trashedDir = File(getPrivateDocsDir(context), trashedFolderName)
    trashedDir.mkdirs()

    val pageNames = mutableListOf<String>()

    if (doc.isLocked) {
        pagesToDelete.forEach { pageFile ->
            val encFile = File(doc.directory, "${pageFile.name}.enc")
            if (encFile.exists()) {
                val destEnc = File(trashedDir, "${pageFile.name}.enc")
                encFile.renameTo(destEnc)
            }
            val plainInDoc = File(doc.directory, pageFile.name)
            if (plainInDoc.exists()) {
                plainInDoc.renameTo(File(trashedDir, pageFile.name))
            }
            pageFile.delete()
            pageNames.add(pageFile.name)
        }

        val dekFile = File(doc.directory, ".dek.enc")
        if (dekFile.exists()) {
            dekFile.copyTo(File(trashedDir, ".dek.enc"), overwrite = true)
        }

        File(trashedDir, "order.txt").writeText(pageNames.joinToString("\n"))
        DocMeta.writeToDir(trashedDir, DocMeta(isLocked = true, trashedDate = stamp))
    } else {
        pagesToDelete.forEach { pageFile ->
            val destFile = File(trashedDir, pageFile.name)
            pageFile.renameTo(destFile)
            pageNames.add(pageFile.name)
        }
        File(trashedDir, "order.txt").writeText(pageNames.joinToString("\n"))
        DocMeta.writeToDir(trashedDir, DocMeta(isLocked = false, trashedDate = stamp))
    }

    DocumentRepository.indexExistingDocs(context)
}

fun appendFilesToDocFolder(context: Context, docFolder: File, uris: List<Uri>): Int {
    var count = 0
    val existingOrder = readPageOrder(docFolder).toMutableList()

    uris.forEach { uri ->
        try {
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val originalName = queryOriginalFileName(context, uri).lowercase(Locale.ROOT)
            val isPdf = mimeType.contains("pdf", ignoreCase = true) || originalName.endsWith(".pdf")

            if (isPdf) {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val renderer = PdfRenderer(pfd)
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()

                        val baseName = "img_${UUID.randomUUID()}.jpg"
                        appendImageFileToDoc(context, docFolder, baseName) { outPlainFile ->
                            outPlainFile.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
                        }
                        existingOrder.add(baseName)
                        bitmap.recycle()
                        count++
                    }
                    renderer.close()
                    pfd.close()
                }
            } else {
                val bitmap = IdCardGenerator.decodeUriWithExif(context, uri, 2048, 2048)
                val baseName = "img_${UUID.randomUUID()}.jpg"
                if (bitmap != null) {
                    appendImageFileToDoc(context, docFolder, baseName) { outPlainFile ->
                        outPlainFile.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
                    }
                    bitmap.recycle()
                    existingOrder.add(baseName)
                    count++
                } else {
                    appendImageFileToDoc(context, docFolder, baseName) { outPlainFile ->
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            outPlainFile.outputStream().use { out -> input.copyTo(out) }
                        }
                    }
                    existingOrder.add(baseName)
                    count++
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AppendFiles", "Failed appending file from URI: ${e.message}")
        }
    }

    val orderFile = File(docFolder, "order.txt")
    orderFile.writeText(existingOrder.joinToString("\n"))
    return count
}

fun getUriByteSize(context: Context, uri: Uri): Long {
    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    } catch (e: Exception) {
        0L
    }
}

