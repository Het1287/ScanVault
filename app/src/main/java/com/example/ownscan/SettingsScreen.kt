package com.example.ownscan

import java.io.File

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// -------------------------------------------------------------
// SCREEN 3: SETTINGS (DRIVE STATS, AUTO-SYNC, DELETE BACKUP)
// -------------------------------------------------------------

@Composable
fun SettingsScreenDesign(
    activity: Activity,
    storedDocs: List<LocalDoc>,
    isDarkTheme: Boolean,
    selectedAccentHex: Long,
    isFlagSecureEnabled: Boolean = false,
    onToggleFlagSecure: (Boolean) -> Unit = {},
    isDynamicColorEnabled: Boolean = false,
    onToggleDynamicColor: (Boolean) -> Unit = {},
    onTriggerBackupNow: () -> Unit,
    isBackupNowRunning: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onSelectAccent: (Long) -> Unit,
    onRefresh: () -> Unit,
    onOpenTrash: () -> Unit
) {
    val colors = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { activity.getSharedPreferences("scanvault_settings", Context.MODE_PRIVATE) }

    var googleAccount by remember { mutableStateOf(DriveBackupManager.getSignedInAccount(activity)) }
    var cloudBackupInfo by remember { mutableStateOf<CloudBackupInfo?>(null) }
    var autoSyncEnabled by remember { mutableStateOf(prefs.getBoolean("auto_sync", false)) }
    var showDeleteBackupWarning by remember { mutableStateOf(false) }
    var showCloudBackupsDialog by remember { mutableStateOf(false) }
    var showAuthExpiredPrompt by remember { mutableStateOf(false) }
    var showBackupNowLockedWarning by remember { mutableStateOf(false) }
    var showAutoSyncLockedWarning by remember { mutableStateOf(false) }
    var appLockEnabled by remember { mutableStateOf(AppLockManager.isAppLockEnabled(activity)) }
    var inactivityTimeoutSec by remember { mutableIntStateOf(AppLockManager.getInactivityTimeoutSeconds(activity)) }
    var showDeviceLockRequiredDialog by remember { mutableStateOf(false) }

    // Phase 10g: SAF Auto-Backup States
    var safStatus by remember { mutableStateOf(SafAutoBackupEngine.getBackupStatus(activity)) }
    var showSafPasswordDialog by remember { mutableStateOf(false) }
    var safBackupPasswordInput by remember { mutableStateOf("") }
    var isSafProcessing by remember { mutableStateOf(false) }

    val safFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            SafAutoBackupEngine.setBackupConfig(activity, enabled = true, treeUri = treeUri)
            safStatus = SafAutoBackupEngine.getBackupStatus(activity)
            Toast.makeText(activity, "Target backup folder set", Toast.LENGTH_SHORT).show()
        }
    }

    // Phase 5: Local Encrypted Backup States
    var showExportEncryptedBackupDialog by remember { mutableStateOf(false) }
    var pendingEncryptedBackupPassword by remember { mutableStateOf("") }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var activeConflictState by remember { mutableStateOf<ConflictDialogState?>(null) }
    var isProcessingEncryptedBackup by remember { mutableStateOf(false) }
    var encryptedBackupStatusMsg by remember { mutableStateOf<String?>(null) }

    val createEncryptedBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { targetUri ->
        if (targetUri != null && pendingEncryptedBackupPassword.isNotBlank()) {
            isProcessingEncryptedBackup = true
            encryptedBackupStatusMsg = "Encrypting backup with AES-256-GCM..."
            coroutineScope.launch {
                try {
                    val success = LocalEncryptedBackupManager.createEncryptedBackup(
                        context = activity,
                        docFolders = storedDocs.map { it.directory },
                        password = pendingEncryptedBackupPassword,
                        destinationUri = targetUri
                    )
                    if (success) {
                        Toast.makeText(activity, "Encrypted backup (.zip.enc) saved successfully!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(activity, "Failed to create encrypted backup", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EncryptedBackup", "Failed to create backup: ${e.message}", e)
                    Toast.makeText(activity, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    pendingEncryptedBackupPassword = ""
                    isProcessingEncryptedBackup = false
                    encryptedBackupStatusMsg = null
                }
            }
        }
    }

    val restoreEncryptedBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri != null) {
            pendingRestoreUri = sourceUri
            showRestorePasswordDialog = true
        }
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.result
            googleAccount = account
            prefs.edit()
                .putBoolean("is_first_launch", false)
                .putBoolean("has_seen_onboarding", true)
                .apply()
            Toast.makeText(activity, "Signed in as ${account.email}", Toast.LENGTH_SHORT).show()
            coroutineScope.launch {
                when (val res = DriveBackupManager.checkExistingBackupInfo(activity, account)) {
                    is DriveResult.Success -> {
                        cloudBackupInfo = res.data
                    }
                    is DriveResult.Failure -> {
                        cloudBackupInfo = null
                        if (res is DriveResult.Failure.AuthExpired) {
                            showAuthExpiredPrompt = true
                        }
                    }
                }
                onRefresh()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Sign-in failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(googleAccount, isBackupNowRunning) {
        googleAccount?.let { acc ->
            when (val res = DriveBackupManager.checkExistingBackupInfo(activity, acc)) {
                is DriveResult.Success -> {
                    cloudBackupInfo = res.data
                }
                is DriveResult.Failure -> {
                    if (res is DriveResult.Failure.AuthExpired) {
                        showAuthExpiredPrompt = true
                    }
                }
            }
        }
    }

    val localTotalSize = remember(storedDocs) {
        storedDocs.sumOf { doc -> doc.imageFiles.sumOf { it.length() } }
    }

    if (showAuthExpiredPrompt) {
        AuthExpiredPromptDialog(
            onSignInAgain = {
                val client = DriveBackupManager.getGoogleSignInClient(activity)
                signInLauncher.launch(client.signInIntent)
            },
            onDismiss = { showAuthExpiredPrompt = false }
        )
    }

    if (showDeviceLockRequiredDialog) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showDeviceLockRequiredDialog = false },
            title = { Text("Device Lock Required", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "ScanVault uses your device screen lock (PIN, Pattern, Password, or Biometric) to protect the app.\n\nPlease set up a screen lock in your device Settings before enabling App Lock.",
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

    if (showCloudBackupsDialog && googleAccount != null) {
        CloudBackupsDialog(
            activity = activity,
            account = googleAccount!!,
            onDismiss = { showCloudBackupsDialog = false },
            onRefreshDocs = {
                onRefresh()
                coroutineScope.launch {
                    val res = DriveBackupManager.checkExistingBackupInfo(activity, googleAccount!!)
                    if (res is DriveResult.Success) {
                        cloudBackupInfo = res.data
                    }
                }
            },
            onTriggerAuthExpired = { showAuthExpiredPrompt = true }
        )
    }

    val lockedDocs = remember(storedDocs) { storedDocs.filter { it.isLocked || File(it.directory, ".dek.enc").exists() } }
    val unlockedDocs = remember(storedDocs) { storedDocs.filter { !it.isLocked && !File(it.directory, ".dek.enc").exists() } }

    if (showBackupNowLockedWarning) {
        LockedDocCloudBackupDialog(
            docName = if (lockedDocs.size == 1) lockedDocs.first().displayName else null,
            lockedCount = lockedDocs.size,
            unlockedCount = unlockedDocs.size,
            onDismiss = { showBackupNowLockedWarning = false },
            onProceedWithUnlocked = if (unlockedDocs.isNotEmpty()) {
                {
                    showBackupNowLockedWarning = false
                    onTriggerBackupNow()
                }
            } else null,
            onOpenLocalBackup = {
                showBackupNowLockedWarning = false
            }
        )
    }

    if (showAutoSyncLockedWarning) {
        LockedDocCloudBackupDialog(
            docName = if (lockedDocs.size == 1) lockedDocs.first().displayName else null,
            lockedCount = lockedDocs.size,
            unlockedCount = unlockedDocs.size,
            isAutoSyncPrompt = true,
            onDismiss = { showAutoSyncLockedWarning = false },
            onProceedWithUnlocked = {
                showAutoSyncLockedWarning = false
                autoSyncEnabled = true
                prefs.edit().putBoolean("auto_sync", true).apply()
            },
            onOpenLocalBackup = {
                showAutoSyncLockedWarning = false
            }
        )
    }

    if (showDeleteBackupWarning) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showDeleteBackupWarning = false },
            title = {
                Text(
                    text = "⚠️ Delete Cloud Backup?",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Your Google Drive backup will be permanently deleted from the cloud. This action cannot be undone. Do you want to confirm?",
                    color = colors.textPrimary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteBackupWarning = false
                        coroutineScope.launch {
                            when (val res = DriveBackupManager.deleteCloudBackup(activity, googleAccount!!)) {
                                is DriveResult.Success -> {
                                    cloudBackupInfo = null
                                    onRefresh()
                                    Toast.makeText(activity, "Cloud backup deleted successfully", Toast.LENGTH_SHORT).show()
                                }
                                is DriveResult.Failure -> {
                                    Toast.makeText(activity, "Failed to delete cloud backup: ${res.displayMessage}", Toast.LENGTH_LONG).show()
                                    if (res is DriveResult.Failure.AuthExpired) {
                                        showAuthExpiredPrompt = true
                                    }
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Confirm Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteBackupWarning = false }) {
                    Text("Cancel", color = colors.textMuted)
                }
            }
        )
    }

    if (showExportEncryptedBackupDialog) {
        ExportEncryptedBackupDialog(
            colors = colors,
            docCount = storedDocs.size,
            onDismiss = { showExportEncryptedBackupDialog = false },
            onPasswordConfirmed = { password ->
                pendingEncryptedBackupPassword = password
                showExportEncryptedBackupDialog = false
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                createEncryptedBackupLauncher.launch("ScanVault_Backup_$timeStamp.zip.enc")
            }
        )
    }

    if (showSafPasswordDialog) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showSafPasswordDialog = false },
            title = { Text("SAF Backup Password", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a password (min 8 chars) to encrypt automatic scheduled backups with AES-256-GCM:", color = colors.textMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = safBackupPasswordInput,
                        onValueChange = { safBackupPasswordInput = it },
                        singleLine = true,
                        label = { Text("Password", color = colors.textMuted) },
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
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (safBackupPasswordInput.length >= 8) {
                            SafAutoBackupEngine.setBackupConfig(activity, enabled = safStatus.isEnabled, password = safBackupPasswordInput)
                            safStatus = SafAutoBackupEngine.getBackupStatus(activity)
                            showSafPasswordDialog = false
                            Toast.makeText(activity, "Backup password saved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, "Password must be at least 8 characters", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSafPasswordDialog = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

    if (showRestorePasswordDialog && pendingRestoreUri != null) {
        RestoreEncryptedBackupPasswordDialog(
            colors = colors,
            onDismiss = {
                showRestorePasswordDialog = false
                pendingRestoreUri = null
            },
            onPasswordEntered = { password ->
                showRestorePasswordDialog = false
                val uri = pendingRestoreUri ?: return@RestoreEncryptedBackupPasswordDialog
                pendingRestoreUri = null

                isProcessingEncryptedBackup = true
                encryptedBackupStatusMsg = "Decrypting & verifying backup..."

                coroutineScope.launch {
                    try {
                        var rememberedChoice: Pair<ConflictResolution, Boolean>? = null

                        val result = LocalEncryptedBackupManager.restoreEncryptedBackup(
                            context = activity,
                            sourceUri = uri,
                            password = password,
                            conflictResolver = { docName, localTime, backupTime ->
                                if (rememberedChoice != null && rememberedChoice!!.second) {
                                    rememberedChoice!!.first
                                } else {
                                    val deferred = CompletableDeferred<Pair<ConflictResolution, Boolean>>()
                                    activeConflictState = ConflictDialogState(
                                        docName = docName,
                                        localTime = localTime,
                                        cloudTime = backupTime,
                                        isRestoreAll = true,
                                        onResolution = { resolution, applyToAll ->
                                            deferred.complete(Pair(resolution, applyToAll))
                                        }
                                    )
                                    val choice = deferred.await()
                                    activeConflictState = null
                                    if (choice.second) {
                                        rememberedChoice = choice
                                    }
                                    choice.first
                                }
                            }
                        )

                        when (result) {
                            is EncryptedRestoreResult.Success -> {
                                onRefresh()
                                val msg = "Restored ${result.restoredCount} document(s)" +
                                        (if (result.skippedCount > 0) " (${result.skippedCount} skipped)" else "") +
                                        (if (result.badEntriesCount > 0) " [${result.badEntriesCount} bad zip-slip entries blocked]" else "")
                                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                            }
                            is EncryptedRestoreResult.IncorrectPasswordOrCorrupted -> {
                                Toast.makeText(activity, "Incorrect password or corrupted backup file", Toast.LENGTH_LONG).show()
                            }
                            is EncryptedRestoreResult.InvalidFileFormat -> {
                                Toast.makeText(activity, "Invalid file format. Must be an ScanVault .zip.enc file", Toast.LENGTH_LONG).show()
                            }
                            is EncryptedRestoreResult.Error -> {
                                Toast.makeText(activity, "Restore failed: ${result.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("EncryptedRestore", "Error during restore: ${e.message}", e)
                        Toast.makeText(activity, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        isProcessingEncryptedBackup = false
                        encryptedBackupStatusMsg = null
                        activeConflictState = null
                    }
                }
            }
        )
    }

    if (activeConflictState != null) {
        RestoreConflictDialog(
            state = activeConflictState!!,
            onDismiss = {
                activeConflictState?.onResolution?.invoke(ConflictResolution.SKIP, false)
                activeConflictState = null
            }
        )
    }

    if (isProcessingEncryptedBackup && activeConflictState == null) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = {},
            title = { Text("Processing Encrypted Backup", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(encryptedBackupStatusMsg ?: "Please wait...", color = colors.textPrimary, fontSize = 14.sp)
                }
            },
            confirmButton = {}
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Settings",
            color = colors.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Cloud backup, auto-sync, and theme preferences",
            color = colors.textMuted,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // GOOGLE DRIVE SYNC CARD
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("☁️", fontSize = 26.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Google Drive Sync",
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = googleAccount?.email ?: "Connect for hidden cloud backup",
                                        color = colors.textMuted,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            if (isBackupNowRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = colors.accent,
                                    strokeWidth = 2.dp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        if (googleAccount == null) {
                            Button(
                                onClick = {
                                    val client = DriveBackupManager.getGoogleSignInClient(activity)
                                    signInLauncher.launch(client.signInIntent)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                            ) {
                                Text("Connect Google Account", color = Color.White)
                            }
                        } else {
                            if (cloudBackupInfo != null) {
                                Text(
                                    text = "Last Cloud Backup: ${formatDate(cloudBackupInfo!!.timestamp)}",
                                    color = colors.accentGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (lockedDocs.isNotEmpty()) {
                                            showBackupNowLockedWarning = true
                                        } else {
                                            onTriggerBackupNow()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isBackupNowRunning,
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                                ) {
                                    Text("Backup Now", color = Color.White)
                                }

                                OutlinedButton(
                                    onClick = { showCloudBackupsDialog = true },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isBackupNowRunning,
                                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                                ) {
                                    Text("Restore", color = colors.textPrimary)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = { showDeleteBackupWarning = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isBackupNowRunning,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error.copy(alpha = 0.5f)))
                            ) {
                                Text("🗑️ Delete Cloud Backup", color = MaterialTheme.colorScheme.error)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = {
                                    DriveBackupManager.getGoogleSignInClient(activity).signOut().addOnCompleteListener {
                                        googleAccount?.email?.let { email ->
                                            DriveBackupManager.clearFingerprints(activity, email)
                                        }
                                        DriveBackupManager.clearLocalCloudCache(activity)
                                        googleAccount = null
                                        cloudBackupInfo = null
                                        prefs.edit()
                                            .putBoolean("is_first_launch", true)
                                            .putBoolean("has_seen_onboarding", false)
                                            .putBoolean("auto_sync", false)
                                            .apply()
                                        onRefresh()
                                        Toast.makeText(activity, "Signed out from Google Drive", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textMuted),
                                border = BorderStroke(1.dp, colors.cardBorder)
                            ) {
                                Text("Sign Out Account", color = colors.textMuted)
                            }
                        }
                    }
                }
            }

            // AUTO SYNC TOGGLE
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic Cloud Sync", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Auto-upload to Google Drive when you scan, edit, or scroll down and hold",
                                color = colors.textMuted,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = autoSyncEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    if (googleAccount == null) {
                                        Toast.makeText(activity, "Please connect Google Account above first", Toast.LENGTH_SHORT).show()
                                        return@Switch
                                    }
                                    if (lockedDocs.isNotEmpty()) {
                                        showAutoSyncLockedWarning = true
                                    } else {
                                        autoSyncEnabled = true
                                        prefs.edit().putBoolean("auto_sync", true).apply()
                                    }
                                } else {
                                    autoSyncEnabled = false
                                    prefs.edit().putBoolean("auto_sync", false).apply()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colors.accent
                            )
                        )
                    }
                }
            }

            // APPEARANCE: THEME MODE & CUSTOM ACCENT COLORS
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Appearance & Theme", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("High contrast dark/light mode with customized accents", color = colors.textMuted, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onToggleTheme(true) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDarkTheme) colors.accent else colors.cardSubtle
                                )
                            ) {
                                Text("🌙 Dark", color = if (isDarkTheme) Color.White else colors.textPrimary, fontSize = 13.sp)
                            }

                            Button(
                                onClick = { onToggleTheme(false) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!isDarkTheme) colors.accent else colors.cardSubtle
                                )
                            ) {
                                Text("☀️ Light", color = if (!isDarkTheme) Color.White else colors.textPrimary, fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Custom Accent Color", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(PresetAccents) { preset ->
                                val isSelected = preset.colorLong == selectedAccentHex
                                val swatchColor = Color(preset.colorLong)

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { onSelectAccent(preset.colorLong) }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor)
                                            .border(
                                                width = if (isSelected) 3.dp else 0.dp,
                                                color = if (isSelected) colors.textPrimary else Color.Transparent,
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = preset.name,
                                        color = if (isSelected) colors.textPrimary else colors.textMuted,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Material You Dynamic Color", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("Derive accent & surfaces from wallpaper palette", color = colors.textMuted, fontSize = 11.sp)
                                }
                                Switch(
                                    checked = isDynamicColorEnabled,
                                    onCheckedChange = onToggleDynamicColor,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = colors.accent
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // STORAGE INFO CARD
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Storage & Cloud Info", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Local Documents", color = colors.textMuted, fontSize = 13.sp)
                            Text("${storedDocs.size}", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Local Pages", color = colors.textMuted, fontSize = 13.sp)
                            Text("${storedDocs.sumOf { it.pageCount }}", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Local Storage Used", color = colors.textMuted, fontSize = 13.sp)
                            Text(formatSize(localTotalSize), color = colors.accentGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Documents in Google Drive", color = colors.textMuted, fontSize = 13.sp)
                            Text(
                                text = if (cloudBackupInfo != null) "${cloudBackupInfo!!.docCount} doc(s)" else "No backup yet",
                                color = if (cloudBackupInfo != null) colors.accent else colors.textMuted,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Cloud Size Taken in Drive", color = colors.textMuted, fontSize = 13.sp)
                            Text(
                                text = if (cloudBackupInfo != null) formatSize(cloudBackupInfo!!.byteSize) else "0 KB",
                                color = if (cloudBackupInfo != null) colors.accentGreen else colors.textMuted,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // LOCAL ENCRYPTED BACKUP CARD (AES-256-GCM + PBKDF2)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔐", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Encrypted Local Backup", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("AES-256-GCM password-protected archive (.zip.enc)", color = colors.textMuted, fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Save all documents into a single password-encrypted file on your device, SD card, or flash drive. If you lose your password, the backup cannot be recovered.",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { showExportEncryptedBackupDialog = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                enabled = storedDocs.isNotEmpty() && !isProcessingEncryptedBackup
                            ) {
                                Text("Export .zip.enc", color = Color.White, fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    restoreEncryptedBackupLauncher.launch(arrayOf("*/*"))
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isProcessingEncryptedBackup,
                                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                            ) {
                                Text("Restore Backup", color = colors.textPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // PHASE 10g: SCHEDULED LOCAL AUTO-BACKUP (SAF)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📁", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Scheduled Local Auto-Backup (SAF)", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("Automated AES-256 backup to selected folder/SD card", color = colors.textMuted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = safStatus.isEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        if (safStatus.folderUri == null) {
                                            safFolderPickerLauncher.launch(null)
                                        } else if (!safStatus.hasPassword) {
                                            showSafPasswordDialog = true
                                        } else {
                                            SafAutoBackupEngine.setBackupConfig(activity, enabled = true)
                                            safStatus = SafAutoBackupEngine.getBackupStatus(activity)
                                        }
                                    } else {
                                        SafAutoBackupEngine.setBackupConfig(activity, enabled = false)
                                        safStatus = SafAutoBackupEngine.getBackupStatus(activity)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = colors.accent
                                )
                            )
                        }

                        if (safStatus.isEnabled) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                            Spacer(modifier = Modifier.height(14.dp))

                            // Folder row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Backup Folder", color = colors.textMuted, fontSize = 11.sp)
                                    Text(
                                        text = safStatus.folderName ?: "None selected",
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(onClick = { safFolderPickerLauncher.launch(null) }) {
                                    Text("Change", color = colors.accent, fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Frequency selection
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Schedule Frequency", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("DAILY" to "Daily", "WEEKLY" to "Weekly").forEach { (freqKey, label) ->
                                        val isSel = safStatus.frequency.equals(freqKey, ignoreCase = true)
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) colors.accent else colors.cardSubtle,
                                            modifier = Modifier.clickable {
                                                SafAutoBackupEngine.setBackupConfig(activity, enabled = true, frequency = freqKey)
                                                safStatus = SafAutoBackupEngine.getBackupStatus(activity)
                                            }
                                        ) {
                                            Text(
                                                text = label,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                color = if (isSel) Color.White else colors.textPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Password status & set password button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Backup Password", color = colors.textMuted, fontSize = 11.sp)
                                    Text(if (safStatus.hasPassword) "•••••••• (Protected)" else "Not set", color = colors.textPrimary, fontSize = 13.sp)
                                }
                                TextButton(onClick = { showSafPasswordDialog = true }) {
                                    Text(if (safStatus.hasPassword) "Change Password" else "Set Password", color = colors.accent, fontSize = 12.sp)
                                }
                            }

                            if (safStatus.lastBackupStatus != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Last: ${safStatus.lastBackupStatus} (${formatDate(safStatus.lastBackupTimestamp)})",
                                    color = if (safStatus.lastBackupStatus?.startsWith("Success") == true) colors.accentGreen else MaterialTheme.colorScheme.error,
                                    fontSize = 11.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = {
                                    if (safStatus.folderUri == null) {
                                        safFolderPickerLauncher.launch(null)
                                    } else if (!safStatus.hasPassword) {
                                        showSafPasswordDialog = true
                                    } else {
                                        isSafProcessing = true
                                        coroutineScope.launch {
                                            val (ok, msg) = SafAutoBackupEngine.performBackup(activity)
                                            isSafProcessing = false
                                            safStatus = SafAutoBackupEngine.getBackupStatus(activity)
                                            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                enabled = !isSafProcessing
                            ) {
                                if (isSafProcessing) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Backing up...", color = Color.White, fontSize = 12.sp)
                                } else {
                                    Text("Run Backup Now", color = Color.White, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // REAL DOCUMENT LOCKING & APP SECURITY CARD
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🛡️", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Security & Document Protection", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("Hardware Keystore AES-256-GCM encryption & Biometrics", color = colors.textMuted, fontSize = 12.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Require Biometrics on Launch", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Lock the entire app whenever opened or launched", color = colors.textMuted, fontSize = 11.sp)
                            }
                            Switch(
                                checked = appLockEnabled,
                                onCheckedChange = { targetState ->
                                    if (targetState) {
                                        if (!BiometricAuthManager.isAuthAvailable(activity)) {
                                            showDeviceLockRequiredDialog = true
                                        } else {
                                            BiometricAuthManager.authenticate(
                                                activity = activity as FragmentActivity,
                                                title = "Enable App Lock",
                                                subtitle = "Verify biometric or device credential to require lock on launch",
                                                onSuccess = {
                                                    appLockEnabled = true
                                                    AppLockManager.setAppLockEnabled(activity, true)
                                                    Toast.makeText(activity, "App lock enabled on launch", Toast.LENGTH_SHORT).show()
                                                },
                                                onError = { err ->
                                                    Toast.makeText(activity, "Authentication failed: $err", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    } else {
                                        BiometricAuthManager.authenticate(
                                            activity = activity as FragmentActivity,
                                            title = "Disable App Lock",
                                            subtitle = "Verify biometric or device credential to turn off launch lock",
                                            onSuccess = {
                                                appLockEnabled = false
                                                AppLockManager.setAppLockEnabled(activity, false)
                                                Toast.makeText(activity, "App lock disabled", Toast.LENGTH_SHORT).show()
                                            },
                                            onError = { err ->
                                                Toast.makeText(activity, "Authentication failed: $err", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = colors.accent,
                                    uncheckedThumbColor = colors.textMuted,
                                    uncheckedTrackColor = colors.cardSubtle,
                                    uncheckedBorderColor = colors.cardBorder
                                )
                            )
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Inactivity Timeout Relock", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Auto-relocks documents & wipes cache after inactivity", color = colors.textMuted, fontSize = 11.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(60 to "1m", 120 to "2m", 300 to "5m").forEach { (sec, label) ->
                                    val isCur = inactivityTimeoutSec == sec
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isCur) colors.accent else colors.cardSubtle,
                                        modifier = Modifier.clickable {
                                            inactivityTimeoutSec = sec
                                            AppLockManager.setInactivityTimeoutSeconds(activity, sec)
                                        }
                                    ) {
                                        Text(
                                            text = label,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            color = if (isCur) Color.White else colors.textPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🔒 Screen Security (FLAG_SECURE)", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Blocks screenshots & hides content in recent apps", color = colors.textMuted, fontSize = 11.sp)
                            }
                            Switch(
                                checked = isFlagSecureEnabled,
                                onCheckedChange = onToggleFlagSecure,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = colors.accent
                                )
                            )
                        }
                    }
                }
            }

            // TRASH (30-DAY AUTO-PURGE)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenTrash() },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🗑️", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text("Trash", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("Restore or permanently delete (30-day auto-purge)", color = colors.textMuted, fontSize = 12.sp)
                            }
                        }
                        Text("›", color = colors.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // SUPPORT (FEEDBACK & BUG REPORT)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.support_section_title),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sendFeedback(activity, FeedbackType.FEEDBACK)
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Feedback,
                                    contentDescription = stringResource(R.string.support_send_feedback_title),
                                    tint = colors.accent,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.support_send_feedback_title),
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = stringResource(R.string.support_send_feedback_subtitle),
                                        color = colors.textMuted,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Text("›", color = colors.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sendFeedback(activity, FeedbackType.BUG)
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.BugReport,
                                    contentDescription = stringResource(R.string.support_report_bug_title),
                                    tint = colors.accent,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.support_report_bug_title),
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = stringResource(R.string.support_report_bug_subtitle),
                                        color = colors.textMuted,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Text("›", color = colors.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

        }
    }
}

