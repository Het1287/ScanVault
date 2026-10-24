package com.example.ownscan

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.io.File

// -------------------------------------------------------------
// NON-DESTRUCTIVE ORIGINAL IMAGE BACKUP MANAGER
// -------------------------------------------------------------

object OriginalImageManager {
    private const val ORIGINALS_DIR_NAME = ".originals"

    private fun getOriginalsDir(docFolder: File): File {
        val dir = File(docFolder, ORIGINALS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun hasOriginal(pageFile: File): Boolean {
        val originalFile = File(getOriginalsDir(pageFile.parentFile ?: return false), pageFile.name)
        return originalFile.exists() && originalFile.length() > 0
    }

    fun ensureOriginalPreserved(pageFile: File) {
        val parent = pageFile.parentFile ?: return
        val originalFile = File(getOriginalsDir(parent), pageFile.name)
        if (!originalFile.exists()) {
            try {
                pageFile.copyTo(originalFile, overwrite = false)
            } catch (e: Exception) {
                android.util.Log.e("OriginalImageManager", "Failed preserving original: ${e.message}")
            }
        }
    }

    fun revertToOriginal(pageFile: File): Boolean {
        val parent = pageFile.parentFile ?: return false
        val originalFile = File(getOriginalsDir(parent), pageFile.name)
        if (!originalFile.exists()) return false
        return try {
            originalFile.copyTo(pageFile, overwrite = true)
            true
        } catch (e: Exception) {
            android.util.Log.e("OriginalImageManager", "Failed reverting to original: ${e.message}")
            false
        }
    }
}

// -------------------------------------------------------------
// ANNOTATION MODELS (PEN & HIGHLIGHTER STROKES)
// -------------------------------------------------------------

data class AnnotationStroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
    val isHighlighter: Boolean = false
)
