package com.example.ownscan

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

// -------------------------------------------------------------
// FULL-SCREEN GALLERY VIEWER WITH SWIPE & SENSITIVE PINCH-TO-ZOOM / PAN
// -------------------------------------------------------------

@Composable
fun GalleryViewerScreen(
    doc: LocalDoc,
    initialPageIndex: Int,
    onClose: () -> Unit,
    onEditPage: (File) -> Unit
) {
    val colors = LocalAppColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var effectivePages by remember(doc) {
        val sessionDir = if (doc.isLocked) LockedDocCryptoManager.getUnlockedSessionDir(context, doc.folderName) else null
        val pages = if (sessionDir != null) {
            val list = sessionDir.listFiles { f ->
                val ext = f.extension.lowercase(Locale.ROOT)
                f.isFile && (ext == "jpg" || ext == "jpeg" || ext == "png")
            }?.toList() ?: emptyList()
            val orderNames = readPageOrder(doc.directory)
            val sorted = if (orderNames.isNotEmpty()) {
                val fileMap = list.associateBy { it.name }
                val ordered = orderNames.mapNotNull {
                    fileMap[it] ?: fileMap[it.removeSuffix(".enc")]
                }.toMutableList()
                list.forEach { if (!ordered.contains(it)) ordered.add(it) }
                ordered
            } else {
                list.sortedBy { it.name }
            }
            if (sorted.isNotEmpty()) sorted else doc.imageFiles
        } else {
            doc.imageFiles
        }
        mutableStateOf(pages)
    }

    LaunchedEffect(doc.folderName, doc.isLocked, doc.imageFiles) {
        if (doc.isLocked) {
            val sDir = LockedDocCryptoManager.getUnlockedSessionDir(context, doc.folderName)
            if (sDir == null || sDir.listFiles()?.none { it.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png") } == true) {
                withContext(Dispatchers.IO) {
                    try {
                        LockedDocCryptoManager.prepareUnlockedSession(context, doc.directory)
                    } catch (_: Exception) {}
                }
            }
            val ready = LockedDocCryptoManager.getUnlockedSessionDir(context, doc.folderName)
            val raw = ready?.listFiles { f ->
                val ext = f.extension.lowercase(Locale.ROOT)
                f.isFile && (ext == "jpg" || ext == "jpeg" || ext == "png")
            }?.toList() ?: emptyList()
            val orderNames = readPageOrder(doc.directory)
            val sorted = if (orderNames.isNotEmpty()) {
                val fileMap = raw.associateBy { it.name }
                val ordered = orderNames.mapNotNull {
                    fileMap[it] ?: fileMap[it.removeSuffix(".enc")]
                }.toMutableList()
                raw.forEach { if (!ordered.contains(it)) ordered.add(it) }
                ordered
            } else {
                raw.sortedBy { it.name }
            }
            if (sorted.isNotEmpty()) {
                effectivePages = sorted
            }
        }
    }

    val pageCount = effectivePages.size.coerceAtLeast(1)
    val safeInitialPage = initialPageIndex.coerceIn(0, pageCount - 1)
    val pagerState = rememberPagerState(
        initialPage = safeInitialPage,
        pageCount = { effectivePages.size }
    )

    // Current page zoom level to allow/disallow pager swipe seamlessly
    var activePageScale by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(pagerState.currentPage) {
        activePageScale = 1f
    }

    val currentFile = effectivePages.getOrNull(pagerState.currentPage)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // HorizontalPager for fluid swiping across all images
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = (activePageScale <= 1.05f),
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            val pageFile = effectivePages.getOrNull(pageIndex)
            if (pageFile != null) {
                GalleryPageViewerItem(
                    file = pageFile,
                    isCurrentPage = (pageIndex == pagerState.currentPage),
                    onScaleChange = { newScale ->
                        if (pageIndex == pagerState.currentPage) {
                            activePageScale = newScale
                        }
                    }
                )
            }
        }

        // Top bar: Close button, page counter indicator, Edit button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                text = "Page ${pagerState.currentPage + 1} of ${effectivePages.size}",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )

            Button(
                onClick = { currentFile?.let { onEditPage(it) } },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("✏️ Edit", fontSize = 13.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun GalleryPageViewerItem(
    file: File,
    isCurrentPage: Boolean,
    onScaleChange: (Float) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            onScaleChange(1f)
        }
    }

    val highResBitmapState = produceState<Bitmap?>(initialValue = null, key1 = file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(file, 1600, 2400)
        }
    }
    val highResBitmap = highResBitmapState.value

    val currentScale by rememberUpdatedState(scale)
    val currentOffsetX by rememberUpdatedState(offsetX)
    val currentOffsetY by rememberUpdatedState(offsetY)
    val currentOnScaleChange by rememberUpdatedState(onScaleChange)
    val currentHighResBitmap by rememberUpdatedState(highResBitmap)

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()

        fun clampOffset(x: Float, y: Float, curScale: Float): Pair<Float, Float> {
            val bmp = currentHighResBitmap ?: return Pair(0f, 0f)
            if (curScale <= 1.05f) return Pair(0f, 0f)
            val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
            val containerAspect = maxWidthPx / maxHeightPx.coerceAtLeast(1f)
            val dispWidth = if (bmpAspect > containerAspect) maxWidthPx else maxHeightPx * bmpAspect
            val dispHeight = if (bmpAspect > containerAspect) maxWidthPx / bmpAspect else maxHeightPx
            val scaledW = dispWidth * curScale
            val scaledH = dispHeight * curScale
            val maxPanX = if (scaledW > maxWidthPx) (scaledW - maxWidthPx) / 2f + 80f else (maxWidthPx * (curScale - 1f) / 2f + 50f)
            val maxPanY = if (scaledH > maxHeightPx) (scaledH - maxHeightPx) / 2f + 80f else (maxHeightPx * (curScale - 1f) / 2f + 50f)
            return Pair(x.coerceIn(-maxPanX, maxPanX), y.coerceIn(-maxPanY, maxPanY))
        }

        if (highResBitmap != null) {
            Image(
                bitmap = highResBitmap.asImageBitmap(),
                contentDescription = "Full Preview",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .pointerInput(file.name) {
                        var lastTapTime = 0L
                        var lastTapPos = Offset.Zero

                        awaitEachGesture {
                            var isDragOrPinch = false
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downTime = System.currentTimeMillis()
                            val downPos = down.position

                            do {
                                val event = awaitPointerEvent()
                                val active = event.changes.filter { it.pressed }

                                if (active.size > 1) {
                                    isDragOrPinch = true
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    if (zoom != 1f) {
                                        val zoomSensitivity = 1.6f
                                        val effectiveZoom = 1f + (zoom - 1f) * zoomSensitivity
                                        val newScale = (currentScale * effectiveZoom).coerceIn(1f, 6f)
                                        scale = if (newScale < 1.05f) 1f else newScale
                                        currentOnScaleChange(scale)
                                        event.changes.forEach { it.consume() }
                                    }
                                    if (scale > 1.05f) {
                                        val panSensitivity = 1.45f
                                        val rawX = currentOffsetX + pan.x * panSensitivity
                                        val rawY = currentOffsetY + pan.y * panSensitivity
                                        val (clampedX, clampedY) = clampOffset(rawX, rawY, scale)
                                        offsetX = clampedX
                                        offsetY = clampedY
                                        event.changes.forEach { it.consume() }
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                } else if (active.size == 1) {
                                    val change = active.first()
                                    val dragDist = (change.position - downPos).getDistance()
                                    if (dragDist > 10f) {
                                        isDragOrPinch = true
                                    }
                                    if (currentScale > 1.05f) {
                                        val pan = change.position - change.previousPosition
                                        val panSensitivity = 1.45f
                                        val rawX = currentOffsetX + pan.x * panSensitivity
                                        val rawY = currentOffsetY + pan.y * panSensitivity
                                        val (clampedX, clampedY) = clampOffset(rawX, rawY, currentScale)
                                        offsetX = clampedX
                                        offsetY = clampedY
                                        change.consume()
                                    }
                                }
                            } while (active.isNotEmpty())

                            if (!isDragOrPinch && (System.currentTimeMillis() - downTime) < 300) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 350 && (downPos - lastTapPos).getDistance() < 100f) {
                                    if (currentScale > 1.05f) {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                        currentOnScaleChange(1f)
                                    } else {
                                        scale = 2.5f
                                        val (cx, cy) = clampOffset(0f, 0f, 2.5f)
                                        offsetX = cx
                                        offsetY = cy
                                        currentOnScaleChange(2.5f)
                                    }
                                    lastTapTime = 0L
                                } else {
                                    lastTapTime = now
                                    lastTapPos = downPos
                                }
                            }
                        }
                    },
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(36.dp)
            )
        }

        // Floating quick reset button when zoomed in
        if (scale > 1.05f) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .clickable {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                        onScaleChange(1f)
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("🔍", fontSize = 12.sp)
                    Text(
                        text = "${(scale * 10).roundToInt() / 10f}x • Tap to Reset",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// IMAGE EDITOR & ANNOTATIONS (FILTERS, PEN, HIGHLIGHT, TEXT, SIGNATURE)
// -------------------------------------------------------------

@Composable
fun ImageEditorScreen(
    file: File,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    FullImageEditorScreen(
        activity = context as Activity,
        file = file,
        onClose = onClose,
        onSaved = onSaved
    )
}
