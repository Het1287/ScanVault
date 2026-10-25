package com.example.ownscan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bottom Sheet / Dialog with exactly 2 options:
 * 1) Save to device as JPG (Pictures/ScanVault)
 * 2) Save to device as PDF (Documents/ScanVault)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToDeviceDialog(
    docTitle: String = "Document",
    isSaving: Boolean = false,
    progressText: String = "",
    onDismiss: () -> Unit,
    onSaveJpg: () -> Unit,
    onSavePdf: () -> Unit
) {
    val colors = LocalAppColors.current

    if (isSaving) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = {},
            title = {
                Text(
                    text = "Saving to device...",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = progressText.ifEmpty { "Writing file to storage..." },
                        color = colors.textPrimary,
                        fontSize = 14.sp
                    )
                }
            },
            confirmButton = {}
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = colors.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Save to device",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Option 1: Save as JPG
                ListItem(
                    headlineContent = {
                        Text(
                            text = "Save to device as JPG",
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "Saves page images into Pictures/ScanVault",
                            color = colors.textMuted,
                            fontSize = 12.sp
                        )
                    },
                    leadingContent = {
                        Text("🖼️", fontSize = 24.sp)
                    },
                    colors = ListItemDefaults.colors(containerColor = colors.cardBackground),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSaveJpg() }
                )

                // Option 2: Save as PDF
                ListItem(
                    headlineContent = {
                        Text(
                            text = "Save to device as PDF",
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "Saves compiled document into Documents/ScanVault",
                            color = colors.textMuted,
                            fontSize = 12.sp
                        )
                    },
                    leadingContent = {
                        Text("📄", fontSize = 24.sp)
                    },
                    colors = ListItemDefaults.colors(containerColor = colors.cardBackground),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSavePdf() }
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel", color = colors.textPrimary)
                }
            }
        }
    }
}
