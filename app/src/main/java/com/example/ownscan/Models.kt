package com.example.ownscan

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import java.io.File

data class LocalDoc(
    val folderName: String,
    val displayName: String,
    val timestamp: Long,
    val pageCount: Int,
    val imageFiles: List<File>,
    val parentFolder: String? = null,
    val directory: File,
    val tags: List<String> = emptyList(),
    val isLocked: Boolean = false,
    val expiryDate: Long? = null
)

data class UserFolder(
    val name: String,
    val docCount: Int
)

data class ToolItem(
    val title: String,
    val icon: String,
    val description: String,
    val id: String
)

data class ToolCompletionResult(
    val title: String,
    val fileName: String,
    val uri: Uri,
    val mimeType: String,
    val originalBytes: Long = 0L,
    val newBytes: Long = 0L
)

