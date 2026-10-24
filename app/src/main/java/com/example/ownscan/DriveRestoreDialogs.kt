package com.example.ownscan

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import java.io.File
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

data class ConflictDialogState(
    val docName: String,
    val localTime: Long,
    val cloudTime: Long,
    val isRestoreAll: Boolean,
    val onResolution: (ConflictResolution, Boolean) -> Unit
)

@Composable
fun RestoreConflictDialog(
    state: ConflictDialogState,
    onDismiss: () -> Unit
) {
    val colors = LocalAppColors.current
    var applyToAll by remember { mutableStateOf(false) }

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = {
            state.onResolution(ConflictResolution.SKIP, false)
            onDismiss()
        },
        title = {
            Text(
                text = "Document Conflict",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "'${state.docName}' already exists on this device.",
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "• Local modified: ${formatDate(state.localTime)}\n• Cloud backup: ${formatDate(state.cloudTime)}",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                if (state.isRestoreAll) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { applyToAll = !applyToAll }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = applyToAll,
                            onCheckedChange = { applyToAll = it },
                            colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Apply to all conflicts",
                            color = colors.textPrimary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        state.onResolution(ConflictResolution.KEEP_BOTH, applyToAll)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Keep Both (Restore as Copy)", color = Color.White)
                }

                OutlinedButton(
                    onClick = {
                        state.onResolution(ConflictResolution.NEWEST_WINS, applyToAll)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder)
                    )
                ) {
                    Text(if (state.isRestoreAll) "Newest Wins" else "Replace Existing", color = colors.textPrimary)
                }

                TextButton(
                    onClick = {
                        state.onResolution(ConflictResolution.SKIP, applyToAll)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Skip", color = colors.textMuted)
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
fun AuthExpiredPromptDialog(
    onSignInAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppColors.current

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Session Expired",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Text(
                text = "Your Google Drive session has expired. Please sign in again to continue syncing your documents.",
                color = colors.textSecondary,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onSignInAgain()
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
            ) {
                Text("Sign in again", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMuted)
            }
        }
    )
}

@Composable
fun CloudBackupsDialog(
    activity: Activity,
    account: GoogleSignInAccount,
    onDismiss: () -> Unit,
    onRefreshDocs: () -> Unit,
    onTriggerAuthExpired: () -> Unit
) {
    val colors = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cloudItems by remember { mutableStateOf<List<CloudDocBackupItem>>(emptyList()) }
    var isRestoring by remember { mutableStateOf(false) }
    var restoringStatus by remember { mutableStateOf("") }

    var activeConflictState by remember { mutableStateOf<ConflictDialogState?>(null) }
    var lockedRestoreItem by remember { mutableStateOf<CloudDocBackupItem?>(null) }
    var lockedRestoreError by remember { mutableStateOf<String?>(null) }
    var isLockedRestoring by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<CloudDocBackupItem?>(null) }

    fun loadBackups() {
        isLoading = true
        errorMessage = null
        coroutineScope.launch {
            when (val res = DriveBackupManager.listCloudBackups(activity, account)) {
                is DriveResult.Success -> {
                    cloudItems = res.data
                    isLoading = false
                }
                is DriveResult.Failure -> {
                    isLoading = false
                    errorMessage = res.displayMessage
                    if (res is DriveResult.Failure.AuthExpired) {
                        onTriggerAuthExpired()
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadBackups()
    }

    if (activeConflictState != null) {
        RestoreConflictDialog(
            state = activeConflictState!!,
            onDismiss = { activeConflictState = null }
        )
    }

    if (itemToDelete != null) {
        val toDel = itemToDelete!!
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Cloud Backup", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to delete '${toDel.displayName}' from Google Drive?",
                    color = colors.textMuted
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val item = toDel
                        itemToDelete = null
                        coroutineScope.launch {
                            when (val res = DriveBackupManager.deleteCloudItem(activity, account, item)) {
                                is DriveResult.Success -> {
                                    Toast.makeText(activity, "'${item.displayName}' deleted from Drive", Toast.LENGTH_SHORT).show()
                                    loadBackups()
                                    onRefreshDocs()
                                }
                                is DriveResult.Failure -> {
                                    Toast.makeText(activity, "Failed to delete: ${res.displayMessage}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel", color = colors.textMuted)
                }
            }
        )
    }

    Dialog(
        onDismissRequest = { if (!isRestoring) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            color = colors.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // HEADER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Cloud Backups",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = account.email ?: "Google Drive",
                            color = colors.textMuted,
                            fontSize = 12.sp
                        )
                    }

                    TextButton(
                        onClick = onDismiss,
                        enabled = !isRestoring
                    ) {
                        Text("Close", color = colors.accent, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isRestoring) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = colors.cardSubtle),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = colors.accent,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = restoringStatus,
                                color = colors.textPrimary,
                                fontSize = 13.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // CONTENT AREA
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                } else if (errorMessage != null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = errorMessage ?: "Failed to load backups",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { loadBackups() },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                            ) {
                                Text("Retry", color = Color.White)
                            }
                        }
                    }
                } else if (cloudItems.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No documents found in cloud backup.",
                            color = colors.textMuted,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${cloudItems.size} document(s) in Drive",
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isRestoring = true
                                    restoringStatus = "Restoring all documents..."
                                    var remembered: Pair<ConflictResolution, Boolean>? = null

                                    val res = DriveBackupManager.restoreData(activity, account) { item, localDoc ->
                                        if (remembered != null && remembered!!.second) {
                                            remembered!!
                                        } else {
                                            val deferred = CompletableDeferred<Pair<ConflictResolution, Boolean>>()
                                            activeConflictState = ConflictDialogState(
                                                docName = item.displayName,
                                                localTime = localDoc.imageFiles.maxOfOrNull { it.lastModified() }
                                                    ?: localDoc.directory.lastModified(),
                                                cloudTime = item.modifiedTime,
                                                isRestoreAll = true,
                                                onResolution = { resolution, applyToAll ->
                                                    deferred.complete(Pair(resolution, applyToAll))
                                                }
                                            )
                                            val choice = deferred.await()
                                            if (choice.second) {
                                                remembered = choice
                                            }
                                            choice
                                        }
                                    }

                                    isRestoring = false
                                    when (res) {
                                        is DriveResult.Success -> {
                                            val sum = res.data
                                            DocumentRepository.rebuildRoomFromMetaJson(activity)
                                            val lockedMsg = if (sum.lockedSkippedCount > 0) " (${sum.lockedSkippedCount} locked documents must be restored individually with a password)" else ""
                                            Toast.makeText(
                                                activity,
                                                "Restore complete: ${sum.restoredCount} restored, ${sum.skippedCount} skipped$lockedMsg",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            onRefreshDocs()
                                            onDismiss()
                                        }
                                        is DriveResult.Failure -> {
                                            Toast.makeText(activity, "Restore failed: ${res.displayMessage}", Toast.LENGTH_LONG).show()
                                            if (res is DriveResult.Failure.AuthExpired) {
                                                onTriggerAuthExpired()
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isRestoring,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                        ) {
                            Text("Restore All", color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(cloudItems, key = { it.fileId }) { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                                border = CardDefaults.outlinedCardBorder().copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = item.displayName,
                                                color = colors.textPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            )
                                            if (item.isLockedBackup) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("🔒", fontSize = 13.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (!item.parentFolder.isNullOrBlank()) {
                                                Text(
                                                    text = "📁 ${item.parentFolder}  •  ",
                                                    color = colors.accent,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Text(
                                                text = "${formatDate(item.modifiedTime)} • ${formatSize(item.byteSize)}",
                                                color = colors.textMuted,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { itemToDelete = item },
                                            enabled = !isRestoring
                                        ) {
                                            Text("🗑️", fontSize = 14.sp)
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        OutlinedButton(
                                            onClick = {
                                                if (item.isLockedBackup) {
                                                    Toast.makeText(
                                                        activity,
                                                        "Google Drive cloud backup does not support locked files. Use Local Backup to restore locked files.",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } else {
                                                    coroutineScope.launch {
                                                        isRestoring = true
                                                        restoringStatus = "Restoring '${item.displayName}'..."
                                                        val res = DriveBackupManager.restoreSingleDoc(activity, account, item) { cItem, localDoc ->
                                                            val deferred = CompletableDeferred<ConflictResolution>()
                                                            activeConflictState = ConflictDialogState(
                                                                docName = cItem.displayName,
                                                                localTime = localDoc.imageFiles.maxOfOrNull { it.lastModified() }
                                                                    ?: localDoc.directory.lastModified(),
                                                                cloudTime = cItem.modifiedTime,
                                                                isRestoreAll = false,
                                                                onResolution = { resolution, _ ->
                                                                    deferred.complete(resolution)
                                                                }
                                                            )
                                                            deferred.await()
                                                        }

                                                        isRestoring = false
                                                        when (res) {
                                                            is DriveResult.Success -> {
                                                                DocumentRepository.rebuildRoomFromMetaJson(activity)
                                                                when (val docRes = res.data) {
                                                                    is RestoreDocResult.Restored -> {
                                                                        val msg = if (docRes.isCopy) {
                                                                            "'${item.displayName}' restored as copy!"
                                                                        } else {
                                                                            "'${item.displayName}' restored successfully!"
                                                                        }
                                                                        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                                                                        onRefreshDocs()
                                                                    }
                                                                    is RestoreDocResult.Skipped -> {
                                                                        Toast.makeText(activity, "Restore skipped.", Toast.LENGTH_SHORT).show()
                                                                    }
                                                                }
                                                            }
                                                            is DriveResult.Failure -> {
                                                                Toast.makeText(activity, "Restore failed: ${res.displayMessage}", Toast.LENGTH_LONG).show()
                                                                if (res is DriveResult.Failure.AuthExpired) {
                                                                    onTriggerAuthExpired()
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !isRestoring,
                                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                                brush = androidx.compose.ui.graphics.SolidColor(colors.accent)
                                            )
                                        ) {
                                            Text("Restore", color = colors.accent, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// LOCKED DOCUMENT CLOUD BACKUP WARNING DIALOG
// -------------------------------------------------------------

@Composable
fun LockedDocCloudBackupDialog(
    docName: String? = null,
    lockedCount: Int = 1,
    unlockedCount: Int = 0,
    isAutoSyncPrompt: Boolean = false,
    onDismiss: () -> Unit,
    onProceedWithUnlocked: (() -> Unit)? = null,
    onOpenLocalBackup: () -> Unit
) {
    val colors = LocalAppColors.current
    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔒", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Cloud Backup Notice",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Google Drive cloud backup does not support locked files backup. If you want locked file backup, please use Local Backup or remove the lock from your file.",
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                if (isAutoSyncPrompt) {
                    Text(
                        text = "Automatic cloud sync will only upload your unlocked documents ($unlockedCount). Locked documents will remain safely stored on this device only.",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                } else if (unlockedCount > 0) {
                    Text(
                        text = "You can proceed to back up the $unlockedCount unlocked document(s), or use Local Backup to safely back up everything including locked files.",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        },
        confirmButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isAutoSyncPrompt && onProceedWithUnlocked != null) {
                    Button(
                        onClick = {
                            onDismiss()
                            onProceedWithUnlocked()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                    ) {
                        Text("Enable for Unlocked", color = Color.White)
                    }
                } else if (unlockedCount > 0 && onProceedWithUnlocked != null) {
                    Button(
                        onClick = {
                            onDismiss()
                            onProceedWithUnlocked()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                    ) {
                        Text("Back Up Unlocked ($unlockedCount)", color = Color.White)
                    }
                }
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onOpenLocalBackup()
                    },
                    border = BorderStroke(1.dp, colors.cardBorder)
                ) {
                    Text("Use Local Backup", color = colors.textPrimary)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMuted)
            }
        }
    )
}
