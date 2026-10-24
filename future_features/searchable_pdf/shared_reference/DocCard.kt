package com.example.ownscan

import android.app.Activity
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// -------------------------------------------------------------
// DOCUMENT CARD (DYNAMIC DRIVE BACKUP / DELETE TOGGLE + CLOUD ICON)
// -------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocCardWithMenu(
    activity: Activity,
    doc: LocalDoc,
    userFolders: List<UserFolder>,
    isFavorite: Boolean,
    isBackedUpToDrive: Boolean,
    onBackupStatusChanged: (Boolean) -> Unit,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenDoc: () -> Unit,
    onEditPage: (File) -> Unit,
    onRefresh: () -> Unit,
    searchSnippet: SearchResultSnippet? = null,
    isUnlockedInSession: Boolean = false,
    onOpenLocalBackup: () -> Unit = {}
) {
    val colors = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showLockGateDialog by remember { mutableStateOf(false) }
    var showDeviceLockRequiredDialog by remember { mutableStateOf(false) }
    var showExpiryDialog by remember { mutableStateOf(false) }
    var showLockedCloudBackupDialog by remember { mutableStateOf(false) }

    var showSharePdfRenameDialog by remember { mutableStateOf(false) }
    var sharePdfNameText by remember { mutableStateOf(doc.displayName) }
    var protectPdfWithPassword by remember { mutableStateOf(false) }
    var pdfExportPassword by remember { mutableStateOf("") }
    var sharePdfMaskAadhaar by remember { mutableStateOf(false) }
    var sharePdfAddWatermark by remember { mutableStateOf(false) }
    var sharePdfWatermarkText by remember { mutableStateOf("FOR KYC ONLY") }
    var sharePdfSearchable by remember { mutableStateOf(true) }

    val totalBytes = remember(doc.imageFiles) { doc.imageFiles.sumOf { it.length() } }
    val formattedSize = remember(totalBytes) { formatSize(totalBytes) }

    if (showInfoDialog) {
        val creationDate = remember(doc.timestamp) {
            SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date(doc.timestamp))
        }
        val creationTime = remember(doc.timestamp) {
            SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(doc.timestamp))
        }

        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Document Info", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(doc.displayName, color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Date Created:", color = colors.textMuted, fontSize = 13.sp)
                        Text(creationDate, color = colors.textPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Time Created:", color = colors.textMuted, fontSize = 13.sp)
                        Text(creationTime, color = colors.textPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("File Size:", color = colors.textMuted, fontSize = 13.sp)
                        Text(formattedSize, color = colors.accentGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Cloud Status:", color = colors.textMuted, fontSize = 13.sp)
                        Text(
                            text = if (isBackedUpToDrive) "☁️ Backed up in Drive" else "Local only",
                            color = if (isBackedUpToDrive) colors.accentGreen else colors.textMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Page Count:", color = colors.textMuted, fontSize = 13.sp)
                        Text("${doc.pageCount} page(s)", color = colors.textPrimary, fontSize = 13.sp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Folder:", color = colors.textMuted, fontSize = 13.sp)
                        Text(doc.parentFolder ?: "Root Directory", color = colors.textPrimary, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Path: ${doc.directory.absolutePath}",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showInfoDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = colors.accent)) {
                    Text("Close", color = Color.White)
                }
            }
        )
    }

    if (showLockGateDialog) {
        LockAccessGateDialog(
            colors = colors,
            itemName = doc.displayName,
            isFolder = false,
            onDismiss = { showLockGateDialog = false },
            onConfirmLock = {
                showLockGateDialog = false
                BiometricAuthManager.authenticate(
                    activity = activity as FragmentActivity,
                    title = "Lock '${doc.displayName}'",
                    subtitle = "Verify biometric or device credential to lock this document",
                    onSuccess = {
                        coroutineScope.launch {
                            val ok = DocumentRepository.updateDocLockStatus(activity, doc, true)
                            if (ok) {
                                onRefresh()
                                Toast.makeText(activity, "'${doc.displayName}' locked", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(activity, "Failed to lock '${doc.displayName}'", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onError = { err ->
                        Toast.makeText(activity, "Lock cancelled: $err", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
    }

    if (showLockedCloudBackupDialog) {
        LockedDocCloudBackupDialog(
            docName = doc.displayName,
            lockedCount = 1,
            unlockedCount = 0,
            onDismiss = { showLockedCloudBackupDialog = false },
            onOpenLocalBackup = {
                showLockedCloudBackupDialog = false
                onOpenLocalBackup()
            }
        )
    }

    if (showDeviceLockRequiredDialog) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showDeviceLockRequiredDialog = false },
            title = { Text("Device Lock Required", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "ScanVault uses your device screen lock (PIN, Pattern, Password, or Biometric) to protect your locked files.\n\nPlease set up a screen lock in your device Settings before locking documents.",
                    color = colors.textMuted
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    onClick = {
                        showDeviceLockRequiredDialog = false
                        BiometricAuthManager.openSecuritySettings(activity)
                    }
                ) {
                    Text("Open Settings", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceLockRequiredDialog = false }) {
                    Text("Cancel", color = colors.textMuted)
                }
            }
        )
    }

    if (showExpiryDialog) {
        ExpiryDatePickerDialog(
            colors = colors,
            initialDate = doc.expiryDate,
            onDismiss = { showExpiryDialog = false },
            onDateSelected = { newExpiry ->
                showExpiryDialog = false
                coroutineScope.launch {
                    DocumentRepository.updateDocExpiryDate(activity, doc, newExpiry)
                    onRefresh()
                    Toast.makeText(
                        activity,
                        if (newExpiry != null) "Expiry date set: ${ExpiryHelper.formatDate(newExpiry)}" else "Expiry date cleared",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    if (showSharePdfRenameDialog) {
        SharePdfRenameDialog(
            initialName = doc.displayName,
            onDismiss = { showSharePdfRenameDialog = false },
            onConfirm = { chosenName, pwd, maskAadhaar, wm, searchablePdf ->
                showSharePdfRenameDialog = false
                val finalName = sanitizeName(chosenName).ifEmpty { doc.displayName }
                shareDocAsPdf(
                    activity,
                    doc,
                    finalName,
                    password = pwd,
                    maskAadhaar = maskAadhaar,
                    watermarkText = wm,
                    searchablePdf = searchablePdf
                )
            }
        )
    }

    if (showShareDialog) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showShareDialog = false },
            title = { Text("Share Document", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(doc.displayName, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Choose format to share:", color = colors.textMuted, fontSize = 13.sp)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showShareDialog = false
                                sharePdfNameText = doc.displayName
                                showSharePdfRenameDialog = true
                            },
                        colors = CardDefaults.cardColors(containerColor = colors.cardSubtle),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📄", fontSize = 26.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Share as PDF", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("${doc.pageCount} page(s) • Est. Size: ~$formattedSize", color = colors.textMuted, fontSize = 12.sp)
                            }
                            Text("Rename & Share ›", color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showShareDialog = false
                                shareDocAsJpg(activity, doc)
                            },
                        colors = CardDefaults.cardColors(containerColor = colors.cardSubtle),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🖼️", fontSize = 26.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Share as JPG Images", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("${doc.pageCount} image(s) • Size: $formattedSize", color = colors.textMuted, fontSize = 12.sp)
                            }
                            Text("Share ›", color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showShareDialog = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

    if (showRenameDialog) {
        RenameDocumentDialog(
            currentName = doc.displayName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                val sanitized = sanitizeName(newName)
                if (sanitized.isNotBlank()) {
                    if (renameDoc(doc.directory, sanitized)) {
                        onRefresh()
                        showRenameDialog = false
                        Toast.makeText(activity, "Renamed to '$sanitized'", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showMoveDialog) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showMoveDialog = false },
            title = { Text("Move '${doc.displayName}'", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select destination folder:", color = colors.textMuted, fontSize = 14.sp)

                    if (doc.parentFolder != null) {
                        OutlinedButton(
                            onClick = {
                                if (moveDocToRoot(activity, doc.directory)) {
                                    onRefresh()
                                    showMoveDialog = false
                                    Toast.makeText(activity, "Moved to Root", Toast.LENGTH_SHORT).show()
                                }
                            },
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📁 Root (Outside folders)", color = colors.textPrimary)
                        }
                    }

                    userFolders.filter { it.name != doc.parentFolder }.forEach { folder ->
                        Button(
                            onClick = {
                                if (moveDocToFolder(activity, doc.directory, folder.name)) {
                                    onRefresh()
                                    showMoveDialog = false
                                    Toast.makeText(activity, "Moved to '📁 ${folder.name}'", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.cardSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📁 ${folder.name}", color = colors.textPrimary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

    if (showCopyDialog) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showCopyDialog = false },
            title = { Text("Copy '${doc.displayName}'", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select destination to duplicate file:", color = colors.textMuted, fontSize = 14.sp)

                    OutlinedButton(
                        onClick = {
                            if (copyDocToRoot(activity, doc.directory)) {
                                onRefresh()
                                showCopyDialog = false
                                Toast.makeText(activity, "Copied to Root", Toast.LENGTH_SHORT).show()
                            }
                        },
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📁 Root (Outside folders)", color = colors.textPrimary)
                    }

                    userFolders.forEach { folder ->
                        Button(
                            onClick = {
                                if (copyDocToFolder(activity, doc.directory, folder.name)) {
                                    onRefresh()
                                    showCopyDialog = false
                                    Toast.makeText(activity, "Copied to '📁 ${folder.name}'", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.cardSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📁 ${folder.name}", color = colors.textPrimary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCopyDialog = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Move to Trash?", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Move '${doc.displayName}' to Trash? It can be restored within 30 days.", color = colors.textMuted) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        coroutineScope.launch {
                            DocumentRepository.moveToTrash(activity, doc)
                            onRefresh()
                            showDeleteConfirm = false
                            Toast.makeText(activity, "'${doc.displayName}' moved to Trash", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Move to Trash", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

    if (showTagDialog) {
        DocTagEditDialog(
            doc = doc,
            colors = colors,
            onDismiss = { showTagDialog = false },
            onSaveTags = { updatedTags ->
                coroutineScope.launch {
                    DocumentRepository.updateDocTags(activity, doc, updatedTags)
                    onRefresh()
                    showTagDialog = false
                    Toast.makeText(activity, "Tags updated for '${doc.displayName}'", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 68.dp)
            .clip(RoundedCornerShape(26.dp))
            .border(
                1.5.dp,
                if (isSelected) colors.accent else colors.cardBorder,
                RoundedCornerShape(26.dp)
            )
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) onToggleSelect() else onOpenDoc()
                },
                onLongClick = {
                    onLongPress()
                }
            ),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) colors.accent.copy(alpha = 0.15f) else colors.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            if (doc.isLocked && !isUnlockedInSession) {
                Surface(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    color = colors.cardSubtle,
                    border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.5f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("🔒", fontSize = 20.sp)
                    }
                }
            } else {
                val displayFile = if (doc.isLocked && isUnlockedInSession) {
                    val sDir = LockedDocCryptoManager.getUnlockedSessionDir(activity, doc.folderName)
                    sDir?.listFiles { f ->
                        val ext = f.extension.lowercase(Locale.ROOT)
                        ext in setOf("jpg", "jpeg", "png")
                    }?.minByOrNull { it.name } ?: doc.imageFiles.firstOrNull()
                } else {
                    doc.imageFiles.firstOrNull()
                }
                AsyncThumbnail(
                    file = displayFile,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    widthPx = 160,
                    heightPx = 200
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f).padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = doc.displayName,
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (doc.isLocked) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isUnlockedInSession) "🔓" else "🔒", fontSize = 13.sp)
                    }
                    if (isFavorite) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("★", color = colors.accentAmber, fontSize = 14.sp)
                    }
                    if (isBackedUpToDrive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("☁️", fontSize = 13.sp)
                    }
                }
                Text(
                    text = "${doc.pageCount} page(s) • ${formatDate(doc.timestamp)}",
                    color = colors.textMuted,
                    fontSize = 11.sp
                )

                if (doc.tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        doc.tags.take(3).forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = colors.accent.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "#$tag",
                                    color = colors.accent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (doc.tags.size > 3) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = colors.cardSubtle
                            ) {
                                Text(
                                    text = "+${doc.tags.size - 3}",
                                    color = colors.textMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                if (doc.expiryDate != null && doc.expiryDate > 0) {
                    val status = ExpiryHelper.getExpiryStatus(doc.expiryDate)
                    val (badgeText, badgeColor) = when (status) {
                        is ExpiryStatus.Expired -> Pair("⚠️ Expired ${status.daysAgo}d ago", colors.accentRed)
                        is ExpiryStatus.ExpiringSoon -> Pair("⏳ Exp in ${status.daysLeft}d", colors.accentAmber)
                        is ExpiryStatus.Valid -> Pair("📅 Exp in ${status.daysLeft}d", colors.accentGreen)
                        ExpiryStatus.None -> Pair("", colors.textMuted)
                    }
                    if (badgeText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.15f),
                            border = BorderStroke(0.5.dp, badgeColor.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = badgeText,
                                color = badgeColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (searchSnippet != null && searchSnippet.snippet.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.cardSubtle,
                        border = BorderStroke(0.5.dp, colors.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(
                                text = searchSnippet.matchType,
                                color = colors.accent,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            HighlightedSnippetText(
                                snippet = searchSnippet.snippet,
                                query = searchSnippet.queryTerm,
                                textColor = colors.textPrimary,
                                highlightColor = colors.accent,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text("⋮", color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(colors.surface)
                ) {
                    if (isBackedUpToDrive) {
                        DropdownMenuItem(
                            text = { Text("Delete from Drive", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Text("🗑️") },
                            onClick = {
                                menuExpanded = false
                                val account = DriveBackupManager.getSignedInAccount(activity)
                                if (account == null) {
                                    Toast.makeText(activity, "Please sign into Google in Settings first", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(activity, "Deleting '${doc.displayName}' from Drive...", Toast.LENGTH_SHORT).show()
                                    coroutineScope.launch {
                                        when (val res = DriveBackupManager.deleteSingleDocFromDrive(activity, account, doc)) {
                                            is DriveResult.Success -> {
                                                onBackupStatusChanged(false)
                                                Toast.makeText(activity, "'${doc.displayName}' deleted from Drive!", Toast.LENGTH_SHORT).show()
                                                onRefresh()
                                            }
                                            is DriveResult.Failure -> {
                                                Toast.makeText(activity, "Failed to delete: ${res.displayMessage}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Backup to Drive", color = colors.textPrimary) },
                            leadingIcon = { Text("☁️") },
                            onClick = {
                                menuExpanded = false
                                if (doc.isLocked) {
                                    showLockedCloudBackupDialog = true
                                    return@DropdownMenuItem
                                }
                                val account = DriveBackupManager.getSignedInAccount(activity)
                                if (account == null) {
                                    Toast.makeText(activity, "Please sign into Google in Settings first", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(activity, "Backing up '${doc.displayName}'...", Toast.LENGTH_SHORT).show()
                                    coroutineScope.launch {
                                        when (val res = DriveBackupManager.backupSingleDoc(activity, account, doc, forceUpload = true)) {
                                            is DriveResult.Success -> {
                                                onBackupStatusChanged(true)
                                                Toast.makeText(activity, "'${doc.displayName}' backed up to Drive!", Toast.LENGTH_SHORT).show()
                                                onRefresh()
                                            }
                                            is DriveResult.Failure -> {
                                                Toast.makeText(activity, "Backup failed: ${res.displayMessage}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }

                    DropdownMenuItem(
                        text = { Text("File Info", color = colors.textPrimary) },
                        leadingIcon = { Text("ℹ️") },
                        onClick = {
                            menuExpanded = false
                            showInfoDialog = true
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Share", color = colors.textPrimary) },
                        leadingIcon = { Text("📤") },
                        onClick = {
                            menuExpanded = false
                            showShareDialog = true
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                                color = colors.textPrimary
                            )
                        },
                        leadingIcon = { Text(if (isFavorite) "★" else "☆") },
                        onClick = {
                            menuExpanded = false
                            onToggleFavorite()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Move", color = colors.textPrimary) },
                        leadingIcon = { Text("📦") },
                        onClick = {
                            menuExpanded = false
                            showMoveDialog = true
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Copy", color = colors.textPrimary) },
                        leadingIcon = { Text("📋") },
                        onClick = {
                            menuExpanded = false
                            showCopyDialog = true
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Rename", color = colors.textPrimary) },
                        leadingIcon = { Text("✏️") },
                        onClick = {
                            menuExpanded = false
                            showRenameDialog = true
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Tags", color = colors.textPrimary) },
                        leadingIcon = { Text("🏷️") },
                        onClick = {
                            menuExpanded = false
                            showTagDialog = true
                        }
                    )

                    DropdownMenuItem(
                        text = { Text(if (doc.expiryDate != null && doc.expiryDate > 0) "Edit Expiry Date" else "Set Expiry Date", color = colors.textPrimary) },
                        leadingIcon = { Text("📅") },
                        onClick = {
                            menuExpanded = false
                            showExpiryDialog = true
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Edit", color = colors.textPrimary) },
                        leadingIcon = { Text("🎨") },
                        onClick = {
                            menuExpanded = false
                            doc.imageFiles.firstOrNull()?.let { onEditPage(it) }
                        }
                    )

                    DropdownMenuItem(
                        text = { Text(if (doc.isLocked) "Unlock Document" else "Lock Document", color = colors.textPrimary) },
                        leadingIcon = { Text(if (doc.isLocked) "🔓" else "🔒") },
                        onClick = {
                            menuExpanded = false
                            if (doc.isLocked) {
                                BiometricAuthManager.authenticate(
                                    activity = activity as FragmentActivity,
                                    title = "Unlock ${doc.displayName}",
                                    onSuccess = {
                                        coroutineScope.launch {
                                            val ok = DocumentRepository.updateDocLockStatus(activity, doc, false)
                                            if (ok) {
                                                onRefresh()
                                                Toast.makeText(activity, "'${doc.displayName}' unlocked", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(activity, "Failed to unlock '${doc.displayName}'", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    onError = { err ->
                                        Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                                    }
                                )
                            } else {
                                if (!BiometricAuthManager.isAuthAvailable(activity)) {
                                    showDeviceLockRequiredDialog = true
                                } else {
                                    showLockGateDialog = true
                                }
                            }
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Text("🗑️") },
                        onClick = {
                            menuExpanded = false
                            if (doc.isLocked) {
                                BiometricAuthManager.authenticate(
                                    activity = activity as FragmentActivity,
                                    title = "Delete '${doc.displayName}'",
                                    subtitle = "Verify biometric or device credential to delete locked document",
                                    onSuccess = {
                                        showDeleteConfirm = true
                                    },
                                    onError = { err ->
                                        Toast.makeText(activity, "Authentication failed: $err", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            } else {
                                showDeleteConfirm = true
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
            Text("›", color = colors.textMuted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun RenameDocumentDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val colors = LocalAppColors.current
    var renameValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = currentName,
                selection = TextRange(0, currentName.length)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
        // If the selection is lost when focus is gained, re-apply it after requestFocus()
        renameValue = renameValue.copy(
            selection = TextRange(0, renameValue.text.length)
        )
    }

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = { Text("Rename Document", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = renameValue,
                onValueChange = { renameValue = it },
                singleLine = true,
                label = { Text("New Name", color = colors.textMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.cardBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(renameValue.text.trim())
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
            ) {
                Text("Rename", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textMuted) }
        }
    )
}

@Composable
fun PillButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) colors.accent else colors.cardSubtle)
            .border(1.dp, if (isSelected) colors.accent else colors.cardBorder, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else colors.textMuted,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}



@Composable
fun SharePdfRenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, password: String?, maskAadhaar: Boolean, watermarkText: String?, searchablePdf: Boolean) -> Unit
) {
    val colors = LocalAppColors.current
    var renameValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialName,
                selection = TextRange(0, initialName.length)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
        renameValue = renameValue.copy(
            selection = TextRange(0, renameValue.text.length)
        )
    }

    var protectPdfWithPassword by remember { mutableStateOf(false) }
    var pdfExportPassword by remember { mutableStateOf("") }
    var sharePdfMaskAadhaar by remember { mutableStateOf(false) }
    var sharePdfAddWatermark by remember { mutableStateOf(false) }
    var sharePdfWatermarkText by remember { mutableStateOf("FOR KYC ONLY") }
    var sharePdfSearchable by remember { mutableStateOf(true) }

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = { Text("Name PDF Before Sharing", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the name for the shared PDF file:", color = colors.textMuted, fontSize = 13.sp)
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("PDF File Name", color = colors.textMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.cardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { protectPdfWithPassword = !protectPdfWithPassword }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = protectPdfWithPassword,
                        onCheckedChange = { protectPdfWithPassword = it },
                        colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("🔒 Protect PDF with Password", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                if (protectPdfWithPassword) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.accentAmber.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, colors.accentAmber.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "⚠️ Forgotten passwords cannot be recovered. ScanVault never stores this password.",
                            color = colors.accentAmber,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(8.dp),
                            lineHeight = 14.sp
                        )
                    }

                    OutlinedTextField(
                        value = pdfExportPassword,
                        onValueChange = { pdfExportPassword = it },
                        singleLine = true,
                        label = { Text("PDF Password", color = colors.textMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.cardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Mask Aadhaar option
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { sharePdfMaskAadhaar = !sharePdfMaskAadhaar }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = sharePdfMaskAadhaar,
                        onCheckedChange = { sharePdfMaskAadhaar = it },
                        colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text("🛡️ Mask Aadhaar (UIDAI Compliant)", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Hide first 8 digits (XXXX XXXX 1234)", color = colors.textMuted, fontSize = 11.sp)
                    }
                }

                // Watermark option
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { sharePdfAddWatermark = !sharePdfAddWatermark }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = sharePdfAddWatermark,
                        onCheckedChange = { sharePdfAddWatermark = it },
                        colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("🌊 Add Watermark", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                if (sharePdfAddWatermark) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("FOR KYC ONLY", "CONFIDENTIAL", "OFFICIAL USE").forEach { preset ->
                                val isSel = sharePdfWatermarkText == preset
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) colors.accent.copy(alpha = 0.2f) else colors.cardSubtle)
                                        .border(1.dp, if (isSel) colors.accent else colors.cardBorder, RoundedCornerShape(6.dp))
                                        .clickable { sharePdfWatermarkText = preset }
                                        .padding(vertical = 4.dp),
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
                            value = sharePdfWatermarkText,
                            onValueChange = { sharePdfWatermarkText = it },
                            singleLine = true,
                            label = { Text("Watermark Text", color = colors.textMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary,
                                focusedBorderColor = colors.accent,
                                unfocusedBorderColor = colors.cardBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Searchable PDF (OCR text layer) option
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { sharePdfSearchable = !sharePdfSearchable }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = sharePdfSearchable,
                        onCheckedChange = { sharePdfSearchable = it },
                        colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text("🔍 Searchable PDF (Selectable text)", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Select, copy & search text with Ctrl+F", color = colors.textMuted, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pwd = if (protectPdfWithPassword) pdfExportPassword.trim() else null
                    val wm = if (sharePdfAddWatermark && sharePdfWatermarkText.isNotBlank()) sharePdfWatermarkText.trim() else null
                    onConfirm(renameValue.text.trim(), pwd, sharePdfMaskAadhaar, wm, sharePdfSearchable)
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
            ) {
                Text("Share PDF", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textMuted) }
        }
    )
}
