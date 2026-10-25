package com.example.ownscan

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// -------------------------------------------------------------
// SCREEN 2: TOOLS (CONVERTERS, COMPRESS & TOP-RIGHT PROFILE)
// -------------------------------------------------------------

@Composable
fun ToolsScreenDesign(
    activity: Activity,
    storedDocs: List<LocalDoc>,
    onOpenDocDirectly: (LocalDoc) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onEditPage: (File) -> Unit = {}
) {
    val colors = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }
    var activeCompletionResult by remember { mutableStateOf<ToolCompletionResult?>(null) }

    var uriToCompress by remember { mutableStateOf<Uri?>(null) }
    var compressionQuality by remember { mutableFloatStateOf(60f) }
    var compressCustomName by remember { mutableStateOf("") }
    var compressOriginalSize by remember { mutableLongStateOf(0L) }
    var compressMimeType by remember { mutableStateOf("") }
    var compressionMode by remember { mutableStateOf(CompressionMode.PRESETS) }
    var selectedPreset by remember { mutableStateOf(CompressionPreset.UNDER_100_KB) }
    var customTargetKbText by remember { mutableStateOf("100") }

    var pendingImageToPdfUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showImageToPdfDialog by remember { mutableStateOf(false) }
    var imageToPdfCustomName by remember { mutableStateOf("") }

    var pendingMergePdfUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showMergePdfDialog by remember { mutableStateOf(false) }
    var mergePdfCustomName by remember { mutableStateOf("") }






    var uriToMaskWatermark by remember { mutableStateOf<Uri?>(null) }
    var maskWatermarkCustomName by remember { mutableStateOf("") }
    var maskWatermarkMimeType by remember { mutableStateOf("") }
    var maskWatermarkOriginalSize by remember { mutableLongStateOf(0L) }
    var doApplyWatermark by remember { mutableStateOf(true) }
    var selectedWatermarkPreset by remember { mutableStateOf("FOR KYC ONLY") }
    var customWatermarkText by remember { mutableStateOf("") }
    var watermarkOpacity by remember { mutableFloatStateOf(26f) }
    var maskWatermarkExportAsPdf by remember { mutableStateOf(true) }

    val maskWatermarkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val size = getUriByteSize(activity, uri)
            val mime = activity.contentResolver.getType(uri) ?: ""
            val originalBase = queryOriginalFileName(activity, uri)
            maskWatermarkOriginalSize = size
            maskWatermarkMimeType = mime
            maskWatermarkCustomName = "${originalBase}_protected"
            val isPdf = mime.contains("pdf", ignoreCase = true)
            maskWatermarkExportAsPdf = isPdf
            doApplyWatermark = true
            selectedWatermarkPreset = "FOR KYC ONLY"
            customWatermarkText = ""
            watermarkOpacity = 26f
            uriToMaskWatermark = uri
        }
    }

    val pdfToJpgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            isProcessing = true
            coroutineScope.launch {
                val newDoc = withContext(Dispatchers.IO) {
                    val resolvedName = queryOriginalFileName(activity, uri)
                    unpackPdfAsInAppDoc(activity, uri, null, resolvedName)
                }
                isProcessing = false
                onRefresh()
                if (newDoc != null) {
                    Toast.makeText(activity, "PDF converted to ${newDoc.pageCount} page(s)!", Toast.LENGTH_SHORT).show()
                    onOpenDocDirectly(newDoc)
                } else {
                    Toast.makeText(activity, "Failed to convert PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val compressPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val size = getUriByteSize(activity, uri)
            val mime = activity.contentResolver.getType(uri) ?: ""
            compressOriginalSize = size
            compressMimeType = mime
            val originalBase = queryOriginalFileName(activity, uri)
            compressCustomName = "${originalBase}_compressed"
            selectedPreset = CompressionPreset.UNDER_100_KB
            compressionMode = CompressionMode.PRESETS
            compressionQuality = 60f
            uriToCompress = uri
        }
    }

    val imageToPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val defaultName = if (uris.size == 1) queryOriginalFileName(activity, uris[0])
            else "Document_${SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())}"
            pendingImageToPdfUris = uris
            imageToPdfCustomName = defaultName
            showImageToPdfDialog = true
        }
    }

    val mergePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.size >= 2) {
            pendingMergePdfUris = uris
            mergePdfCustomName = "Merged_${SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())}"
            showMergePdfDialog = true
        } else if (uris.size == 1) {
            Toast.makeText(activity, "Please select at least 2 PDFs to merge", Toast.LENGTH_SHORT).show()
        }
    }

    if (showImageToPdfDialog && pendingImageToPdfUris.isNotEmpty()) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showImageToPdfDialog = false },
            title = { Text("Name PDF Before Saving", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = imageToPdfCustomName,
                    onValueChange = { imageToPdfCustomName = it },
                    singleLine = true,
                    label = { Text("PDF File Name", color = colors.textMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.cardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val chosenName = sanitizeName(imageToPdfCustomName).ifEmpty { "Document_${System.currentTimeMillis()}" }
                        showImageToPdfDialog = false
                        isProcessing = true
                        coroutineScope.launch {
                            val targetUri = withContext(Dispatchers.IO) { exportUriListAsPdf(activity, chosenName, pendingImageToPdfUris) }
                            isProcessing = false
                            onRefresh()
                            if (targetUri != null) {
                                val bytes = withContext(Dispatchers.IO) { getUriByteSize(activity, targetUri) }
                                activeCompletionResult = ToolCompletionResult(
                                    title = "PDF Created Successfully",
                                    fileName = "$chosenName.pdf",
                                    uri = targetUri,
                                    mimeType = "application/pdf",
                                    newBytes = bytes
                                )
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Save PDF", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImageToPdfDialog = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

    if (showMergePdfDialog && pendingMergePdfUris.isNotEmpty()) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showMergePdfDialog = false },
            title = { Text("Name Merged Document", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = mergePdfCustomName,
                    onValueChange = { mergePdfCustomName = it },
                    singleLine = true,
                    label = { Text("Document Name", color = colors.textMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.cardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val chosenName = sanitizeName(mergePdfCustomName).ifEmpty { "Merged_${System.currentTimeMillis()}" }
                        showMergePdfDialog = false
                        isProcessing = true
                        coroutineScope.launch {
                            val mergeDoc = withContext(Dispatchers.IO) {
                                createMergeWorkspaceDoc(activity, pendingMergePdfUris, chosenName)
                            }
                            isProcessing = false
                            onRefresh()
                            if (mergeDoc != null) {
                                Toast.makeText(activity, "Arrange or delete pages, then tap PDF to save!", Toast.LENGTH_LONG).show()
                                onOpenDocDirectly(mergeDoc)
                            } else {
                                Toast.makeText(activity, "Failed to unpack PDFs for merging", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Open Workspace", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergePdfDialog = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

        if (uriToCompress != null) {
        val isPdf = compressMimeType.contains("pdf", ignoreCase = true)
        val targetKbVal = customTargetKbText.toLongOrNull() ?: 100L
        val estimatedSize = when (compressionMode) {
            CompressionMode.PRESETS -> {
                selectedPreset.targetBytes?.coerceAtMost(compressOriginalSize)
                    ?: (compressOriginalSize * (compressionQuality / 100f) * 0.75f).toLong().coerceAtLeast(1024L)
            }
            CompressionMode.CUSTOM_KB -> {
                (targetKbVal * 1024L).coerceAtMost(compressOriginalSize).coerceAtLeast(1024L)
            }
            CompressionMode.SLIDER -> {
                (compressOriginalSize * (compressionQuality / 100f) * 0.75f).toLong().coerceAtLeast(1024L)
            }
        }
        val estimatedSaved = ((compressOriginalSize - estimatedSize) * 100f / compressOriginalSize.coerceAtLeast(1L)).roundToInt().coerceIn(1, 95)

        val dialogTitle = "Compress & Format"

        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { uriToCompress = null },
            title = {
                Text(
                    text = dialogTitle,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = compressCustomName,
                        onValueChange = { compressCustomName = it },
                        singleLine = true,
                        label = { Text("Output File Name", color = colors.textMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.cardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 3-way Mode Switcher Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.cardSubtle)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CompressionMode.values().forEach { mode ->
                            val isSelected = compressionMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) colors.accent else Color.Transparent)
                                    .clickable { compressionMode = mode }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = mode.title,
                                    color = if (isSelected) Color.White else colors.textMuted,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    when (compressionMode) {
                        CompressionMode.PRESETS -> {
                            val presetsList = listOf(
                                CompressionPreset.UNDER_50_KB,
                                CompressionPreset.UNDER_100_KB,
                                CompressionPreset.UNDER_200_KB
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = if (isPdf) "Select PDF Target Limit:" else "Select Target Limit:",
                                    color = colors.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    presetsList.forEach { p ->
                                        val isSel = selectedPreset == p
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSel) colors.accent.copy(alpha = 0.2f) else colors.cardSubtle)
                                                .border(
                                                    width = 1.dp,
                                                    color = if (isSel) colors.accent else colors.cardBorder,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable { selectedPreset = p }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    text = p.label,
                                                    color = if (isSel) colors.accent else colors.textPrimary,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                                )
                                                if (p.badge.isNotEmpty()) {
                                                    Text(
                                                        text = "(${p.badge})",
                                                        color = if (isSel) colors.accent else colors.textMuted,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.cardSubtle.copy(alpha = 0.6f))
                                        .border(1.dp, colors.cardBorder, RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = selectedPreset.description,
                                        color = colors.textMuted,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }

                        CompressionMode.CUSTOM_KB -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = customTargetKbText,
                                    onValueChange = { customTargetKbText = it.filter { ch -> ch.isDigit() }.take(5) },
                                    singleLine = true,
                                    label = { Text("Target Maximum Size", color = colors.textMuted) },
                                    trailingIcon = { Text("KB ", color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = colors.textPrimary,
                                        unfocusedTextColor = colors.textPrimary,
                                        focusedBorderColor = colors.accent,
                                        unfocusedBorderColor = colors.cardBorder
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("30", "50", "100", "200", "500").forEach { quickKb ->
                                        val isQuickSel = customTargetKbText == quickKb
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isQuickSel) colors.accent.copy(alpha = 0.2f) else colors.cardSubtle)
                                                .border(1.dp, if (isQuickSel) colors.accent else colors.cardBorder, RoundedCornerShape(6.dp))
                                                .clickable { customTargetKbText = quickKb }
                                                .padding(vertical = 5.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${quickKb}K",
                                                color = if (isQuickSel) colors.accent else colors.textPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = if (isQuickSel) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        CompressionMode.SLIDER -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Quality: ${compressionQuality.roundToInt()}%", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(if (compressionQuality > 70) "High Quality" else if (compressionQuality > 40) "Balanced" else "Max Compress", color = colors.textMuted, fontSize = 12.sp)
                                }
                                Slider(
                                    value = compressionQuality,
                                    onValueChange = { compressionQuality = it },
                                    valueRange = 15f..90f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = colors.accent,
                                        activeTrackColor = colors.accent,
                                        inactiveTrackColor = colors.cardBorder
                                    )
                                )
                            }
                        }
                    }

                    // Size comparison summary card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.cardSubtle),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Original Size", color = colors.textMuted, fontSize = 11.sp)
                                    Text(formatSize(compressOriginalSize), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                                Text("➔", color = colors.textMuted, fontSize = 16.sp)
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = if (compressionMode == CompressionMode.SLIDER) "Estimated Size" else "Target Maximum",
                                        color = colors.textMuted,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = if (compressionMode == CompressionMode.SLIDER) formatSize(estimatedSize) else "≤ " + formatSize(estimatedSize),
                                        color = colors.accentGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                            if (estimatedSaved > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Approx. ~$estimatedSaved% size reduction", color = colors.accentAmber, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val buttonLabel = "Compress & Save"

                Button(
                    onClick = {
                        val uri = uriToCompress!!
                        val quality = compressionQuality.roundToInt()
                        val chosenName = sanitizeName(compressCustomName).ifEmpty { "Compressed_${System.currentTimeMillis()}" }
                        val targetLimit = when (compressionMode) {
                            CompressionMode.PRESETS -> selectedPreset.targetBytes
                            CompressionMode.CUSTOM_KB -> (customTargetKbText.toLongOrNull() ?: 100L) * 1024L
                            CompressionMode.SLIDER -> null
                        }
                        val presetToPass = if (compressionMode == CompressionMode.PRESETS) selectedPreset else CompressionPreset.NONE
                        uriToCompress = null
                        isProcessing = true

                        coroutineScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                performCustomCompression(
                                    context = activity,
                                    uri = uri,
                                    desiredName = chosenName,
                                    qualityPercent = quality,
                                    targetBytesLimit = targetLimit,
                                    preset = presetToPass
                                )
                            }
                            isProcessing = false
                            if (result != null) {
                                activeCompletionResult = result
                                onRefresh()
                            } else {
                                Toast.makeText(activity, "Compression failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text(buttonLabel, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { uriToCompress = null }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

    activeCompletionResult?.let { result ->
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { activeCompletionResult = null },
            title = { Text(result.title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(result.fileName, color = colors.accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

                    if (result.originalBytes > 0L && result.newBytes > 0L) {
                        val savedPct = ((result.originalBytes - result.newBytes) * 100f / result.originalBytes).roundToInt()
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.cardSubtle),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Size: ${formatSize(result.originalBytes)} ➔ ${formatSize(result.newBytes)}",
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Saved $savedPct% file size!",
                                    color = colors.accentGreen,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else if (result.newBytes > 0L) {
                        Text("Size: ${formatSize(result.newBytes)}", color = colors.textMuted, fontSize = 13.sp)
                    }

                    Text("Saved to Downloads/ScanVault", color = colors.textMuted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        openUri(activity, result.uri, result.mimeType)
                        activeCompletionResult = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("View / Open", color = Color.White)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = result.mimeType
                                putExtra(Intent.EXTRA_STREAM, result.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            activity.startActivity(Intent.createChooser(shareIntent, "Share Result"))
                        },
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                    ) {
                        Text("Share", color = colors.textPrimary)
                    }
                    TextButton(onClick = { activeCompletionResult = null }) { Text("Done", color = colors.textMuted) }
                }
            }
        )
    }

    if (uriToMaskWatermark != null) {
        val pickedUri = uriToMaskWatermark!!
        val isPdf = maskWatermarkMimeType.contains("pdf", ignoreCase = true)
        val finalWatermark = if (doApplyWatermark) {
            if (customWatermarkText.isNotBlank()) customWatermarkText.trim() else selectedWatermarkPreset
        } else null

        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { uriToMaskWatermark = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🌊", fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Watermark Document",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Source: ${queryOriginalFileName(activity, pickedUri)} (${formatSize(maskWatermarkOriginalSize)})",
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )

                    OutlinedTextField(
                        value = maskWatermarkCustomName,
                        onValueChange = { maskWatermarkCustomName = it },
                        singleLine = true,
                        label = { Text("Output File Name", color = colors.textMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.cardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )


                    // Watermark Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.cardSubtle),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { doApplyWatermark = !doApplyWatermark }
                            ) {
                                Checkbox(
                                    checked = doApplyWatermark,
                                    onCheckedChange = { doApplyWatermark = it },
                                    colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Apply Semi-Transparent Watermark",
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            if (doApplyWatermark) {
                                Text("Quick Presets:", color = colors.textMuted, fontSize = 11.sp)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("FOR KYC ONLY", "CONFIDENTIAL", "OFFICIAL USE").forEach { preset ->
                                        val isSel = selectedWatermarkPreset == preset && customWatermarkText.isBlank()
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isSel) colors.accent.copy(alpha = 0.2f) else colors.surface)
                                                .border(1.dp, if (isSel) colors.accent else colors.cardBorder, RoundedCornerShape(6.dp))
                                                .clickable {
                                                    selectedWatermarkPreset = preset
                                                    customWatermarkText = ""
                                                }
                                                .padding(vertical = 5.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = preset,
                                                color = if (isSel) colors.accent else colors.textPrimary,
                                                fontSize = 10.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = customWatermarkText,
                                    onValueChange = { customWatermarkText = it },
                                    singleLine = true,
                                    placeholder = { Text(selectedWatermarkPreset, color = colors.textMuted) },
                                    label = { Text("Custom Watermark Text (Optional)", color = colors.textMuted) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = colors.textPrimary,
                                        unfocusedTextColor = colors.textPrimary,
                                        focusedBorderColor = colors.accent,
                                        unfocusedBorderColor = colors.cardBorder
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Opacity: ${watermarkOpacity.roundToInt()}%", color = colors.textPrimary, fontSize = 12.sp)
                                        Text(if (watermarkOpacity > 35) "Strong" else "Subtle", color = colors.textMuted, fontSize = 11.sp)
                                    }
                                    Slider(
                                        value = watermarkOpacity,
                                        onValueChange = { watermarkOpacity = it },
                                        valueRange = 15f..50f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = colors.accent,
                                            activeTrackColor = colors.accent,
                                            inactiveTrackColor = colors.cardBorder
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Format selection if image
                    if (!isPdf) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.cardSubtle),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Export Format:", color = colors.textMuted, fontSize = 11.sp)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (maskWatermarkExportAsPdf) colors.accent.copy(alpha = 0.2f) else colors.surface)
                                            .border(1.dp, if (maskWatermarkExportAsPdf) colors.accent else colors.cardBorder, RoundedCornerShape(8.dp))
                                            .clickable { maskWatermarkExportAsPdf = true }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "📄 PDF Document",
                                            color = if (maskWatermarkExportAsPdf) colors.accent else colors.textPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = if (maskWatermarkExportAsPdf) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (!maskWatermarkExportAsPdf) colors.accent.copy(alpha = 0.2f) else colors.surface)
                                            .border(1.dp, if (!maskWatermarkExportAsPdf) colors.accent else colors.cardBorder, RoundedCornerShape(8.dp))
                                            .clickable { maskWatermarkExportAsPdf = false }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "🖼️ JPG Image",
                                            color = if (!maskWatermarkExportAsPdf) colors.accent else colors.textPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = if (!maskWatermarkExportAsPdf) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val base = sanitizeName(maskWatermarkCustomName).ifEmpty { "Watermarked_${System.currentTimeMillis()}" }
                        val exportPdf = isPdf || maskWatermarkExportAsPdf
                        val wm = finalWatermark
                        val op = watermarkOpacity.roundToInt()
                        uriToMaskWatermark = null
                        isProcessing = true
                        coroutineScope.launch {
                            val res = if (exportPdf) {
                                MaskAndWatermarkEngine.exportAsPdf(
                                    context = activity,
                                    inputUri = pickedUri,
                                    desiredName = base,
                                    watermarkText = wm,
                                    watermarkOpacity = op
                                )
                            } else {
                                MaskAndWatermarkEngine.exportAsJpg(
                                    context = activity,
                                    inputUri = pickedUri,
                                    desiredName = base,
                                    watermarkText = wm,
                                    watermarkOpacity = op
                                )
                            }
                            isProcessing = false
                            if (res != null) {
                                activeCompletionResult = res
                                onRefresh()
                            } else {
                                Toast.makeText(activity, "Export failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Apply Watermark", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { uriToMaskWatermark = null }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

    val pdfConverters = remember {
        listOf(
            ToolItem("PDF to JPEG", "🖼️", "Convert PDF into photo pages & open in manager", "pdf_to_jpeg")
        )
    }

    val imageConverters = remember {
        listOf(
            ToolItem("Image to PDF", "📄", "Combine multiple photos into a single PDF", "image_to_pdf")
        )
    }

    val directTools = remember {
        listOf(
            ToolItem("Compress File", "🗜️", "Compress PDF & JPEG with Target KB, Presets & Slider", "compress_file"),
            ToolItem("Merge PDF", "📑", "Arrange & delete pages visually before merging", "merge_pdf"),
            ToolItem("Apply Watermark", "🌊", "Add semi-transparent KYC or confidential watermark", "mask_watermark")
        )
    }

    fun onToolClick(tool: ToolItem) {
        when (tool.id) {
            "compress_file" -> compressPickerLauncher.launch("*/*")
            "pdf_to_jpeg" -> pdfToJpgLauncher.launch("application/pdf")
            "image_to_pdf" -> imageToPdfLauncher.launch("image/*")
            "merge_pdf" -> mergePdfLauncher.launch("application/pdf")
            "mask_watermark" -> maskWatermarkPickerLauncher.launch("*/*")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Document Tools",
                color = colors.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(colors.cardSubtle)
                        .clickable { onNavigateToSettings() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("👤", fontSize = 16.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Convert, compress, and edit documents on your device",
            color = colors.textMuted,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("PDF Converters", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            items(pdfConverters.chunked(2)) { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    pair.forEach { tool ->
                        ToolCard(tool = tool, modifier = Modifier.weight(1f), onClick = { onToolClick(tool) })
                    }
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Image Converters", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            items(imageConverters.chunked(2)) { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    pair.forEach { tool ->
                        ToolCard(tool = tool, modifier = Modifier.weight(1f), onClick = { onToolClick(tool) })
                    }
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Direct Tools", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            items(directTools.chunked(2)) { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    pair.forEach { tool ->
                        ToolCard(tool = tool, modifier = Modifier.weight(1f), onClick = { onToolClick(tool) })
                    }
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }

}

@Composable
fun ToolCard(
    tool: ToolItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder)),
        modifier = modifier
            .height(126.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(tool.icon, fontSize = 28.sp)
            Column {
                Text(
                    text = tool.title,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = tool.description,
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    maxLines = 2,
                    lineHeight = 13.sp
                )
            }
        }
    }
}

