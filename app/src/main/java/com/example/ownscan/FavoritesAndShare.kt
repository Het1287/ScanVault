package com.example.ownscan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList

// -------------------------------------------------------------
// FAVORITE SYSTEM
// -------------------------------------------------------------

fun loadFavoritePaths(context: Context): Set<String> {
    val prefs = context.getSharedPreferences("scanvault_favorites", Context.MODE_PRIVATE)
    // One-time migration from absolute paths to folder names
    if (!prefs.getBoolean("migrated_fav_to_folder_names", false)) {
        val oldFavPaths = prefs.getStringSet("fav_paths", emptySet()) ?: emptySet()
        val migratedFolderNames = oldFavPaths.map { path ->
            File(path).name
        }.toSet()
        prefs.edit()
            .putStringSet("fav_folder_names", migratedFolderNames)
            .putBoolean("migrated_fav_to_folder_names", true)
            .apply()
        return migratedFolderNames
    }
    return prefs.getStringSet("fav_folder_names", emptySet()) ?: emptySet()
}

fun toggleDocFavorite(context: Context, docIdentifier: String): Set<String> {
    val folderName = if (docIdentifier.contains("/") || docIdentifier.contains("\\")) {
        File(docIdentifier).name
    } else {
        docIdentifier
    }
    val prefs = context.getSharedPreferences("scanvault_favorites", Context.MODE_PRIVATE)
    val current = loadFavoritePaths(context).toMutableSet()
    if (current.contains(folderName)) {
        current.remove(folderName)
    } else {
        current.add(folderName)
    }
    prefs.edit().putStringSet("fav_folder_names", current).apply()
    return current
}

// -------------------------------------------------------------
// SHARING ENGINE (FileProvider & cacheDir/shared/)
// -------------------------------------------------------------

fun getSharedCacheDir(context: Context): File {
    val dir = File(context.cacheDir, "shared")
    if (!dir.exists()) dir.mkdirs()
    val threshold = System.currentTimeMillis() - 30 * 60 * 1000L // 30 minutes
    dir.listFiles()?.forEach { file ->
        if (file.lastModified() < threshold) {
            file.delete()
        }
    }
    return dir
}

fun clearSharedCacheDir(context: Context) {
    try {
        val dir = File(context.cacheDir, "shared")
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    } catch (_: Exception) {}
}

/**
 * Resolves decrypted image files from the ephemeral session cache if the document is locked.
 * Unlocked documents return existing image files directly.
 */
suspend fun resolveDecryptedImageFiles(
    context: Context,
    doc: LocalDoc,
    inputFiles: List<File>? = null
): List<File> = withContext(Dispatchers.IO) {
    val rawFiles = inputFiles ?: doc.imageFiles
    if (!doc.isLocked) {
        return@withContext rawFiles.filter { it.exists() && it.length() > 0L }
    }
    val sessionDir = try {
        LockedDocCryptoManager.getUnlockedSessionDir(context, doc.folderName)
            ?: LockedDocCryptoManager.prepareUnlockedSession(context, doc.directory)
    } catch (e: Exception) {
        android.util.Log.e("FavoritesAndShare", "Failed to decrypt locked doc for sharing: ${e.message}", e)
        return@withContext emptyList()
    }

    rawFiles.map { file ->
        if (file.name.endsWith(".enc", ignoreCase = true)) {
            val plainName = file.name.removeSuffix(".enc")
            File(sessionDir, plainName)
        } else {
            File(sessionDir, file.name)
        }
    }.filter { it.exists() && it.length() > 0L }
}

fun shareDocAsPdf(
    activity: Activity,
    doc: LocalDoc,
    customName: String = doc.displayName,
    password: String? = null,
    watermarkText: String? = null
) {
    Toast.makeText(activity, "Preparing PDF...", Toast.LENGTH_SHORT).show()
    val sanitized = sanitizeName(customName).ifEmpty { doc.displayName }
    (activity as? FragmentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
        val pages = resolveDecryptedImageFiles(activity, doc)
        if (pages.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Couldn't prepare the file", Toast.LENGTH_SHORT).show()
            }
            return@launch
        }
        val shareDir = getSharedCacheDir(activity)
        val shareFile = File(shareDir, "${sanitized}_${System.currentTimeMillis()}.pdf")
        val generatedFile = PasswordPdfExporter.generatePdfFile(
            context = activity,
            baseName = sanitized,
            imageFiles = pages,
            password = password,
            watermarkText = watermarkText
        )
        if (generatedFile != null && generatedFile.exists() && generatedFile.length() > 0L) {
            generatedFile.copyTo(shareFile, overwrite = true)
            generatedFile.delete()
            if (shareFile.exists() && shareFile.length() > 0L) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    shareFile
                )
                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(shareIntent, "Share $sanitized as PDF"))
                }
            } else {
                shareFile.delete()
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Couldn't prepare the file", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            generatedFile?.delete()
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Couldn't prepare the file", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

fun shareDocAsJpg(
    activity: Activity,
    doc: LocalDoc,
    watermarkText: String? = null
) {
    Toast.makeText(activity, "Preparing images...", Toast.LENGTH_SHORT).show()
    (activity as? FragmentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
        val pages = resolveDecryptedImageFiles(activity, doc)
        if (pages.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Couldn't prepare the file", Toast.LENGTH_SHORT).show()
            }
            return@launch
        }
        val shareDir = getSharedCacheDir(activity)
        val uris = ArrayList<Uri>()
        pages.forEachIndexed { index, file ->
            try {
                val shareFile = File(shareDir, "${doc.displayName}_page_${index + 1}_${System.currentTimeMillis()}.jpg")
                if (!watermarkText.isNullOrBlank()) {
                    val rawBmp = decodeFileCapped(file.absolutePath)
                    if (rawBmp != null) {
                        val procBmp = MaskAndWatermarkEngine.processImage(activity, rawBmp, watermarkText)
                        if (procBmp != rawBmp) rawBmp.recycle()
                        FileOutputStream(shareFile).use { fos ->
                            procBmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                            fos.flush()
                        }
                        procBmp.recycle()
                    } else {
                        file.copyTo(shareFile, overwrite = true)
                    }
                } else {
                    file.copyTo(shareFile, overwrite = true)
                }
                if (shareFile.exists() && shareFile.length() > 0L) {
                    val u = androidx.core.content.FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        shareFile
                    )
                    uris.add(u)
                } else {
                    shareFile.delete()
                }
            } catch (e: Exception) {
                android.util.Log.w("FavoritesAndShare", "Failed preparing image for sharing", e)
            }
        }

        withContext(Dispatchers.Main) {
            if (uris.isNotEmpty()) {
                val shareIntent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "image/jpeg"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                activity.startActivity(Intent.createChooser(shareIntent, "Share ${doc.displayName} as JPG"))
            } else {
                Toast.makeText(activity, "Couldn't prepare the file", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

fun shareMultipleImages(activity: Activity, images: List<File>) {
    if (images.isEmpty()) {
        Toast.makeText(activity, "Couldn't prepare the file", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(activity, "Preparing images for sharing...", Toast.LENGTH_SHORT).show()
    (activity as? FragmentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
        val shareDir = getSharedCacheDir(activity)
        val uris = ArrayList<Uri>()
        images.forEachIndexed { index, file ->
            try {
                if (file.name.endsWith(".enc", ignoreCase = true) || !file.exists() || file.length() == 0L) {
                    return@forEachIndexed
                }
                val shareFile = File(shareDir, "Doc_Page_${index + 1}_${System.currentTimeMillis()}.jpg")
                file.copyTo(shareFile, overwrite = true)
                if (shareFile.exists() && shareFile.length() > 0L) {
                    val u = androidx.core.content.FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        shareFile
                    )
                    uris.add(u)
                } else {
                    shareFile.delete()
                }
            } catch (e: Exception) {
                android.util.Log.w("FavoritesAndShare", "Failed preparing selected page for sharing", e)
            }
        }

        withContext(Dispatchers.Main) {
            if (uris.isNotEmpty()) {
                val shareIntent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "image/jpeg"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                activity.startActivity(Intent.createChooser(shareIntent, "Share Selected Documents"))
            } else {
                Toast.makeText(activity, "Couldn't prepare the file", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

