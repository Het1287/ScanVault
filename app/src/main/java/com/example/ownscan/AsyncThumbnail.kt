package com.example.ownscan

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// -------------------------------------------------------------
// ASYNCHRONOUS THUMBNAIL
// -------------------------------------------------------------

@Composable
fun AsyncThumbnail(
    file: File?,
    modifier: Modifier = Modifier,
    widthPx: Int = 160,
    heightPx: Int = 200,
    placeholderIcon: String = "📄",
    isLocked: Boolean = false
) {
    val colors = LocalAppColors.current
    val effectivePlaceholder = if (isLocked || (file != null && file.name.endsWith(".enc", ignoreCase = true))) "🔒" else placeholderIcon
    val isEncrypted = file != null && file.name.endsWith(".enc", ignoreCase = true)

    val bitmapState = produceState<Bitmap?>(initialValue = null, key1 = file?.absolutePath, key2 = file?.lastModified()) {
        if (file == null || !file.exists() || isLocked || isEncrypted) {
            value = null
            return@produceState
        }
        val cacheKey = "${file.absolutePath}_${file.lastModified()}_${widthPx}x$heightPx"
        val cached = thumbnailMemoryCache.get(cacheKey)
        if (cached != null) {
            value = cached
        } else {
            val decoded = withContext(Dispatchers.IO) {
                decodeSampledBitmap(file, widthPx, heightPx)
            }
            if (decoded != null) {
                thumbnailMemoryCache.put(cacheKey, decoded)
            }
            value = decoded
        }
    }

    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Thumbnail",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.background(colors.cardSubtle),
            contentAlignment = Alignment.Center
        ) {
            Text(effectivePlaceholder, fontSize = 20.sp)
        }
    }
}

