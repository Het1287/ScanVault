package com.example.ownscan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ownscan.ui.icons.AppIcons

@Composable
fun MultiSelectTopBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit
) {
    val colors = LocalAppColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(colors.surface)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onClearSelection,
                modifier = Modifier.semantics { contentDescription = "Clear Selection" }
            ) {
                Text(AppIcons.CloseGlyph, color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$selectedCount selected",
                color = colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        TextButton(onClick = onSelectAll) {
            Text(
                text = if (selectedCount == totalCount) "Deselect All" else "Select All",
                color = colors.accent,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * Reusable BottomBarAction component extracting repeated multi-select actions.
 * Provides accessible semantics and unified padding/shape styling.
 */
@Composable
fun BottomBarAction(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color? = null
) {
    val colors = LocalAppColors.current
    val effectiveColor = textColor ?: colors.textPrimary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
            }
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 18.sp)
        }
        Text(label, color = effectiveColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun BottomBarAction(
    painter: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
    iconTint: Color? = null
) {
    val colors = LocalAppColors.current
    val effectiveColor = textColor ?: colors.textPrimary
    val effectiveIconTint = iconTint ?: effectiveColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
            }
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = effectiveIconTint
            )
        }
        Text(label, color = effectiveColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MultiSelectBottomBar(
    onMove: () -> Unit,
    onMerge: () -> Unit = {},
    canMerge: Boolean = false,
    onTag: () -> Unit,
    onShare: () -> Unit,
    onSaveToDevice: () -> Unit = {},
    onBackup: () -> Unit,
    onLock: () -> Unit = {},
    onDelete: () -> Unit
) {
    val colors = LocalAppColors.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface,
        shadowElevation = 12.dp
    ) {
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBarAction(painter = AppIcons.MoveVector, label = "Move", onClick = onMove)
            if (canMerge) {
                BottomBarAction(icon = AppIcons.Merge, label = "Merge", onClick = onMerge, textColor = colors.accent)
            }
            BottomBarAction(painter = AppIcons.TagVector, label = "Tags", onClick = onTag)
            BottomBarAction(painter = AppIcons.ShareVector, label = "Share", onClick = onShare)
            BottomBarAction(painter = AppIcons.SaveDeviceVector, label = "Save", onClick = onSaveToDevice)
            BottomBarAction(painter = AppIcons.BackupDriveVector, label = "Backup", onClick = onBackup)
            BottomBarAction(painter = AppIcons.LockVector, label = "Lock", onClick = onLock)
            BottomBarAction(
                painter = AppIcons.DeleteVector,
                label = "Trash",
                onClick = onDelete,
                textColor = MaterialTheme.colorScheme.error,
                iconTint = MaterialTheme.colorScheme.error
            )
        }
    }
    }
}

@Composable
fun MergeDocsDialog(
    selectedDocs: List<LocalDoc>,
    isMerging: Boolean = false,
    onDismiss: () -> Unit,
    onConfirmMerge: (mergedName: String, deleteOriginals: Boolean, lockMerged: Boolean) -> Unit
) {
    val colors = LocalAppColors.current
    val totalPages = selectedDocs.sumOf { it.pageCount }
    val hasLockedDocs = selectedDocs.any { it.isLocked }
    var mergeName by remember {
        mutableStateOf("Merged_${java.text.SimpleDateFormat("ddMMM_HHmm", java.util.Locale.getDefault()).format(java.util.Date())}")
    }
    var deleteOriginals by remember { mutableStateOf(false) }
    var lockMergedFile by remember { mutableStateOf(hasLockedDocs) }

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = {
            if (!isMerging) onDismiss()
        },
        title = {
            Text(
                text = "Merge ${selectedDocs.size} Files",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Combine ${selectedDocs.size} files into a single document ($totalPages total pages):",
                    color = colors.textSecondary,
                    fontSize = 13.sp
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(selectedDocs) { doc ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.cardBackground,
                            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = doc.displayName,
                                    color = colors.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${doc.pageCount} page(s)",
                                    color = colors.textMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Merged File Name:",
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = mergeName,
                    onValueChange = { mergeName = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isMerging,
                    singleLine = true,
                    placeholder = { Text("Enter merged file name", color = colors.textMuted) }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(enabled = !isMerging) { deleteOriginals = !deleteOriginals }
                ) {
                    Checkbox(
                        checked = deleteOriginals,
                        onCheckedChange = { deleteOriginals = it },
                        enabled = !isMerging,
                        colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Move original files to Trash after merging",
                        color = colors.textSecondary,
                        fontSize = 12.sp
                    )
                }

                if (hasLockedDocs) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(enabled = !isMerging) { lockMergedFile = !lockMergedFile }
                    ) {
                        Checkbox(
                            checked = lockMergedFile,
                            onCheckedChange = { lockMergedFile = it },
                            enabled = !isMerging,
                            colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Lock merged file",
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmMerge(mergeName, deleteOriginals, if (hasLockedDocs) lockMergedFile else false)
                },
                enabled = !isMerging && mergeName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
            ) {
                if (isMerging) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Merging...", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Merge Files", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isMerging
            ) {
                Text("Cancel", color = colors.textMuted)
            }
        }
    )
}

@Composable
fun BulkTagDialog(
    onDismiss: () -> Unit,
    onApplyTags: (List<String>) -> Unit
) {
    val colors = LocalAppColors.current
    var tagInput by remember { mutableStateOf("") }

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add Tags to Selected",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enter comma-separated tags (e.g. Receipt, Tax, 2026):",
                    color = colors.textSecondary,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Tags...", color = colors.textMuted) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tags = tagInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onApplyTags(tags)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
            ) {
                Text("Apply Tags", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMuted)
            }
        }
    )
}

