package com.example.ownscan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// -------------------------------------------------------------
// MAIN APP NAVIGATION & GLOBAL STATE
// -------------------------------------------------------------

@Composable
fun MainApp(
    activity: Activity,
    incomingIntent: Intent? = null,
    onIntentHandled: () -> Unit = {},
    isDarkTheme: Boolean,
    selectedAccentHex: Long,
    isFlagSecureEnabled: Boolean = false,
    onToggleFlagSecure: (Boolean) -> Unit = {},
    isDynamicColorEnabled: Boolean = false,
    onToggleDynamicColor: (Boolean) -> Unit = {},
    onToggleTheme: (Boolean) -> Unit,
    onSelectAccent: (Long) -> Unit,
    onRelockApp: () -> Unit = {}
) {
    val colors = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()

    var currentTab by remember { mutableIntStateOf(0) }
    var storedDocs by remember { mutableStateOf<List<LocalDoc>>(emptyList()) }
    var userFolders by remember { mutableStateOf<List<UserFolder>>(emptyList()) }
    var favoritePaths by remember { mutableStateOf(loadFavoritePaths(activity)) }

    // Instant local cache initialization so cloud icon is visible immediately
    val backedUpDocFolderNames by DriveBackupManager.backedUpDocFolderNames.collectAsState()
    val uploadingDocFolderNames by DriveBackupManager.uploadingDocFolderNames.collectAsState()

    var activeFolder by remember { mutableStateOf<String?>(null) }
    var selectedDocForDetail by remember { mutableStateOf<LocalDoc?>(null) }

    var fullPreviewDocState by remember { mutableStateOf<Pair<LocalDoc, Int>?>(null) }
    var editingFileState by remember { mutableStateOf<File?>(null) }

    var pendingPageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showNameDialog by remember { mutableStateOf(false) }
    var customDocName by remember { mutableStateOf("") }
    var suggestedDocName by remember { mutableStateOf<String?>(null) }
    var isAnalyzingDocName by remember { mutableStateOf(false) }
    var targetFolderForScan by remember { mutableStateOf<String?>(null) }

    // Phase 6: Share Target & Notification Permission
    var pendingIncomingShare by remember { mutableStateOf<IncomingShareData?>(null) }
    var isProcessingShareImport by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    activity,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        DriveBackupManager.initLocalCache(activity)
        ExpiryReminderWorker.schedule(activity.applicationContext)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val sessionUnlockedFolders = remember { mutableStateListOf<String>() }
    var isAwaitingExternalResult by remember { mutableStateOf(false) }

    // Auto-relock on background (ON_STOP) + FLAG_SECURE reset + session wipe
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (!isAwaitingExternalResult) {
                    AppLockManager.onAppBackgrounded()
                    sessionUnlockedFolders.clear()
                    LockedDocCryptoManager.wipeDecryptedSession(activity)
                    if (selectedDocForDetail?.isLocked == true) {
                        selectedDocForDetail = null
                    }
                    if (fullPreviewDocState?.first?.isLocked == true) {
                        fullPreviewDocState = null
                    }
                    SecureFlagHelper.setSecureFlag(activity, isFlagSecureEnabled)
                }
            } else if (event == Lifecycle.Event.ON_START) {
                if (!isAwaitingExternalResult) {
                    if (AppLockManager.onAppForegrounded(activity)) {
                        onRelockApp()
                    }
                }
            } else if (event == Lifecycle.Event.ON_RESUME) {
                isAwaitingExternalResult = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Inactivity timeout auto-relock
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5000L)
            val timeoutSec = AppLockManager.getInactivityTimeoutSeconds(activity)
            if (AppLockManager.isInactive(timeoutSec)) {
                if (sessionUnlockedFolders.isNotEmpty() || selectedDocForDetail?.isLocked == true || fullPreviewDocState?.first?.isLocked == true) {
                    sessionUnlockedFolders.clear()
                    LockedDocCryptoManager.wipeDecryptedSession(activity)
                    if (selectedDocForDetail?.isLocked == true) selectedDocForDetail = null
                    if (fullPreviewDocState?.first?.isLocked == true) fullPreviewDocState = null
                    SecureFlagHelper.setSecureFlag(activity, isFlagSecureEnabled)
                }
                if (AppLockManager.isAppLockEnabled(activity)) {
                    onRelockApp()
                }
            }
        }
    }

    LaunchedEffect(selectedDocForDetail, fullPreviewDocState, isFlagSecureEnabled) {
        val isViewingLocked = (selectedDocForDetail?.isLocked == true) || (fullPreviewDocState?.first?.isLocked == true)
        SecureFlagHelper.setSecureFlag(activity, isViewingLocked || isFlagSecureEnabled)
        AppLockManager.recordUserInteraction()
    }

    LaunchedEffect(Unit) {
        OcrManager.processAllPendingDocs(activity)
    }

    // HOISTED GLOBAL SYNC STATE (VISIBLE ACROSS ALL TABS)
    var isGlobalSyncing by remember { mutableStateOf(false) }
    var globalSyncProgress by remember { mutableFloatStateOf(0f) }
    var globalSyncStatusText by remember { mutableStateOf("") }
    var globalSyncCompletionMsg by remember { mutableStateOf<String?>(null) }
    var showTrashScreen by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedDocPaths by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showBatchMoveDialog by remember { mutableStateOf(false) }
    var showBatchSaveDialog by remember { mutableStateOf(false) }
    var isBatchSaving by remember { mutableStateOf(false) }
    var batchSaveProgressText by remember { mutableStateOf("") }
    var showMergeDocsDialog by remember { mutableStateOf(false) }
    var isMergingDocs by remember { mutableStateOf(false) }
    var showBatchTagDialog by remember { mutableStateOf(false) }
    var multiSelectLockedCloudState by remember { mutableStateOf<Pair<List<LocalDoc>, List<LocalDoc>>?>(null) }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedDocPaths = emptySet()
    }

    fun refreshDocs() {
        storedDocs = loadAllInAppDocuments(activity)
        userFolders = loadUserFolders(activity)
        favoritePaths = loadFavoritePaths(activity)
        selectedDocForDetail?.let { current ->
            selectedDocForDetail = storedDocs.find { it.directory.absolutePath == current.directory.absolutePath }
        }
        val account = DriveBackupManager.getSignedInAccount(activity)
        if (account != null) {
            coroutineScope.launch {
                when (val res = DriveBackupManager.getBackedUpDocFolderNames(activity, account)) {
                    is DriveResult.Success -> {
                        DriveBackupManager.setLocalCachedDocIds(activity, res.data)
                    }
                    is DriveResult.Failure -> {
                        DriveBackupManager.initLocalCache(activity)
                    }
                }
            }
        } else {
            DriveBackupManager.initLocalCache(activity)
        }
    }

    // Unified global sync function called by Pull-to-Sync, Settings, or AutoSync
    fun startGlobalSync(reason: String = "Manual") {
        val account = DriveBackupManager.getSignedInAccount(activity)
        if (account == null) {
            Toast.makeText(activity, "Please connect Google Account in Settings first", Toast.LENGTH_SHORT).show()
            return
        }
        if (isGlobalSyncing) return

        coroutineScope.launch {
            isGlobalSyncing = true
            globalSyncProgress = 0f
            globalSyncStatusText = "Preparing files for sync..."
            globalSyncCompletionMsg = null

            val docsToBackup = loadAllInAppDocuments(activity)
            if (docsToBackup.isEmpty()) {
                isGlobalSyncing = false
                globalSyncCompletionMsg = "No documents found to sync."
                delay(3000)
                globalSyncCompletionMsg = null
                return@launch
            }

            var finished = 0
            val updatedBackedUpSet = backedUpDocFolderNames.toMutableSet()

            val res = DriveBackupManager.backupAllDocsWithProgress(activity, account) { current, total, doc ->
                finished = current
                globalSyncProgress = current.toFloat() / total.toFloat()
                val pct = (globalSyncProgress * 100).roundToInt()
                globalSyncStatusText = "Syncing '${doc.displayName}' ($current/$total - $pct%)"

                // Real-time local state update so folder files show cloud icon immediately
                updatedBackedUpSet.add(doc.folderName)
                DriveBackupManager.addDocIdToLocalCache(activity, doc.folderName)
            }

            isGlobalSyncing = false
            refreshDocs()

            when (res) {
                is DriveResult.Success -> {
                    val sync = res.data
                    if (sync.isNothingChanged) {
                        globalSyncCompletionMsg = "✓ Cloud sync complete: Everything is up to date."
                        Toast.makeText(activity, "All files already up to date in cloud", Toast.LENGTH_SHORT).show()
                    } else {
                        globalSyncCompletionMsg = "✓ Cloud sync complete: ${sync.uploaded} uploaded, ${sync.skipped} up to date"
                        Toast.makeText(activity, "Cloud sync complete: ${sync.uploaded} uploaded", Toast.LENGTH_LONG).show()
                    }
                }
                is DriveResult.Failure -> {
                    globalSyncCompletionMsg = "Sync failed: ${res.displayMessage}"
                    Toast.makeText(activity, "Sync failed: ${res.displayMessage}", Toast.LENGTH_LONG).show()
                }
            }

            delay(3500)
            globalSyncCompletionMsg = null
        }
    }

    fun triggerAutoSyncIfEnabled() {
        val prefs = activity.getSharedPreferences("scanvault_settings", Context.MODE_PRIVATE)
        val autoSync = prefs.getBoolean("auto_sync", false)
        if (autoSync) {
            startGlobalSync("AutoSync")
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DocumentRepository.indexExistingDocs(activity)
            TrashPurgeWorker.schedulePeriodic(activity)
            refreshDocs()
        }
    }

    val options = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(35)
            .setResultFormats(RESULT_FORMAT_JPEG)
            .setScannerMode(SCANNER_MODE_FULL)
            .build()
    }
    val scanner = remember { GmsDocumentScanning.getClient(options) }

    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        isAwaitingExternalResult = false
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages?.map { it.imageUri } ?: emptyList()

            if (pages.isNotEmpty()) {
                val defaultTimestamp = SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())
                customDocName = "Scan_$defaultTimestamp"
                suggestedDocName = null
                isAnalyzingDocName = true
                pendingPageUris = pages
                targetFolderForScan = activeFolder
                showNameDialog = true

                coroutineScope.launch {
                    val suggestion = OcrManager.quickAnalyzeFirstPage(activity, pages.first())
                    suggestedDocName = suggestion
                    isAnalyzingDocName = false
                }
            }
        }
    }

    LaunchedEffect(incomingIntent) {
        val current = incomingIntent ?: return@LaunchedEffect
        val action = current.action ?: return@LaunchedEffect

        when (action) {
            Intent.ACTION_SEND -> {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    current.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    current.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (streamUri != null) {
                    val mimeType = current.type ?: "application/octet-stream"
                    pendingIncomingShare = IncomingShareData(listOf(streamUri), mimeType)
                }
                onIntentHandled()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    current.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    current.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (!streamUris.isNullOrEmpty()) {
                    val mimeType = current.type ?: "application/octet-stream"
                    pendingIncomingShare = IncomingShareData(streamUris, mimeType)
                }
                onIntentHandled()
            }
            "com.example.ownscan.ACTION_QUICK_SCAN" -> {
                onIntentHandled()
                scanner.getStartScanIntent(activity)
                    .addOnSuccessListener { intentSender ->
                        scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(activity, "Failed to launch scanner: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            "com.example.ownscan.ACTION_SEARCH" -> {
                onIntentHandled()
                currentTab = 1
            }
            "com.example.ownscan.ACTION_VIEW_RECENTS" -> {
                onIntentHandled()
                currentTab = 0
            }
            "com.example.ownscan.ACTION_IMPORT_FILE" -> {
                onIntentHandled()
                scanner.getStartScanIntent(activity)
                    .addOnSuccessListener { intentSender ->
                        scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                    }
            }
            else -> {
                val isLockedExpiry = current.getBooleanExtra("IS_LOCKED_EXPIRY", false)
                if (isLockedExpiry) {
                    Toast.makeText(activity, "A locked document has an upcoming expiry reminder", Toast.LENGTH_LONG).show()
                    onIntentHandled()
                } else {
                    val openDoc = current.getStringExtra("OPEN_DOC_FOLDER")
                    if (openDoc != null) {
                        val doc = storedDocs.find { it.folderName == openDoc }
                        if (doc != null) {
                            if (doc.isLocked) {
                                if (sessionUnlockedFolders.contains(doc.folderName)) {
                                    selectedDocForDetail = doc
                                }
                            } else {
                                selectedDocForDetail = doc
                            }
                        }
                        onIntentHandled()
                    }
                }
            }
        }
    }

    if (pendingIncomingShare != null) {
        ShareTargetImportDialog(
            colors = colors,
            shareData = pendingIncomingShare!!,
            storedDocs = storedDocs,
            onDismiss = { pendingIncomingShare = null },
            onImportConfirmed = { isNewDoc, newDocName, existingDoc ->
                val data = pendingIncomingShare ?: return@ShareTargetImportDialog
                pendingIncomingShare = null
                isProcessingShareImport = true

                coroutineScope.launch {
                    try {
                        val targetDir = ShareImportProcessor.importSharedContent(
                            context = activity,
                            uris = data.uris,
                            isNewDoc = isNewDoc,
                            newDocName = newDocName,
                            existingDoc = existingDoc
                        )
                        if (targetDir != null) {
                            DocumentRepository.indexExistingDocs(activity)
                            refreshDocs()
                            val updatedDocs = loadAllInAppDocuments(activity)
                            val docToOpen = updatedDocs.find { it.directory.absolutePath == targetDir.absolutePath }
                            if (docToOpen != null) {
                                selectedDocForDetail = docToOpen
                            }
                            Toast.makeText(activity, "Imported ${data.uris.size} item(s) successfully!", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(activity, "Failed to import content: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        isProcessingShareImport = false
                    }
                }
            }
        )
    }

    if (isProcessingShareImport) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = {},
            title = { Text("Importing Content", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Processing shared pages...", color = colors.textPrimary, fontSize = 14.sp)
                }
            },
            confirmButton = {}
        )
    }

    if (showNameDialog && pendingPageUris.isNotEmpty()) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = {
                val savedFolder = savePagesToAppStorage(activity, pendingPageUris, customDocName, targetFolderForScan)
                refreshDocs()
                triggerAutoSyncIfEnabled()
                showNameDialog = false
                pendingPageUris = emptyList()
                savedFolder?.let { folder ->
                    coroutineScope.launch(Dispatchers.IO) {
                        OcrManager.processDocumentOcr(activity, folder)
                    }
                }
            },
            title = { Text("Name Document", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Scanned ${pendingPageUris.size} page(s). Saving to: ${targetFolderForScan?.let { "📁 $it" } ?: "Root"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (isAnalyzingDocName) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accent)
                            Text("Detecting document type & date...", color = colors.textMuted, fontSize = 12.sp)
                        }
                    } else if (suggestedDocName != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colors.accent.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                                .clickable {
                                    customDocName = suggestedDocName!!
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("✨", fontSize = 16.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Suggested Title (Tap to apply):", fontSize = 10.sp, color = colors.textMuted)
                                    Text(suggestedDocName!!, fontSize = 13.sp, color = colors.accent, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = customDocName,
                        onValueChange = { customDocName = it },
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
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalName = sanitizeName(customDocName).ifEmpty { "Scan_${System.currentTimeMillis()}" }
                        val savedFolder = savePagesToAppStorage(activity, pendingPageUris, finalName, targetFolderForScan)
                        refreshDocs()
                        triggerAutoSyncIfEnabled()
                        showNameDialog = false
                        pendingPageUris = emptyList()
                        savedFolder?.let { folder ->
                            coroutineScope.launch(Dispatchers.IO) {
                                OcrManager.processDocumentOcr(activity, folder)
                            }
                        }
                        Toast.makeText(activity, "Saved successfully!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNameDialog = false
                        pendingPageUris = emptyList()
                    }
                ) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }


    if (editingFileState != null) {
        BackHandler { editingFileState = null }
        ImageEditorScreen(
            file = editingFileState!!,
            onClose = { editingFileState = null },
            onSaved = {
                editingFileState = null
                refreshDocs()
                triggerAutoSyncIfEnabled()
            }
        )
    } else if (fullPreviewDocState != null) {
        val (doc, pageIdx) = fullPreviewDocState!!
        BackHandler { fullPreviewDocState = null }
        GalleryViewerScreen(
            doc = doc,
            initialPageIndex = pageIdx,
            onClose = { fullPreviewDocState = null },
            onEditPage = { fileToEdit -> editingFileState = fileToEdit }
        )
    } else if (selectedDocForDetail != null) {
        BackHandler {
            selectedDocForDetail = null
            refreshDocs()
        }
        DocDetailScreen(
            activity = activity,
            currentDoc = selectedDocForDetail!!,
            onBack = {
                selectedDocForDetail = null
                refreshDocs()
            },
            onDocUpdated = {
                refreshDocs()
                triggerAutoSyncIfEnabled()
            },
            onOpenFullGallery = { pageIndex ->
                fullPreviewDocState = Pair(selectedDocForDetail!!, pageIndex)
            },
            onEditPage = { fileToEdit -> editingFileState = fileToEdit },
            onSetAwaitingExternalResult = { isAwaitingExternalResult = it }
        )
    } else if (showTrashScreen) {
        TrashScreen(
            activity = activity,
            onBack = {
                showTrashScreen = false
                refreshDocs()
            },
            onRefreshMainDocs = { refreshDocs() }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = colors.background,
            contentWindowInsets = WindowInsets.statusBars,
            bottomBar = {
                if (isSelectionMode) {
                    MultiSelectBottomBar(
                        onMove = { if (selectedDocPaths.isNotEmpty()) showBatchMoveDialog = true },
                        onMerge = {
                            if (selectedDocPaths.size >= 2) {
                                val docsToMerge = storedDocs.filter { selectedDocPaths.contains(it.directory.absolutePath) }
                                val anyLocked = docsToMerge.any { it.isLocked }
                                if (anyLocked) {
                                    BiometricAuthManager.authenticate(
                                        activity = activity as FragmentActivity,
                                        title = "Merge Locked Documents",
                                        subtitle = "Biometric authentication required to merge locked documents",
                                        onSuccess = { showMergeDocsDialog = true },
                                        onError = {}
                                    )
                                } else {
                                    showMergeDocsDialog = true
                                }
                            }
                        },
                        canMerge = (selectedDocPaths.size >= 2),
                        onTag = { if (selectedDocPaths.isNotEmpty()) showBatchTagDialog = true },
                        onShare = {
                            if (selectedDocPaths.isNotEmpty()) {
                                val docsToShare = storedDocs.filter { selectedDocPaths.contains(it.directory.absolutePath) }
                                val hasLockedDocs = docsToShare.any { it.isLocked }
                                if (hasLockedDocs) {
                                    BiometricAuthManager.authenticate(
                                        activity = activity as FragmentActivity,
                                        title = "Unlock Documents to Share",
                                        subtitle = "Biometric authentication required to share locked documents",
                                        onSuccess = {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val allImages = mutableListOf<File>()
                                                for (d in docsToShare) {
                                                    val resolved = resolveDecryptedImageFiles(activity, d)
                                                    allImages.addAll(resolved)
                                                }
                                                withContext(Dispatchers.Main) {
                                                    if (allImages.isNotEmpty()) {
                                                        shareMultipleImages(activity, allImages)
                                                        selectedDocPaths = emptySet()
                                                        isSelectionMode = false
                                                    } else {
                                                        Toast.makeText(activity, "Couldn't prepare the file", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        },
                                        onError = { err ->
                                            Toast.makeText(activity, err.ifBlank { "Authentication required to share" }, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                } else {
                                    val allImages = docsToShare.flatMap { it.imageFiles }
                                    if (allImages.isNotEmpty()) {
                                        shareMultipleImages(activity, allImages)
                                        selectedDocPaths = emptySet()
                                        isSelectionMode = false
                                    }
                                }
                            }
                        },
                        onSaveToDevice = {
                            if (selectedDocPaths.isNotEmpty()) {
                                val selectedDocs = storedDocs.filter { selectedDocPaths.contains(it.directory.absolutePath) }
                                val hasLockedDocs = selectedDocs.any { it.isLocked }
                                if (hasLockedDocs) {
                                    BiometricAuthManager.authenticate(
                                        activity = activity as FragmentActivity,
                                        title = "Unlock Documents to Save",
                                        subtitle = "Biometric authentication required to export locked documents",
                                        onSuccess = { showBatchSaveDialog = true },
                                        onError = { err -> Toast.makeText(activity, err, Toast.LENGTH_SHORT).show() }
                                    )
                                } else {
                                    showBatchSaveDialog = true
                                }
                            }
                        },
                        onBackup = {
                            if (selectedDocPaths.isNotEmpty()) {
                                val selectedDocs = storedDocs.filter { selectedDocPaths.contains(it.directory.absolutePath) }
                                val lockedDocs = selectedDocs.filter { it.isLocked }
                                val unlockedDocs = selectedDocs.filter { !it.isLocked }

                                if (lockedDocs.isNotEmpty()) {
                                    multiSelectLockedCloudState = Pair(lockedDocs, unlockedDocs)
                                } else {
                                    val account = DriveBackupManager.getSignedInAccount(activity)
                                    if (account == null) {
                                        Toast.makeText(activity, "Please sign into Google in Settings first", Toast.LENGTH_SHORT).show()
                                    } else {
                                        coroutineScope.launch {
                                            Toast.makeText(activity, "Backing up ${selectedDocPaths.size} document(s)...", Toast.LENGTH_SHORT).show()
                                            var count = 0
                                            selectedDocPaths.forEach { path ->
                                                val doc = storedDocs.find { it.directory.absolutePath == path }
                                                if (doc != null && !doc.isLocked) {
                                                    val res = DriveBackupManager.backupSingleDoc(activity, account, doc, forceUpload = true)
                                                    if (res is DriveResult.Success) count++
                                                }
                                            }
                                            selectedDocPaths = emptySet()
                                            isSelectionMode = false
                                            refreshDocs()
                                            Toast.makeText(activity, "Backed up $count document(s) to Drive!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        },
                        onLock = {
                            if (selectedDocPaths.isNotEmpty()) {
                                val selectedDocs = storedDocs.filter { selectedDocPaths.contains(it.directory.absolutePath) }
                                val anyUnlocked = selectedDocs.any { !it.isLocked }
                                if (anyUnlocked) {
                                    if (!BiometricAuthManager.isAuthAvailable(activity)) {
                                        Toast.makeText(activity, "Device screen lock required to lock documents", Toast.LENGTH_LONG).show()
                                    } else {
                                        BiometricAuthManager.authenticate(
                                            activity = activity as FragmentActivity,
                                            title = "Lock Selected Documents",
                                            subtitle = "Verify biometric or device credential to lock documents",
                                            onSuccess = {
                                                coroutineScope.launch {
                                                    selectedDocs.forEach { doc ->
                                                        DocumentRepository.updateDocLockStatus(activity, doc, true)
                                                    }
                                                    selectedDocPaths = emptySet()
                                                    isSelectionMode = false
                                                    refreshDocs()
                                                    Toast.makeText(activity, "Locked ${selectedDocs.size} document(s)", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onError = { err ->
                                                Toast.makeText(activity, "Lock cancelled: $err", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                } else {
                                    BiometricAuthManager.authenticate(
                                        activity = activity as FragmentActivity,
                                        title = "Unlock Selected Documents",
                                        onSuccess = {
                                            coroutineScope.launch {
                                                selectedDocs.forEach { doc ->
                                                    DocumentRepository.updateDocLockStatus(activity, doc, false)
                                                }
                                                selectedDocPaths = emptySet()
                                                isSelectionMode = false
                                                refreshDocs()
                                                Toast.makeText(activity, "Unlocked ${selectedDocs.size} document(s)", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onError = { err ->
                                            Toast.makeText(activity, "Unlock cancelled: $err", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        },
                        onDelete = {
                            if (selectedDocPaths.isNotEmpty()) {
                                val anyLocked = selectedDocPaths.any { path ->
                                    storedDocs.find { it.directory.absolutePath == path }?.isLocked == true
                                }
                                if (anyLocked) {
                                    BiometricAuthManager.authenticate(
                                        activity = activity as FragmentActivity,
                                        title = "Delete Selected Documents",
                                        subtitle = "One or more selected documents are locked. Verify biometric to delete.",
                                        onSuccess = {
                                            showBatchDeleteConfirm = true
                                        },
                                        onError = { err ->
                                            Toast.makeText(activity, "Authentication failed: $err", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                } else {
                                    showBatchDeleteConfirm = true
                                }
                            }
                        }
                    )
                } else {
                    MockupBottomBar(
                        selectedTab = currentTab,
                        onSelectTab = {
                            currentTab = it
                            if (it != 1) activeFolder = null
                            isSelectionMode = false
                            selectedDocPaths = emptySet()
                        },
                        onCenterScanClick = {
                            targetFolderForScan = activeFolder
                            scanner.getStartScanIntent(activity)
                                .addOnSuccessListener { intentSender ->
                                    scannerLauncher.launch(
                                        IntentSenderRequest.Builder(intentSender).build()
                                    )
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                        }
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // GLOBAL STICKY SYNC PROGRESS & COMPLETION BANNER (SURVIVES TAB SWITCHING)
                AnimatedVisibility(visible = isGlobalSyncing || globalSyncCompletionMsg != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isGlobalSyncing) colors.surface else colors.accentGreen.copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = CardDefaults.outlinedCardBorder().copy(
                            brush = androidx.compose.ui.graphics.SolidColor(
                                if (isGlobalSyncing) colors.accent else colors.accentGreen
                            )
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            if (isGlobalSyncing) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = colors.accent,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = globalSyncStatusText,
                                            color = colors.textPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${(globalSyncProgress * 100).roundToInt()}%",
                                        color = colors.accent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { globalSyncProgress },
                                    color = colors.accent,
                                    trackColor = colors.cardSubtle,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            } else if (globalSyncCompletionMsg != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = globalSyncCompletionMsg!!,
                                        color = colors.accentGreen,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(
                                        onClick = { globalSyncCompletionMsg = null },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Text("✕", color = colors.textMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (currentTab) {
                        0 -> HomeScreenDesign(
                            activity = activity,
                            storedDocs = storedDocs,
                            userFolders = userFolders,
                            favoritePaths = favoritePaths,
                            backedUpDocFolderNames = backedUpDocFolderNames,
                            sessionUnlockedFolders = sessionUnlockedFolders,
                            onOpenDoc = { doc ->
                                if (doc.isLocked && !sessionUnlockedFolders.contains(doc.folderName)) {
                                    BiometricAuthManager.authenticate(
                                        activity = activity as FragmentActivity,
                                        title = "Unlock ${doc.displayName}",
                                        onSuccess = {
                                            coroutineScope.launch {
                                                try {
                                                    LockedDocCryptoManager.prepareUnlockedSession(activity, doc.directory)
                                                    sessionUnlockedFolders.add(doc.folderName)
                                                    markDocOpened(activity, doc.directory.absolutePath)
                                                    selectedDocForDetail = doc
                                                } catch (e: Exception) {
                                                    Toast.makeText(activity, "Failed to unlock: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        onError = { err ->
                                            Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                } else {
                                    markDocOpened(activity, doc.directory.absolutePath)
                                    selectedDocForDetail = doc
                                }
                            },
                            onToggleFavorite = { path ->
                                favoritePaths = toggleDocFavorite(activity, path)
                            },
                            onEditPage = { file -> editingFileState = file },
                            onRefresh = { refreshDocs() },
                            onNavigateToSearch = { currentTab = 1 },
                            onNavigateToSettings = { currentTab = 3 },
                            isSelectionMode = isSelectionMode,
                            selectedDocPaths = selectedDocPaths,
                            onUpdateSelectionMode = { isSelectionMode = it },
                            onUpdateSelectedDocPaths = { selectedDocPaths = it }
                        )
                        1 -> FilesScreenDesign(
                            activity = activity,
                            storedDocs = storedDocs,
                            userFolders = userFolders,
                            favoritePaths = favoritePaths,
                            backedUpDocFolderNames = backedUpDocFolderNames,
                            sessionUnlockedFolders = sessionUnlockedFolders,
                            onUpdateBackedUpList = { updatedSet -> DriveBackupManager.setLocalCachedDocIds(activity, updatedSet) },
                            onTriggerCloudSync = { startGlobalSync("PullToSync") },
                            isSyncInProgress = isGlobalSyncing,
                            activeFolder = activeFolder,
                            onSelectFolder = { folderName ->
                                val docsInFolder = storedDocs.filter { it.parentFolder == folderName }
                                val allLocked = docsInFolder.isNotEmpty() && docsInFolder.all { it.isLocked }
                                if (allLocked && !sessionUnlockedFolders.contains(folderName)) {
                                    BiometricAuthManager.authenticate(
                                        activity = activity as FragmentActivity,
                                        title = "Unlock Folder '$folderName'",
                                        onSuccess = {
                                            sessionUnlockedFolders.add(folderName)
                                            activeFolder = folderName
                                        },
                                        onError = { err ->
                                            Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                } else {
                                    activeFolder = folderName
                                }
                            },
                            onBackFolder = { activeFolder = null },
                            onOpenDoc = { doc ->
                                if (doc.isLocked && !sessionUnlockedFolders.contains(doc.folderName)) {
                                    BiometricAuthManager.authenticate(
                                        activity = activity as FragmentActivity,
                                        title = "Unlock ${doc.displayName}",
                                        onSuccess = {
                                            coroutineScope.launch {
                                                try {
                                                    LockedDocCryptoManager.prepareUnlockedSession(activity, doc.directory)
                                                    sessionUnlockedFolders.add(doc.folderName)
                                                    markDocOpened(activity, doc.directory.absolutePath)
                                                    selectedDocForDetail = doc
                                                } catch (e: Exception) {
                                                    Toast.makeText(activity, "Failed to unlock: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        onError = { err ->
                                            Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                } else {
                                    markDocOpened(activity, doc.directory.absolutePath)
                                    selectedDocForDetail = doc
                                }
                            },
                            onToggleFavorite = { path ->
                                favoritePaths = toggleDocFavorite(activity, path)
                            },
                            onEditPage = { file -> editingFileState = file },
                            onScanTrigger = { folder ->
                                targetFolderForScan = folder
                                scanner.getStartScanIntent(activity)
                                    .addOnSuccessListener { intentSender ->
                                        scannerLauncher.launch(
                                            IntentSenderRequest.Builder(intentSender).build()
                                        )
                                    }
                                    .addOnFailureListener { e ->
                                        Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                            },
                            onRefresh = {
                                refreshDocs()
                                triggerAutoSyncIfEnabled()
                            },
                            onNavigateToSettings = { currentTab = 3 },
                            isSelectionMode = isSelectionMode,
                            selectedDocPaths = selectedDocPaths,
                            onUpdateSelectionMode = { isSelectionMode = it },
                            onUpdateSelectedDocPaths = { selectedDocPaths = it }
                        )
                        2 -> ToolsScreenDesign(
                            activity = activity,
                            storedDocs = storedDocs,
                            onOpenDocDirectly = { doc ->
                                if (doc.isLocked && !sessionUnlockedFolders.contains(doc.folderName)) {
                                    BiometricAuthManager.authenticate(
                                        activity = activity as FragmentActivity,
                                        title = "Unlock ${doc.displayName}",
                                        onSuccess = {
                                            coroutineScope.launch {
                                                try {
                                                    LockedDocCryptoManager.prepareUnlockedSession(activity, doc.directory)
                                                    sessionUnlockedFolders.add(doc.folderName)
                                                    markDocOpened(activity, doc.directory.absolutePath)
                                                    selectedDocForDetail = doc
                                                } catch (e: Exception) {
                                                    Toast.makeText(activity, "Failed to unlock: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        onError = { err ->
                                            Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                } else {
                                    markDocOpened(activity, doc.directory.absolutePath)
                                    selectedDocForDetail = doc
                                }
                            },
                            onRefresh = {
                                refreshDocs()
                                triggerAutoSyncIfEnabled()
                            },
                            onNavigateToSettings = { currentTab = 3 },
                            onEditPage = { file -> editingFileState = file }
                        )
                        3 -> SettingsScreenDesign(
                            activity = activity,
                            storedDocs = storedDocs,
                            isDarkTheme = isDarkTheme,
                            selectedAccentHex = selectedAccentHex,
                            isFlagSecureEnabled = isFlagSecureEnabled,
                            onToggleFlagSecure = onToggleFlagSecure,
                            isDynamicColorEnabled = isDynamicColorEnabled,
                            onToggleDynamicColor = onToggleDynamicColor,
                            onTriggerBackupNow = { startGlobalSync("SettingsBackupNow") },
                            isBackupNowRunning = isGlobalSyncing,
                            onToggleTheme = onToggleTheme,
                            onSelectAccent = onSelectAccent,
                            onRefresh = { refreshDocs() },
                            onOpenTrash = { showTrashScreen = true }
                        )
                    }
                }
            }
        }
    }

    if (showBatchDeleteConfirm && selectedDocPaths.isNotEmpty()) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("Move ${selectedDocPaths.size} Document(s) to Trash?", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Selected documents will be moved to Trash. They can be restored within 30 days.", color = colors.textMuted) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        coroutineScope.launch {
                            selectedDocPaths.forEach { path ->
                                val doc = storedDocs.find { it.directory.absolutePath == path }
                                if (doc != null) {
                                    DocumentRepository.moveToTrash(activity, doc)
                                }
                            }
                            selectedDocPaths = emptySet()
                            isSelectionMode = false
                            showBatchDeleteConfirm = false
                            refreshDocs()
                            Toast.makeText(activity, "Moved to Trash", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Move to Trash", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

    if (showBatchTagDialog && selectedDocPaths.isNotEmpty()) {
        BulkTagDialog(
            onDismiss = { showBatchTagDialog = false },
            onApplyTags = { tags ->
                coroutineScope.launch {
                    selectedDocPaths.forEach { path ->
                        val doc = storedDocs.find { it.directory.absolutePath == path }
                        if (doc != null) {
                            val mergedTags = (doc.tags + tags).distinct()
                            DocumentRepository.updateDocTags(activity, doc, mergedTags)
                        }
                    }
                    selectedDocPaths = emptySet()
                    isSelectionMode = false
                    showBatchTagDialog = false
                    Toast.makeText(activity, "Tags applied to selected documents", Toast.LENGTH_SHORT).show()
                    refreshDocs()
                }
            }
        )
    }

    if (showMergeDocsDialog && selectedDocPaths.size >= 2) {
        val docsToMerge = storedDocs.filter { selectedDocPaths.contains(it.directory.absolutePath) }
        MergeDocsDialog(
            selectedDocs = docsToMerge,
            isMerging = isMergingDocs,
            onDismiss = {
                if (!isMergingDocs) showMergeDocsDialog = false
            },
            onConfirmMerge = { mergedTitle, deleteOriginals, lockMerged ->
                val anyLocked = docsToMerge.any { it.isLocked }
                val executeMerge = {
                    isMergingDocs = true
                    coroutineScope.launch {
                        var errorMsg: String? = null
                        val mergedDoc = withContext(Dispatchers.IO) {
                            try {
                                mergeDocuments(activity, docsToMerge, mergedTitle, deleteOriginals)
                            } catch (e: Exception) {
                                errorMsg = e.message
                                null
                            }
                        }
                        if (mergedDoc != null) {
                            if (lockMerged) {
                                withContext(Dispatchers.IO) {
                                    DocumentRepository.updateDocLockStatus(activity, mergedDoc, true)
                                }
                            }
                            DocumentRepository.indexExistingDocs(activity)
                            selectedDocPaths = emptySet()
                            isSelectionMode = false
                            isMergingDocs = false
                            showMergeDocsDialog = false
                            refreshDocs()
                            Toast.makeText(activity, "Successfully merged into '${mergedDoc.displayName}'!", Toast.LENGTH_SHORT).show()
                            selectedDocForDetail = mergedDoc
                        } else {
                            isMergingDocs = false
                            Toast.makeText(activity, errorMsg ?: "Failed to merge documents", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                if (anyLocked) {
                    BiometricAuthManager.authenticate(
                        activity = activity as FragmentActivity,
                        title = "Merge Locked Documents",
                        subtitle = "Biometric authentication required to merge locked documents",
                        onSuccess = { executeMerge() },
                        onError = {}
                    )
                } else {
                    executeMerge()
                }
            }
        )
    }

    if (showBatchMoveDialog && selectedDocPaths.isNotEmpty()) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showBatchMoveDialog = false },
            title = { Text("Move ${selectedDocPaths.size} Document(s)", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select destination folder:", color = colors.textMuted, fontSize = 14.sp)

                    if (activeFolder != null) {
                        OutlinedButton(
                            onClick = {
                                selectedDocPaths.forEach { path ->
                                    moveDocToRoot(activity, File(path))
                                }
                                selectedDocPaths = emptySet()
                                isSelectionMode = false
                                showBatchMoveDialog = false
                                refreshDocs()
                                Toast.makeText(activity, "Moved to Root", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accent),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📄 Move to Root (Unfiled)", color = colors.accent)
                        }
                    }

                    userFolders.forEach { folder ->
                        if (folder.name != activeFolder) {
                            Button(
                                onClick = {
                                    selectedDocPaths.forEach { path ->
                                        moveDocToFolder(activity, File(path), folder.name)
                                    }
                                    selectedDocPaths = emptySet()
                                    isSelectionMode = false
                                    showBatchMoveDialog = false
                                    refreshDocs()
                                    Toast.makeText(activity, "Moved to '${folder.name}'", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.cardSubtle),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("📁 ${folder.name}", color = colors.textPrimary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBatchMoveDialog = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

    if (showBatchSaveDialog || isBatchSaving) {
        val selectedDocs = storedDocs.filter { selectedDocPaths.contains(it.directory.absolutePath) }
        SaveToDeviceDialog(
            docTitle = "${selectedDocs.size} documents",
            isSaving = isBatchSaving,
            progressText = batchSaveProgressText,
            onDismiss = { showBatchSaveDialog = false },
            onSaveJpg = {
                isBatchSaving = true
                batchSaveProgressText = "Saving documents as JPG..."
                coroutineScope.launch {
                    val res = SaveToDeviceManager.saveMultipleDocs(
                        context = activity,
                        docs = selectedDocs,
                        asPdf = false,
                        onProgress = { current, total, name ->
                            batchSaveProgressText = "Saving $current of $total: $name"
                        }
                    )
                    isBatchSaving = false
                    showBatchSaveDialog = false
                    when (res) {
                        is SaveToDeviceResult.Success -> {
                            Toast.makeText(activity, "Saved ${res.savedCount} images to Pictures/ScanVault", Toast.LENGTH_LONG).show()
                            isSelectionMode = false
                            selectedDocPaths = emptySet()
                        }
                        is SaveToDeviceResult.Failure -> {
                            Toast.makeText(activity, res.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onSavePdf = {
                isBatchSaving = true
                batchSaveProgressText = "Saving documents as PDF..."
                coroutineScope.launch {
                    val res = SaveToDeviceManager.saveMultipleDocs(
                        context = activity,
                        docs = selectedDocs,
                        asPdf = true,
                        onProgress = { current, total, name ->
                            batchSaveProgressText = "Saving $current of $total: $name"
                        }
                    )
                    isBatchSaving = false
                    showBatchSaveDialog = false
                    when (res) {
                        is SaveToDeviceResult.Success -> {
                            Toast.makeText(activity, "Saved ${res.savedCount} documents to Documents/ScanVault", Toast.LENGTH_LONG).show()
                            isSelectionMode = false
                            selectedDocPaths = emptySet()
                        }
                        is SaveToDeviceResult.Failure -> {
                            Toast.makeText(activity, res.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    if (multiSelectLockedCloudState != null) {
        val (lockedDocs, unlockedDocs) = multiSelectLockedCloudState!!
        LockedDocCloudBackupDialog(
            docName = if (lockedDocs.size == 1) lockedDocs.first().displayName else null,
            lockedCount = lockedDocs.size,
            unlockedCount = unlockedDocs.size,
            onDismiss = { multiSelectLockedCloudState = null },
            onProceedWithUnlocked = if (unlockedDocs.isNotEmpty()) {
                {
                    multiSelectLockedCloudState = null
                    val account = DriveBackupManager.getSignedInAccount(activity)
                    if (account == null) {
                        Toast.makeText(activity, "Please sign into Google in Settings first", Toast.LENGTH_SHORT).show()
                    } else {
                        coroutineScope.launch {
                            Toast.makeText(activity, "Backing up ${unlockedDocs.size} document(s)...", Toast.LENGTH_SHORT).show()
                            var count = 0
                            unlockedDocs.forEach { doc ->
                                val res = DriveBackupManager.backupSingleDoc(activity, account, doc, forceUpload = true)
                                if (res is DriveResult.Success) count++
                            }
                            selectedDocPaths = emptySet()
                            isSelectionMode = false
                            refreshDocs()
                            Toast.makeText(activity, "Backed up $count document(s) to Drive!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else null,
            onOpenLocalBackup = {
                multiSelectLockedCloudState = null
                currentTab = 3
            }
        )
    }

}
