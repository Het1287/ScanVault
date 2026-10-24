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
        val shareDir = getSharedCacheDir(activity)
        val shareFile = File(shareDir, "${sanitized}_${System.currentTimeMillis()}.pdf")
        val generatedFile = PasswordPdfExporter.generatePdfFile(
            context = activity,
            baseName = sanitized,
            imageFiles = doc.imageFiles,
            password = password,
            watermarkText = watermarkText
        )
        if (generatedFile != null) {
            generatedFile.copyTo(shareFile, overwrite = true)
            generatedFile.delete()
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
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Failed to prepare PDF", Toast.LENGTH_SHORT).show()
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
        val shareDir = getSharedCacheDir(activity)
        val uris = ArrayList<Uri>()
        doc.imageFiles.forEachIndexed { index, file ->
            try {
                val shareFile = File(shareDir, "${doc.displayName}_page_${index + 1}_${System.currentTimeMillis()}.jpg")
                if (!watermarkText.isNullOrBlank()) {
                    val rawBmp = decodeFileCapped(file.absolutePath)
                    if (rawBmp != null) {
                        val procBmp = MaskAndWatermarkEngine.processImage(activity, rawBmp, watermarkText)
                        if (procBmp != rawBmp) rawBmp.recycle()
                        FileOutputStream(shareFile).use { fos ->
                            procBmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                        }
                        procBmp.recycle()
                    } else {
                        file.copyTo(shareFile, overwrite = true)
                    }
                } else {
                    file.copyTo(shareFile, overwrite = true)
                }
                val u = androidx.core.content.FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    shareFile
                )
                uris.add(u)
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
                Toast.makeText(activity, "Failed to prepare images", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

fun shareMultipleImages(activity: Activity, images: List<File>) {
    Toast.makeText(activity, "Preparing images for sharing...", Toast.LENGTH_SHORT).show()
    val shareDir = getSharedCacheDir(activity)
    val uris = ArrayList<Uri>()
    images.forEachIndexed { index, file ->
        try {
            val shareFile = File(shareDir, "Doc_Page_${index + 1}_${System.currentTimeMillis()}.jpg")
            file.copyTo(shareFile, overwrite = true)
            val u = androidx.core.content.FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                shareFile
            )
            uris.add(u)
        } catch (e: Exception) {
            android.util.Log.w("FavoritesAndShare", "Failed preparing selected page for sharing", e)
        }
    }

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
        Toast.makeText(activity, "Failed to prepare images", Toast.LENGTH_SHORT).show()
    }
}

