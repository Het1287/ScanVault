package com.example.ownscan

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ownscan.ui.icons.AppIcons
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class IncomingShareData(
    val uris: List<Uri>,
    val mimeType: String
)

@Composable
fun ShareTargetImportDialog(
    colors: AppColors,
    shareData: IncomingShareData,
    storedDocs: List<LocalDoc>,
    onDismiss: () -> Unit,
    onImportConfirmed: (isNewDoc: Boolean, newDocName: String, existingDoc: LocalDoc?) -> Unit
) {
    val defaultName = remember {
        val stamp = SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())
        "Shared_Doc_$stamp"
    }

    var isNewDoc by remember { mutableStateOf(true) }
    var newDocName by remember { mutableStateOf(defaultName) }
    var selectedExistingDoc by remember { mutableStateOf(storedDocs.firstOrNull()) }

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(AppIcons.Import, fontSize = 22.sp, modifier = Modifier.semantics { contentDescription = "Import" })
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Import Shared Content (${shareData.uris.size})",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "You shared ${shareData.uris.size} item(s) to ScanVault. Choose how to organize them:",
                    color = colors.textMuted,
                    fontSize = 13.sp
                )

                // Mode Selector (New vs Append)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.cardBackground, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val newSelectedModifier = if (isNewDoc) {
                        Modifier
                            .weight(1f)
                            .background(colors.accent, RoundedCornerShape(8.dp))
                            .padding(vertical = 8.dp)
                    } else {
                        Modifier
                            .weight(1f)
                            .clickable { isNewDoc = true }
                            .padding(vertical = 8.dp)
                    }

                    Box(
                        modifier = newSelectedModifier,
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "New Document",
                            color = if (isNewDoc) Color.White else colors.textMuted,
                            fontSize = 12.sp,
                            fontWeight = if (isNewDoc) FontWeight.Bold else FontWeight.Normal
                        )
                    }

                    val appendSelectedModifier = if (!isNewDoc) {
                        Modifier
                            .weight(1f)
                            .background(colors.accent, RoundedCornerShape(8.dp))
                            .padding(vertical = 8.dp)
                    } else {
                        Modifier
                            .weight(1f)
                            .clickable { isNewDoc = false }
                            .padding(vertical = 8.dp)
                    }

                    Box(
                        modifier = appendSelectedModifier,
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Append Existing",
                            color = if (!isNewDoc) Color.White else colors.textMuted,
                            fontSize = 12.sp,
                            fontWeight = if (!isNewDoc) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                if (isNewDoc) {
                    OutlinedTextField(
                        value = newDocName,
                        onValueChange = { newDocName = it },
                        label = { Text("Document Name", color = colors.textMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.cardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    if (storedDocs.isEmpty()) {
                        Text(
                            "No existing documents found to append to.",
                            color = colors.accentAmber,
                            fontSize = 12.sp
                        )
                    } else {
                        Text("Select Document to Append to:", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(storedDocs) { doc ->
                                val isSelected = selectedExistingDoc?.folderName == doc.folderName
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) colors.accent.copy(alpha = 0.15f) else colors.cardBackground,
                                    border = BorderStroke(1.dp, if (isSelected) colors.accent else colors.cardBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedExistingDoc = doc }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (doc.isLocked) {
                                            Icon(
                                                painter = AppIcons.LockVector,
                                                contentDescription = "Locked Document",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Text(
                                                AppIcons.FilePdf,
                                                fontSize = 14.sp,
                                                modifier = Modifier.semantics {
                                                    contentDescription = "Document"
                                                }
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(doc.displayName, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            Text("${doc.pageCount} pages", color = colors.textMuted, fontSize = 11.sp)
                                        }
                                    }
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
                    onImportConfirmed(
                        isNewDoc,
                        newDocName.trim().ifEmpty { defaultName },
                        if (!isNewDoc) selectedExistingDoc else null
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                enabled = isNewDoc || (selectedExistingDoc != null)
            ) {
                Text("Import Files", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMuted)
            }
        }
    )
}

// -------------------------------------------------------------
// IMPORT PROCESSING HELPER
// -------------------------------------------------------------

object ShareImportProcessor {

    suspend fun importSharedContent(
        context: Context,
        uris: List<Uri>,
        isNewDoc: Boolean,
        newDocName: String,
        existingDoc: LocalDoc?
    ): File? = withContext(Dispatchers.IO) {
        val targetDir: File = if (isNewDoc || existingDoc == null) {
            val stamp = System.currentTimeMillis()
            val safeName = sanitizeName(newDocName)
            val baseDir = getPrivateDocsDir(context)
            val dir = File(baseDir, "${stamp}__$safeName")
            dir.mkdirs()
            DocMeta.writeToDir(dir, DocMeta())
            dir
        } else {
            existingDoc.directory
        }

        appendFilesToDocFolder(context, targetDir, uris)
        thumbnailMemoryCache.evictAll()
        targetDir
    }
}
