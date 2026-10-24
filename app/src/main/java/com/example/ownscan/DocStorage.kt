package com.example.ownscan

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

// -------------------------------------------------------------
// CORE FILE & STORAGE SYSTEM
// -------------------------------------------------------------

fun sanitizeName(name: String): String {
    var sanitized = name.replace(Regex("[\\\\/:*?\"<>|]"), "")
    while (sanitized.contains("__")) {
        sanitized = sanitized.replace("__", "")
    }
    while (sanitized.contains("..")) {
        sanitized = sanitized.replace("..", ".")
    }
    sanitized = sanitized.trimStart('.').trim()
    if (sanitized.length > 80) {
        sanitized = sanitized.take(80)
    }
    return sanitized
}

fun getPrivateDocsDir(context: Context): File {
    val dir = File(context.filesDir, "documents")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

fun getUserFoldersDir(context: Context): File {
    val dir = File(getPrivateDocsDir(context), "folders")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

fun loadUserFolders(context: Context): List<UserFolder> {
    val dir = getUserFoldersDir(context)
    val list = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
    return list.map { folder ->
        val docCount = folder.listFiles { it.isDirectory }?.size ?: 0
        UserFolder(folder.name, docCount)
    }.sortedBy { it.name.lowercase() }
}

fun createUserFolder(context: Context, name: String): Boolean {
    val sanitized = sanitizeName(name)
    if (sanitized.isBlank()) return false
    val folder = File(getUserFoldersDir(context), sanitized)
    return if (!folder.exists()) folder.mkdirs() else false
}

fun renameUserFolder(context: Context, oldName: String, newName: String): Boolean {
    val sanitized = sanitizeName(newName)
    if (sanitized.isBlank() || sanitized == oldName) return false
    val src = File(getUserFoldersDir(context), oldName)
    val dest = File(getUserFoldersDir(context), sanitized)
    return src.renameTo(dest)
}

fun deleteUserFolder(context: Context, name: String): Boolean {
    val folder = File(getUserFoldersDir(context), name)
    return folder.deleteRecursively()
}

fun savePagesToAppStorage(context: Context, uris: List<Uri>, displayName: String, targetFolder: String?): File? {
    val cleanName = sanitizeName(displayName).ifBlank {
        "Doc_${SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())}"
    }
    val timestamp = System.currentTimeMillis()
    val folderName = "${timestamp}__$cleanName"
    val parentDir = if (targetFolder != null) {
        File(getUserFoldersDir(context), targetFolder)
    } else {
        getPrivateDocsDir(context)
    }
    if (!parentDir.exists()) parentDir.mkdirs()

    val docFolder = File(parentDir, folderName)
    try {
        if (!docFolder.exists()) docFolder.mkdirs()

        val resolver = context.contentResolver
        val createdFiles = mutableListOf<File>()

        uris.forEach { uri ->
            val pageFile = File(docFolder, "img_${UUID.randomUUID()}.jpg")
            val inputStream = resolver.openInputStream(uri) ?: throw IOException("Could not open input stream for $uri")
            inputStream.use { input ->
                pageFile.outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            if (!pageFile.exists() || pageFile.length() == 0L) {
                throw IOException("Failed to write page file: ${pageFile.name}")
            }
            createdFiles.add(pageFile)
        }
        savePageOrder(docFolder, createdFiles)
        DocMeta.writeToDir(docFolder, DocMeta())
        return docFolder
    } catch (e: Exception) {
        android.util.Log.e("ScanVault", "savePagesToAppStorage failed", e)
        if (docFolder.exists()) {
            docFolder.deleteRecursively()
        }
        return null
    }
}

fun loadAllInAppDocuments(context: Context, includeTrashed: Boolean = false): List<LocalDoc> {
    val docs = mutableListOf<LocalDoc>()
    val baseDocsDir = getPrivateDocsDir(context)

    fun parseDocDir(folder: File, parentFolderName: String?): LocalDoc? {
        if (!folder.isDirectory || folder.name == "folders" || folder.name == "trash" || folder.name.startsWith(".backup_old_") || folder.name.startsWith(".temp_staging_")) return null
        val hasDelimiter = folder.name.contains("__")
        val meta = DocMeta.readFromDir(folder)
        if (!includeTrashed && meta.trashedDate != null) {
            return null
        }

        val timestamp = if (hasDelimiter) {
            folder.name.substringBefore("__").toLongOrNull() ?: folder.lastModified()
        } else {
            folder.lastModified()
        }
        val displayName = if (hasDelimiter) {
            folder.name.substringAfter("__")
        } else {
            folder.name
        }
        val allJpgs = if (meta.isLocked) {
            val encFiles = folder.listFiles { file ->
                file.isFile && file.name.endsWith(".enc", ignoreCase = true) &&
                    file.name != ".dek.enc" && file.name != "ocr.txt.enc" &&
                    !file.name.endsWith(".lock_tmp") && !file.name.endsWith(".unlock_tmp")
            }?.toList() ?: emptyList()
            val encBaseNames = encFiles.map { it.name.removeSuffix(".enc").lowercase(Locale.ROOT) }.toSet()
            val plainFiles = folder.listFiles { file ->
                file.isFile && (file.extension.equals("jpg", ignoreCase = true) || file.extension.equals("jpeg", ignoreCase = true) || file.extension.equals("png", ignoreCase = true)) &&
                    !encBaseNames.contains(file.name.lowercase(Locale.ROOT))
            }?.toList() ?: emptyList()
            encFiles + plainFiles
        } else {
            folder.listFiles { file ->
                file.isFile && (file.extension.equals("jpg", ignoreCase = true) || file.extension.equals("jpeg", ignoreCase = true) || file.extension.equals("png", ignoreCase = true))
            }?.toList() ?: emptyList()
        }

        if (allJpgs.isEmpty() && !File(folder, "meta.json").exists()) {
            return null
        }

        val orderNames = readPageOrder(folder).distinct()
        val sortedFiles = if (orderNames.isNotEmpty()) {
            val fileMap = allJpgs.associateBy { it.name }
            val ordered = orderNames.mapNotNull {
                fileMap[it] ?: fileMap["$it.enc"] ?: fileMap[it.removeSuffix(".enc")]
            }.distinctBy { it.name }.toMutableList()
            allJpgs.forEach { file -> if (!ordered.contains(file)) ordered.add(file) }
            ordered
        } else {
            allJpgs.sortedBy { it.name }
        }

        return LocalDoc(
            folderName = folder.name,
            displayName = displayName,
            timestamp = timestamp,
            pageCount = sortedFiles.size,
            imageFiles = sortedFiles,
            parentFolder = parentFolderName,
            directory = folder,
            tags = meta.tags,
            isLocked = meta.isLocked,
            expiryDate = meta.expiryDate
        )
    }

    baseDocsDir.listFiles { file -> file.isDirectory && file.name != "folders" && !file.name.startsWith(".backup_old_") && !file.name.startsWith(".temp_staging_") }?.forEach { f ->
        parseDocDir(f, null)?.let { docs.add(it) }
    }

    val userFoldersDir = getUserFoldersDir(context)
    userFoldersDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".backup_old_") && !file.name.startsWith(".temp_staging_") }?.forEach { uFolder ->
        uFolder.listFiles { file -> file.isDirectory && !file.name.startsWith(".backup_old_") && !file.name.startsWith(".temp_staging_") }?.forEach { docDir ->
            parseDocDir(docDir, uFolder.name)?.let { docs.add(it) }
        }
    }

    return docs.sortedByDescending { it.timestamp }
}

fun renameDoc(docFolder: File, newDisplayName: String): Boolean {
    val sanitized = sanitizeName(newDisplayName)
    if (sanitized.isBlank()) return false
    val timestamp = docFolder.name.substringBefore("__", "${System.currentTimeMillis()}")
    val newFolderName = "${timestamp}__$sanitized"
    val dest = File(docFolder.parentFile, newFolderName)
    thumbnailMemoryCache.evictAll()
    return docFolder.renameTo(dest)
}

fun moveDocToRoot(context: Context, docFolder: File): Boolean {
    val destParent = getPrivateDocsDir(context)
    val dest = File(destParent, docFolder.name)
    if (dest.exists()) return false
    return docFolder.renameTo(dest)
}

fun moveDocToFolder(context: Context, docFolder: File, targetFolderName: String): Boolean {
    val destParent = File(getUserFoldersDir(context), targetFolderName)
    if (!destParent.exists()) destParent.mkdirs()
    val dest = File(destParent, docFolder.name)
    if (dest.exists()) return false
    return docFolder.renameTo(dest)
}

fun copyDocToRoot(context: Context, docFolder: File): Boolean {
    val destParent = getPrivateDocsDir(context)
    val newFolderName = "${System.currentTimeMillis()}__${docFolder.name.substringAfter("__")}_copy"
    val dest = File(destParent, newFolderName)
    return try {
        docFolder.copyRecursively(dest, overwrite = false)
    } catch (e: Exception) {
        false
    }
}

fun copyDocToFolder(context: Context, docFolder: File, targetFolderName: String): Boolean {
    val destParent = File(getUserFoldersDir(context), targetFolderName)
    if (!destParent.exists()) destParent.mkdirs()
    val newFolderName = "${System.currentTimeMillis()}__${docFolder.name.substringAfter("__")}_copy"
    val dest = File(destParent, newFolderName)
    return try {
        docFolder.copyRecursively(dest, overwrite = false)
    } catch (e: Exception) {
        false
    }
}

fun appendPagesToDocFolder(context: Context, docFolder: File, uris: List<Uri>) {
    try {
        val resolver = context.contentResolver
        val existingOrder = readPageOrder(docFolder).toMutableList()

        uris.forEach { uri ->
            val baseName = "img_${UUID.randomUUID()}.jpg"
            appendImageFileToDoc(context, docFolder, baseName) { outPlainFile ->
                resolver.openInputStream(uri)?.use { input ->
                    outPlainFile.outputStream().use { out -> input.copyTo(out) }
                }
            }
            existingOrder.add(baseName)
        }

        val orderFile = File(docFolder, "order.txt")
        orderFile.writeText(existingOrder.joinToString("\n"))
    } catch (e: Exception) {
        android.util.Log.e("ScanVault", "appendPagesToDocFolder failed", e)
    }
}

fun savePageOrder(docFolder: File, files: List<File>) {
    try {
        val orderFile = File(docFolder, "order.txt")
        orderFile.writeText(files.joinToString("\n") { it.name })
    } catch (e: Exception) {
        android.util.Log.e("ScanVault", "savePageOrder failed", e)
    }
}

fun readPageOrder(docFolder: File): List<String> {
    val orderFile = File(docFolder, "order.txt")
    return if (orderFile.exists()) {
        orderFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    } else {
        emptyList()
    }
}

fun markDocOpened(context: Context, path: String) {
    val prefs = context.getSharedPreferences("scanvault_recents", Context.MODE_PRIVATE)
    val existing = prefs.getString("opened_list", "") ?: ""
    val list = existing.split("|||").filter { it.isNotBlank() && it != path }.toMutableList()
    list.add(0, path)
    val trimmed = list.take(10).joinToString("|||")
    prefs.edit().putString("opened_list", trimmed).apply()
}

fun getRecentlyOpenedList(context: Context): List<String> {
    val prefs = context.getSharedPreferences("scanvault_recents", Context.MODE_PRIVATE)
    val existing = prefs.getString("opened_list", "") ?: ""
    return existing.split("|||").filter { it.isNotBlank() }
}

/**
 * Resolves the default export and save folder (e.g. Documents/ScanVault or Downloads/ScanVault).
 * If the legacy folder (Documents/OwnScan or Downloads/OwnScan) exists, keep reading from it as
 * a fallback so existing test scans do not disappear.
 */
fun getExportTargetDir(subDir: String = Environment.DIRECTORY_DOWNLOADS): File {
    val legacyDir = File(Environment.getExternalStoragePublicDirectory(subDir), "OwnScan")
    val newDir = File(Environment.getExternalStoragePublicDirectory(subDir), "ScanVault")
    return if (legacyDir.exists() && !newDir.exists()) {
        legacyDir
    } else {
        newDir.apply { mkdirs() }
    }
}

fun queryOriginalFileName(context: Context, uri: Uri): String {
    var name = ""
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = cursor.getString(index) ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ScanVault", "queryOriginalFileName failed", e)
        }
    }
    if (name.isBlank()) {
        name = uri.lastPathSegment ?: "Imported_Doc"
    }
    return name.substringBeforeLast(".")
}

// -------------------------------------------------------------
// HELPER FUNCTIONS FOR OCR & ORIGINALS CONSISTENCY
// -------------------------------------------------------------

private fun rewriteOcrTextForRemaining(
    originalText: String,
    oldPages: List<String>,
    remainingPages: List<String>
): String {
    if (originalText.isBlank() || oldPages.isEmpty() || remainingPages.isEmpty()) return ""
    val pageMap = mutableMapOf<Int, String>()
    val lines = originalText.lines()
    var currentPageIndex = -1
    val currentBuilder = StringBuilder()

    for (line in lines) {
        val match = Regex("""^\[Page (\d+)\]""").find(line.trim())
        if (match != null) {
            if (currentPageIndex != -1) {
                pageMap[currentPageIndex] = currentBuilder.toString().trim()
            }
            currentPageIndex = (match.groupValues[1].toIntOrNull() ?: 1) - 1
            currentBuilder.clear()
        } else {
            if (currentPageIndex != -1) {
                currentBuilder.append(line).append("\n")
            }
        }
    }
    if (currentPageIndex != -1) {
        pageMap[currentPageIndex] = currentBuilder.toString().trim()
    }

    val pageTextByBase = mutableMapOf<String, String>()
    oldPages.forEachIndexed { idx, name ->
        val text = pageMap[idx] ?: ""
        val base = name.removeSuffix(".enc")
        pageTextByBase[base] = text
        pageTextByBase[name] = text
    }

    val sb = StringBuilder()
    var pageNum = 1
    for (remName in remainingPages) {
        val base = remName.removeSuffix(".enc")
        val text = pageTextByBase[base] ?: pageTextByBase[remName] ?: ""
        if (text.isNotBlank()) {
            sb.append("[Page $pageNum]\n")
            sb.append(text)
            sb.append("\n\n")
        }
        pageNum++
    }
    return sb.toString().trim()
}

private suspend fun invalidateOcrAndReindex(
    context: Context,
    docFolder: File,
    remainingPages: List<String> = emptyList(),
    oldPages: List<String> = emptyList()
) = withContext(Dispatchers.IO) {
    val meta = DocMeta.readFromDir(docFolder)
    DocMeta.writeToDir(docFolder, meta.copy(isOcrDone = false))

    val ocrFile = File(docFolder, "ocr.txt")
    val encOcrFile = File(docFolder, "ocr.txt.enc")
    val sessionDir = if (meta.isLocked) LockedDocCryptoManager.getUnlockedSessionDir(context, docFolder.name) else null
    val sessionOcrFile = if (sessionDir != null) File(sessionDir, "ocr.txt") else null

    var currentOcrText = ""
    if (meta.isLocked) {
        if (sessionOcrFile != null && sessionOcrFile.exists()) {
            currentOcrText = try { sessionOcrFile.readText() } catch (e: Exception) { android.util.Log.w("ScanVault", "Failed reading session OCR text in invalidateOcrAndReindex", e); "" }
        }
    } else if (ocrFile.exists()) {
        currentOcrText = try { ocrFile.readText() } catch (e: Exception) { android.util.Log.w("ScanVault", "Failed reading OCR text in invalidateOcrAndReindex", e); "" }
    }

    val updatedOcrText = if (currentOcrText.isNotBlank() && oldPages.isNotEmpty() && remainingPages.isNotEmpty()) {
        rewriteOcrTextForRemaining(currentOcrText, oldPages, remainingPages)
    } else if (remainingPages.isEmpty()) {
        ""
    } else {
        currentOcrText
    }

    if (meta.isLocked) {
        if (updatedOcrText.isNotBlank()) {
            try {
                LockedDocCryptoManager.encryptBytesToFile(updatedOcrText.toByteArray(Charsets.UTF_8), encOcrFile)
            } catch (e: Exception) {
                android.util.Log.w("ScanVault", "Failed encrypting OCR text to file in invalidateOcrAndReindex", e)
            }
            try {
                sessionOcrFile?.writeText(updatedOcrText)
            } catch (e: Exception) {
                android.util.Log.w("ScanVault", "Failed writing session OCR text in invalidateOcrAndReindex", e)
            }
        } else {
            encOcrFile.delete()
            sessionOcrFile?.delete()
        }
    } else {
        if (updatedOcrText.isNotBlank()) {
            try {
                ocrFile.writeText(updatedOcrText)
            } catch (e: Exception) {
                android.util.Log.w("ScanVault", "Failed writing OCR text in invalidateOcrAndReindex", e)
            }
        } else {
            ocrFile.delete()
        }
    }

    try {
        val db = AppDatabase.getInstance(context)
        val dao = db.documentDao()
        val ftsDao = db.documentFtsDao()
        val existing = dao.getByFolderName(docFolder.name)
        val storedRoomText = if (meta.isLocked) "" else updatedOcrText
        if (existing != null) {
            dao.insertOrUpdate(existing.copy(isOcrDone = false, ocrText = storedRoomText))
        }
        ftsDao.deleteByFolderName(docFolder.name)
        ftsDao.insertOrUpdate(
            DocumentFtsEntity(
                rowid = 0,
                folderName = docFolder.name,
                displayName = docFolder.name.substringAfter("__", docFolder.name),
                tagsText = meta.tags.joinToString(" "),
                pageText = if (meta.isLocked) "" else updatedOcrText
            )
        )
    } catch (e: Exception) {
        android.util.Log.e("ScanVault", "invalidateOcrAndReindex failed", e)
    }
}

private fun copyOriginalForPage(
    context: Context,
    srcDoc: LocalDoc,
    srcPageFile: File,
    targetDir: File,
    targetBaseName: String,
    targetIsLocked: Boolean
) {
    val srcOriginalsDir = File(srcDoc.directory, ".originals")
    if (!srcOriginalsDir.exists()) return

    val candidateNames = listOf(
        srcPageFile.name,
        "${srcPageFile.name}.enc",
        srcPageFile.name.removeSuffix(".enc"),
        "${srcPageFile.name.removeSuffix(".enc")}.enc"
    )
    val foundOriginal = candidateNames.map { File(srcOriginalsDir, it) }.firstOrNull { it.exists() && it.length() > 0 }
        ?: return

    val targetOriginalsDir = File(targetDir, ".originals")
    if (!targetOriginalsDir.exists()) targetOriginalsDir.mkdirs()

    if (srcDoc.isLocked) {
        val srcDek = try { LockedDocCryptoManager.getOrDecryptDek(srcDoc.directory) } catch (_: Exception) { null }
        if (srcDek != null && foundOriginal.name.endsWith(".enc")) {
            val tempPlain = File(targetOriginalsDir, "$targetBaseName.orig_tmp")
            try {
                LockedDocCryptoManager.decryptFile(foundOriginal, tempPlain, srcDek)
                if (targetIsLocked) {
                    val targetDek = try { LockedDocCryptoManager.getOrDecryptDek(targetDir) } catch (_: Exception) { null }
                    if (targetDek != null) {
                        val finalEnc = File(targetOriginalsDir, "$targetBaseName.enc")
                        val tempEnc = File(targetOriginalsDir, "$targetBaseName.enc_tmp")
                        LockedDocCryptoManager.encryptFile(tempPlain, tempEnc, targetDek)
                        if (tempEnc.exists() && LockedDocCryptoManager.verifyEncryptedChunk(tempEnc, targetDek)) {
                            tempEnc.renameTo(finalEnc)
                        }
                        tempEnc.delete()
                    }
                } else {
                    tempPlain.renameTo(File(targetOriginalsDir, targetBaseName))
                }
            } catch (e: Exception) {
                android.util.Log.e("ScanVault", "copyOriginalForPage failed", e)
            } finally {
                if (tempPlain.exists()) tempPlain.delete()
            }
        } else {
            if (targetIsLocked) {
                val targetDek = try { LockedDocCryptoManager.getOrDecryptDek(targetDir) } catch (_: Exception) { null }
                if (targetDek != null) {
                    val finalEnc = File(targetOriginalsDir, "$targetBaseName.enc")
                    LockedDocCryptoManager.encryptFile(foundOriginal, finalEnc, targetDek)
                }
            } else {
                foundOriginal.copyTo(File(targetOriginalsDir, targetBaseName), overwrite = true)
            }
        }
    } else {
        if (targetIsLocked) {
            val targetDek = try { LockedDocCryptoManager.getOrDecryptDek(targetDir) } catch (_: Exception) { null }
            if (targetDek != null) {
                val finalEnc = File(targetOriginalsDir, "$targetBaseName.enc")
                LockedDocCryptoManager.encryptFile(foundOriginal, finalEnc, targetDek)
            }
        } else {
            foundOriginal.copyTo(File(targetOriginalsDir, targetBaseName), overwrite = true)
        }
    }
}

private fun removeOriginalForPage(srcDoc: LocalDoc, srcPageFile: File) {
    val srcOriginalsDir = File(srcDoc.directory, ".originals")
    if (!srcOriginalsDir.exists()) return
    val candidateNames = listOf(
        srcPageFile.name,
        "${srcPageFile.name}.enc",
        srcPageFile.name.removeSuffix(".enc"),
        "${srcPageFile.name.removeSuffix(".enc")}.enc"
    )
    candidateNames.forEach {
        val f = File(srcOriginalsDir, it)
        if (f.exists()) f.delete()
    }
}

// -------------------------------------------------------------
// DOCUMENT MERGE & PAGE TRANSFER OPERATIONS
// -------------------------------------------------------------

suspend fun mergeDocuments(
    context: Context,
    sourceDocs: List<LocalDoc>,
    mergedTitle: String,
    deleteOriginals: Boolean
): LocalDoc? {
    if (sourceDocs.isEmpty()) return null

    val allSameParent = sourceDocs.map { it.parentFolder }.distinct().size == 1
    val targetParentFolder = if (allSameParent) sourceDocs.firstOrNull()?.parentFolder else null
    val parentDir = if (targetParentFolder != null) {
        File(getUserFoldersDir(context), targetParentFolder)
    } else {
        getPrivateDocsDir(context)
    }
    if (!parentDir.exists()) parentDir.mkdirs()

    val sanitized = sanitizeName(mergedTitle).ifBlank {
        "Merged_${SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())}"
    }
    val timestamp = System.currentTimeMillis()
    val folderName = "${timestamp}__$sanitized"
    val targetFolder = File(parentDir, folderName)

    try {
        if (!targetFolder.exists()) targetFolder.mkdirs()

        val allNewFiles = mutableListOf<File>()
        val combinedTags = mutableSetOf<String>()

        for (srcDoc in sourceDocs) {
            combinedTags.addAll(srcDoc.tags)
            val pages: List<File> = if (srcDoc.isLocked) {
                var sDir = LockedDocCryptoManager.getUnlockedSessionDir(context, srcDoc.folderName)
                if (sDir == null || sDir.listFiles()?.none { it.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png") } == true) {
                    try {
                        LockedDocCryptoManager.prepareUnlockedSession(context, srcDoc.directory)
                    } catch (e: Exception) {
                        throw IllegalStateException("Could not read ${srcDoc.displayName}: ${e.message}", e)
                    }
                    sDir = LockedDocCryptoManager.getUnlockedSessionDir(context, srcDoc.folderName)
                }
                val raw = sDir?.listFiles { f ->
                    val ext = f.extension.lowercase(Locale.ROOT)
                    f.isFile && (ext == "jpg" || ext == "jpeg" || ext == "png")
                }?.toList() ?: emptyList()
                val orderNames = readPageOrder(srcDoc.directory)
                if (orderNames.isNotEmpty()) {
                    val fileMap = raw.associateBy { it.name }
                    val ordered = orderNames.mapNotNull { fileMap[it] ?: fileMap[it.removeSuffix(".enc")] }.toMutableList()
                    raw.forEach { if (!ordered.contains(it)) ordered.add(it) }
                    ordered
                } else {
                    raw.sortedBy { it.name }
                }
            } else {
                srcDoc.imageFiles
            }

            if (pages.isEmpty() || pages.size != srcDoc.pageCount) {
                throw IllegalStateException("Could not read ${srcDoc.displayName}")
            }

            for (pageFile in pages) {
                val ext = if (pageFile.extension.equals("png", ignoreCase = true)) "png" else "jpg"
                val newPageFile = File(targetFolder, "img_${UUID.randomUUID()}.$ext")
                pageFile.copyTo(newPageFile, overwrite = true)
                allNewFiles.add(newPageFile)
            }
        }

        savePageOrder(targetFolder, allNewFiles)
        DocMeta.writeToDir(targetFolder, DocMeta(tags = combinedTags.toList()))

        if (deleteOriginals) {
            sourceDocs.forEach { srcDoc ->
                DocumentRepository.moveToTrash(context, srcDoc)
                DriveBackupManager.removeDocIdFromLocalCache(context, srcDoc.folderName)
            }
        }

        return LocalDoc(
            folderName = targetFolder.name,
            displayName = sanitized,
            timestamp = timestamp,
            pageCount = allNewFiles.size,
            imageFiles = allNewFiles,
            parentFolder = targetParentFolder,
            directory = targetFolder,
            tags = combinedTags.toList(),
            isLocked = false,
            expiryDate = null
        )
    } catch (e: Exception) {
        if (targetFolder.exists()) {
            targetFolder.deleteRecursively()
        }
        throw e
    } finally {
        sourceDocs.filter { it.isLocked }.forEach { srcDoc ->
            try {
                LockedDocCryptoManager.wipeDecryptedSession(context, srcDoc.folderName)
            } catch (_: Exception) {}
        }
    }
}

suspend fun copyPagesToDoc(
    context: Context,
    pages: List<File>,
    targetDoc: LocalDoc
): Boolean = withContext(Dispatchers.IO) {
    try {
        val existingOrder = readPageOrder(targetDoc.directory).toMutableList()
        val copiedFiles = mutableListOf<File>()

        for (pageFile in pages) {
            val ext = if (pageFile.extension.equals("png", ignoreCase = true)) "png" else "jpg"
            val baseName = "img_${UUID.randomUUID()}.$ext"
            val targetFile = appendImageFileToDoc(context, targetDoc.directory, baseName) { outPlain ->
                pageFile.inputStream().use { input ->
                    outPlain.outputStream().use { out -> input.copyTo(out) }
                }
            }
            if (!targetFile.exists() || targetFile.length() == 0L) {
                return@withContext false
            }
            copiedFiles.add(targetFile)
            if (!existingOrder.contains(baseName)) {
                existingOrder.add(baseName)
            }

            val srcDoc = pages.firstOrNull()?.parentFile?.let { p ->
                val folderName = if (p.parentFile?.name == "session_unlocked") p.name else p.name
                loadAllInAppDocuments(context, includeTrashed = true).firstOrNull { it.folderName == folderName }
            }
            if (srcDoc != null) {
                copyOriginalForPage(context, srcDoc, pageFile, targetDoc.directory, baseName, targetDoc.isLocked)
            }
        }

        val orderFile = File(targetDoc.directory, "order.txt")
        orderFile.writeText(existingOrder.joinToString("\n"))

        invalidateOcrAndReindex(context, targetDoc.directory, existingOrder)
        true
    } catch (e: Exception) {
        android.util.Log.e("ScanVault", "copyPagesToDoc failed", e)
        false
    }
}

suspend fun copyPagesToNewDoc(
    context: Context,
    pages: List<File>,
    newDocName: String
): LocalDoc? = withContext(Dispatchers.IO) {
    try {
        val sanitized = sanitizeName(newDocName).ifBlank {
            "Doc_${SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())}"
        }
        val timestamp = System.currentTimeMillis()
        val folderName = "${timestamp}__$sanitized"
        val parentDir = getPrivateDocsDir(context)
        val targetFolder = File(parentDir, folderName)
        if (!targetFolder.exists()) targetFolder.mkdirs()

        val createdFiles = mutableListOf<File>()
        val srcDoc = pages.firstOrNull()?.parentFile?.let { p ->
            val folderName = if (p.parentFile?.name == "session_unlocked") p.name else p.name
            loadAllInAppDocuments(context, includeTrashed = true).firstOrNull { it.folderName == folderName }
        }

        for (pageFile in pages) {
            val ext = if (pageFile.extension.equals("png", ignoreCase = true)) "png" else "jpg"
            val baseName = "img_${UUID.randomUUID()}.$ext"
            val newFile = File(targetFolder, baseName)
            pageFile.copyTo(newFile, overwrite = true)
            if (!newFile.exists() || newFile.length() == 0L) {
                targetFolder.deleteRecursively()
                return@withContext null
            }
            createdFiles.add(newFile)
            if (srcDoc != null) {
                copyOriginalForPage(context, srcDoc, pageFile, targetFolder, baseName, false)
            }
        }

        savePageOrder(targetFolder, createdFiles)
        DocMeta.writeToDir(targetFolder, DocMeta())

        LocalDoc(
            folderName = targetFolder.name,
            displayName = sanitized,
            timestamp = timestamp,
            pageCount = createdFiles.size,
            imageFiles = createdFiles,
            parentFolder = null,
            directory = targetFolder,
            tags = emptyList(),
            isLocked = false,
            expiryDate = null
        )
    } catch (e: Exception) {
        android.util.Log.e("ScanVault", "copyPagesToNewDoc failed", e)
        null
    }
}

suspend fun movePagesToDoc(
    context: Context,
    sourceDoc: LocalDoc,
    pages: List<File>,
    targetDoc: LocalDoc
): Boolean = withContext(Dispatchers.IO) {
    val existingOrder = readPageOrder(targetDoc.directory).toMutableList()
    val copiedPairs = mutableListOf<Pair<File, File>>()

    try {
        for (pageFile in pages) {
            val ext = if (pageFile.extension.equals("png", ignoreCase = true)) "png" else "jpg"
            val baseName = "img_${UUID.randomUUID()}.$ext"
            val targetFile = appendImageFileToDoc(context, targetDoc.directory, baseName) { outPlain ->
                pageFile.inputStream().use { input ->
                    outPlain.outputStream().use { out -> input.copyTo(out) }
                }
            }
            copiedPairs.add(Pair(pageFile, targetFile))
            if (!existingOrder.contains(baseName)) {
                existingOrder.add(baseName)
            }
            copyOriginalForPage(context, sourceDoc, pageFile, targetDoc.directory, baseName, targetDoc.isLocked)
        }

        val allValid = copiedPairs.all { (src, dest) ->
            if (!dest.exists() || dest.length() <= 0L) {
                false
            } else {
                if (!sourceDoc.isLocked && !targetDoc.isLocked) {
                    dest.length() == src.length()
                } else {
                    true
                }
            }
        }

        if (!allValid) {
            copiedPairs.forEach { (_, dest) -> dest.delete() }
            return@withContext false
        }

        val orderFile = File(targetDoc.directory, "order.txt")
        orderFile.writeText(existingOrder.joinToString("\n"))

        withContext(NonCancellable) {
            val oldOrder = readPageOrder(sourceDoc.directory)
            val pageNames = pages.map { it.name }.toSet()
            val pageBaseNames = pages.map { it.name.removeSuffix(".enc") }.toSet()

            pages.forEach { removeOriginalForPage(sourceDoc, it) }

            movePagesToTrash(context, sourceDoc, pages)

            val sessionDir = LockedDocCryptoManager.getUnlockedSessionDir(context, sourceDoc.folderName)
            if (sessionDir != null && sessionDir.exists()) {
                pages.forEach { f ->
                    File(sessionDir, f.name).delete()
                    File(sessionDir, f.name.removeSuffix(".enc")).delete()
                }
            }

            val remainingOrder = oldOrder.filter { name ->
                name !in pageNames && name !in pageBaseNames && name.removeSuffix(".enc") !in pageBaseNames
            }
            val srcOrderFile = File(sourceDoc.directory, "order.txt")
            srcOrderFile.writeText(remainingOrder.joinToString("\n"))

            invalidateOcrAndReindex(context, sourceDoc.directory, remainingOrder, oldOrder)
            invalidateOcrAndReindex(context, targetDoc.directory, existingOrder)

            if (remainingOrder.isEmpty()) {
                DocumentRepository.moveToTrash(context, sourceDoc)
                if (sourceDoc.isLocked) {
                    LockedDocCryptoManager.wipeDecryptedSession(context, sourceDoc.folderName)
                }
                DriveBackupManager.removeDocIdFromLocalCache(context, sourceDoc.folderName)
            }
        }
        true
    } catch (e: Exception) {
        android.util.Log.e("ScanVault", "movePagesToDoc failed", e)
        false
    }
}

suspend fun movePagesToNewDoc(
    context: Context,
    sourceDoc: LocalDoc,
    pages: List<File>,
    newDocName: String
): LocalDoc? = withContext(Dispatchers.IO) {
    val sanitized = sanitizeName(newDocName).ifBlank {
        "Doc_${SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())}"
    }
    val timestamp = System.currentTimeMillis()
    val folderName = "${timestamp}__$sanitized"
    val parentDir = getPrivateDocsDir(context)
    val targetFolder = File(parentDir, folderName)

    try {
        if (!targetFolder.exists()) targetFolder.mkdirs()

        val createdFiles = mutableListOf<File>()
        val copiedPairs = mutableListOf<Pair<File, File>>()

        for (pageFile in pages) {
            val ext = if (pageFile.extension.equals("png", ignoreCase = true)) "png" else "jpg"
            val baseName = "img_${UUID.randomUUID()}.$ext"
            val newFile = File(targetFolder, baseName)
            pageFile.copyTo(newFile, overwrite = true)
            copiedPairs.add(Pair(pageFile, newFile))
            createdFiles.add(newFile)
            copyOriginalForPage(context, sourceDoc, pageFile, targetFolder, baseName, false)
        }

        val allValid = copiedPairs.all { (src, dest) ->
            if (!dest.exists() || dest.length() <= 0L) {
                false
            } else {
                if (!sourceDoc.isLocked) {
                    dest.length() == src.length()
                } else {
                    true
                }
            }
        }

        if (!allValid) {
            targetFolder.deleteRecursively()
            return@withContext null
        }

        savePageOrder(targetFolder, createdFiles)
        DocMeta.writeToDir(targetFolder, DocMeta())

        invalidateOcrAndReindex(context, targetFolder, createdFiles.map { it.name })

        withContext(NonCancellable) {
            val oldOrder = readPageOrder(sourceDoc.directory)
            val pageNames = pages.map { it.name }.toSet()
            val pageBaseNames = pages.map { it.name.removeSuffix(".enc") }.toSet()

            pages.forEach { removeOriginalForPage(sourceDoc, it) }

            movePagesToTrash(context, sourceDoc, pages)

            val sessionDir = LockedDocCryptoManager.getUnlockedSessionDir(context, sourceDoc.folderName)
            if (sessionDir != null && sessionDir.exists()) {
                pages.forEach { f ->
                    File(sessionDir, f.name).delete()
                    File(sessionDir, f.name.removeSuffix(".enc")).delete()
                }
            }

            val remainingOrder = oldOrder.filter { name ->
                name !in pageNames && name !in pageBaseNames && name.removeSuffix(".enc") !in pageBaseNames
            }
            val srcOrderFile = File(sourceDoc.directory, "order.txt")
            srcOrderFile.writeText(remainingOrder.joinToString("\n"))

            invalidateOcrAndReindex(context, sourceDoc.directory, remainingOrder, oldOrder)

            if (remainingOrder.isEmpty()) {
                DocumentRepository.moveToTrash(context, sourceDoc)
                if (sourceDoc.isLocked) {
                    LockedDocCryptoManager.wipeDecryptedSession(context, sourceDoc.folderName)
                }
                DriveBackupManager.removeDocIdFromLocalCache(context, sourceDoc.folderName)
            }
        }

        LocalDoc(
            folderName = targetFolder.name,
            displayName = sanitized,
            timestamp = timestamp,
            pageCount = createdFiles.size,
            imageFiles = createdFiles,
            parentFolder = null,
            directory = targetFolder,
            tags = emptyList(),
            isLocked = false,
            expiryDate = null
        )
    } catch (e: Exception) {
        if (targetFolder.exists()) targetFolder.deleteRecursively()
        android.util.Log.e("ScanVault", "movePagesToNewDoc failed", e)
        null
    }
}
