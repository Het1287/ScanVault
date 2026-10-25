package com.example.ownscan

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

// -------------------------------------------------------------
// SAVE TO DEVICE MANAGER (JPG & PDF via MediaStore)
// -------------------------------------------------------------

sealed class SaveToDeviceResult {
    data class Success(
        val savedCount: Int,
        val firstUri: Uri?,
        val destinationPath: String,
        val mimeType: String
    ) : SaveToDeviceResult()

    data class Failure(
        val message: String
    ) : SaveToDeviceResult()
}

object SaveToDeviceManager {

    private const val FOLDER_PICTURES = "Pictures/ScanVault"
    private const val FOLDER_DOCUMENTS = "Documents/ScanVault"

    /**
     * Checks if storage permission is required and granted (only on Android 9 and below).
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Finds an available display name that doesn't collide with existing files in MediaStore or disk.
     */
    private fun findAvailableFileName(
        context: Context,
        collectionUri: Uri,
        relativeDir: String,
        baseName: String,
        extension: String
    ): String {
        val resolver = context.contentResolver
        val relPathWithSlash = if (relativeDir.endsWith("/")) relativeDir else "$relativeDir/"

        var counter = 0
        while (true) {
            val candidate = if (counter == 0) "$baseName.$extension" else "$baseName ($counter).$extension"
            var exists = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf(candidate, relPathWithSlash)
                try {
                    resolver.query(collectionUri, projection, selection, selectionArgs, null)?.use { cursor ->
                        if (cursor.count > 0) exists = true
                    }
                } catch (_: Exception) {}
            } else {
                val publicDir = if (extension.equals("jpg", ignoreCase = true) || extension.equals("jpeg", ignoreCase = true)) {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                } else {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                }
                val targetFile = File(publicDir, "ScanVault/$candidate")
                if (targetFile.exists()) exists = true
            }

            if (!exists) {
                return candidate
            }
            counter++
        }
    }

    /**
     * Atomically resolves page images for a document (decrypting into session if locked).
     */
    private suspend fun resolveEffectivePages(context: Context, doc: LocalDoc, customPages: List<File>?): List<File> {
        val rawPages = customPages ?: doc.imageFiles
        if (!doc.isLocked) return rawPages

        val sessionDir = LockedDocCryptoManager.getUnlockedSessionDir(context, doc.folderName)
            ?: LockedDocCryptoManager.prepareUnlockedSession(context, doc.directory)

        return rawPages.map { file ->
            if (file.name.endsWith(".enc", ignoreCase = true)) {
                val plainName = file.name.removeSuffix(".enc")
                File(sessionDir, plainName)
            } else {
                File(sessionDir, file.name)
            }
        }.filter { it.exists() && it.length() > 0L }
    }

    /**
     * Saves a document as individual JPG images into Pictures/ScanVault.
     * Single-page doc: "<DocName>.jpg".
     * Multi-page doc: "<DocName>_page_<n>.jpg".
     */
    suspend fun saveDocAsJpg(
        context: Context,
        doc: LocalDoc,
        customPages: List<File>? = null,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): SaveToDeviceResult = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) {
            return@withContext SaveToDeviceResult.Failure("Storage permission required on this Android version")
        }

        try {
            val pages = resolveEffectivePages(context, doc, customPages)
            if (pages.isEmpty()) {
                return@withContext SaveToDeviceResult.Failure("No pages found to save")
            }

            val safeDocName = sanitizeName(doc.displayName).ifBlank { "Document" }
            val resolver = context.contentResolver
            val collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val savedUris = mutableListOf<Uri>()

            val isSinglePage = pages.size == 1

            for ((index, pageFile) in pages.withIndex()) {
                onProgress(index + 1, pages.size)
                if (!pageFile.exists() || pageFile.length() == 0L) {
                    throw IOException("Cannot export empty page: ${pageFile.name}")
                }

                val basePageName = if (isSinglePage) safeDocName else "${safeDocName}_page_${index + 1}"
                val finalFileName = findAvailableFileName(
                    context = context,
                    collectionUri = collectionUri,
                    relativeDir = FOLDER_PICTURES,
                    baseName = basePageName,
                    extension = "jpg"
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER_PICTURES)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }

                    val itemUri = resolver.insert(collectionUri, values)
                        ?: throw IOException("Failed to create MediaStore entry for $finalFileName")

                    try {
                        resolver.openOutputStream(itemUri)?.use { outStream ->
                            val copied = FileInputStream(pageFile).use { inStream ->
                                inStream.copyTo(outStream)
                            }
                            outStream.flush()
                            if (copied <= 0L) {
                                throw IOException("Exported image is empty: $finalFileName")
                            }
                        } ?: throw IOException("Failed to open output stream for $finalFileName")

                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(itemUri, values, null, null)
                        savedUris.add(itemUri)
                    } catch (e: Exception) {
                        try {
                            resolver.delete(itemUri, null, null)
                        } catch (_: Exception) {}
                        throw e
                    }
                } else {
                    val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ScanVault")
                    if (!targetDir.exists()) targetDir.mkdirs()
                    val targetFile = File(targetDir, finalFileName)
                    val tempFile = File(targetDir, "${finalFileName}.tmp")

                    try {
                        FileOutputStream(tempFile).use { outStream ->
                            val copied = FileInputStream(pageFile).use { inStream ->
                                inStream.copyTo(outStream)
                            }
                            outStream.flush()
                            if (copied <= 0L) {
                                throw IOException("Exported image is empty: $finalFileName")
                            }
                        }
                        if (!tempFile.exists() || tempFile.length() == 0L) {
                            tempFile.delete()
                            throw IOException("Exported file is empty: $finalFileName")
                        }
                        val ok = if (tempFile.renameTo(targetFile)) {
                            true
                        } else {
                            tempFile.copyTo(targetFile, overwrite = true)
                            tempFile.delete()
                            true
                        }
                        if (ok && targetFile.exists() && targetFile.length() > 0L) {
                            savedUris.add(Uri.fromFile(targetFile))
                        } else {
                            targetFile.delete()
                            throw IOException("Failed to save $finalFileName")
                        }
                    } catch (e: Exception) {
                        if (tempFile.exists()) tempFile.delete()
                        if (targetFile.exists()) targetFile.delete()
                        throw e
                    }
                }
            }

            SaveToDeviceResult.Success(
                savedCount = savedUris.size,
                firstUri = savedUris.firstOrNull(),
                destinationPath = FOLDER_PICTURES,
                mimeType = "image/jpeg"
            )
        } catch (e: Exception) {
            SaveToDeviceResult.Failure(e.localizedMessage ?: "Failed to save images to device")
        }
    }

    /**
     * Saves a document as a PDF into Documents/ScanVault.
     * Reuses PasswordPdfExporter.generatePdfFile for identical rendering and page order.
     */
    suspend fun saveDocAsPdf(
        context: Context,
        doc: LocalDoc,
        customPages: List<File>? = null,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): SaveToDeviceResult = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) {
            return@withContext SaveToDeviceResult.Failure("Storage permission required on this Android version")
        }

        var tempPdfFile: File? = null
        try {
            onProgress(1, 2)
            val pages = resolveEffectivePages(context, doc, customPages)
            if (pages.isEmpty()) {
                return@withContext SaveToDeviceResult.Failure("No pages found to export")
            }

            val safeDocName = sanitizeName(doc.displayName).ifBlank { "Document" }

            // Generate identical PDF using existing engine
            tempPdfFile = PasswordPdfExporter.generatePdfFile(
                context = context,
                baseName = safeDocName,
                imageFiles = pages
            )
            if (tempPdfFile == null || !tempPdfFile.exists() || tempPdfFile.length() == 0L) {
                tempPdfFile?.delete()
                return@withContext SaveToDeviceResult.Failure("Couldn't prepare the file")
            }

            onProgress(2, 2)

            val resolver = context.contentResolver
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val finalFileName = findAvailableFileName(
                context = context,
                collectionUri = collectionUri,
                relativeDir = FOLDER_DOCUMENTS,
                baseName = safeDocName,
                extension = "pdf"
            )

            val savedUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER_DOCUMENTS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val itemUri = resolver.insert(collectionUri, values)
                    ?: throw IOException("Failed to create MediaStore entry for $finalFileName")

                try {
                    resolver.openOutputStream(itemUri)?.use { outStream ->
                        val copied = FileInputStream(tempPdfFile).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                        outStream.flush()
                        if (copied <= 0L) {
                            throw IOException("Exported PDF is empty")
                        }
                    } ?: throw IOException("Failed to open output stream for $finalFileName")

                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                    itemUri
                } catch (e: Exception) {
                    try {
                        resolver.delete(itemUri, null, null)
                    } catch (_: Exception) {}
                    throw e
                }
            } else {
                val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ScanVault")
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, finalFileName)
                val tempFile = File(targetDir, "${finalFileName}.tmp")

                try {
                    FileOutputStream(tempFile).use { outStream ->
                        val copied = FileInputStream(tempPdfFile).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                        outStream.flush()
                        if (copied <= 0L) {
                            throw IOException("Exported PDF is empty")
                        }
                    }
                    if (!tempFile.exists() || tempFile.length() == 0L) {
                        tempFile.delete()
                        throw IOException("Exported PDF is empty")
                    }
                    val ok = if (tempFile.renameTo(targetFile)) {
                        true
                    } else {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                        true
                    }
                    if (ok && targetFile.exists() && targetFile.length() > 0L) {
                        Uri.fromFile(targetFile)
                    } else {
                        targetFile.delete()
                        throw IOException("Failed to save $finalFileName")
                    }
                } catch (e: Exception) {
                    if (tempFile.exists()) tempFile.delete()
                    if (targetFile.exists()) targetFile.delete()
                    throw e
                }
            }

            SaveToDeviceResult.Success(
                savedCount = 1,
                firstUri = savedUri,
                destinationPath = FOLDER_DOCUMENTS,
                mimeType = "application/pdf"
            )
        } catch (e: Exception) {
            SaveToDeviceResult.Failure(e.localizedMessage ?: "Failed to save PDF to device")
        } finally {
            // Delete working cache copies immediately
            if (tempPdfFile != null && tempPdfFile.exists()) {
                tempPdfFile.delete()
            }
        }
    }

    /**
     * Saves multiple documents to device as either JPGs or PDFs.
     */
    suspend fun saveMultipleDocs(
        context: Context,
        docs: List<LocalDoc>,
        asPdf: Boolean,
        onProgress: (currentDoc: Int, totalDocs: Int, currentDocName: String) -> Unit = { _, _, _ -> }
    ): SaveToDeviceResult = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) {
            return@withContext SaveToDeviceResult.Failure("Storage permission required on this Android version")
        }

        var totalSaved = 0
        var firstSavedUri: Uri? = null
        val total = docs.size

        for ((index, doc) in docs.withIndex()) {
            onProgress(index + 1, total, doc.displayName)
            val res = if (asPdf) {
                saveDocAsPdf(context, doc)
            } else {
                saveDocAsJpg(context, doc)
            }
            when (res) {
                is SaveToDeviceResult.Success -> {
                    totalSaved += res.savedCount
                    if (firstSavedUri == null) {
                        firstSavedUri = res.firstUri
                    }
                }
                is SaveToDeviceResult.Failure -> {
                    return@withContext SaveToDeviceResult.Failure("Failed on '${doc.displayName}': ${res.message}")
                }
            }
        }

        SaveToDeviceResult.Success(
            savedCount = totalSaved,
            firstUri = firstSavedUri,
            destinationPath = if (asPdf) FOLDER_DOCUMENTS else FOLDER_PICTURES,
            mimeType = if (asPdf) "application/pdf" else "image/jpeg"
        )
    }

    /**
     * Launches an external viewer intent for the saved file.
     */
    fun openSavedFile(context: Context, uri: Uri, mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open file"))
        } catch (_: Exception) {
            Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
        }
    }
}
