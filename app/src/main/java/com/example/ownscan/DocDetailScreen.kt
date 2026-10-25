package com.example.ownscan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocDetailScreen(
    activity: Activity,
    currentDoc: LocalDoc,
    onBack: () -> Unit,
    onDocUpdated: () -> Unit,
    onOpenFullGallery: (Int) -> Unit,
    onEditPage: (File) -> Unit,
    onSetAwaitingExternalResult: (Boolean) -> Unit = {}
) {
    val colors = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: DocDetailViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    // Dialog & Sheet States
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }
    var showShareFormatSheet by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var pageTransferMode by remember { mutableStateOf<PageTransferMode?>(null) }
    var showLockGateDialog by remember { mutableStateOf(false) }
    var showDeviceLockRequiredDialog by remember { mutableStateOf(false) }
    var showExpiryDialog by remember { mutableStateOf(false) }
    var showAdvancedPdfDialog by remember { mutableStateOf(false) }
    var showSaveToDeviceDialog by remember { mutableStateOf(false) }
    var isSavingToDevice by remember { mutableStateOf(false) }
    var saveProgressText by remember { mutableStateOf("") }

    // OCR & Smart Banners
    var docOcrText by remember(currentDoc.folderName) { mutableStateOf("") }
    val idCardDismissedPref = remember(currentDoc.folderName) {
        activity.getSharedPreferences("scanvault_dismissed_id_cards", Context.MODE_PRIVATE)
    }
    var dismissedSensitiveBanner by remember(currentDoc.folderName) {
        val meta = DocMeta.readFromDir(currentDoc.directory)
        mutableStateOf(meta.idCardDismissed || idCardDismissedPref.getBoolean(currentDoc.folderName, false))
    }
    val dismissIdCardPermanently = {
        dismissedSensitiveBanner = true
        idCardDismissedPref.edit().putBoolean(currentDoc.folderName, true).apply()
        coroutineScope.launch(Dispatchers.IO) {
            val meta = DocMeta.readFromDir(currentDoc.directory)
            DocMeta.writeToDir(currentDoc.directory, meta.copy(idCardDismissed = true))
        }
    }
    var dismissedExpiryBanner by remember(currentDoc.folderName) { mutableStateOf(false) }
    val backedUpFolderNames by DriveBackupManager.backedUpDocFolderNames.collectAsState()
    val uploadingFolderNames by DriveBackupManager.uploadingDocFolderNames.collectAsState()

    // Initial load and reload on updates
    LaunchedEffect(currentDoc.folderName, currentDoc.isLocked, currentDoc.timestamp) {
        if (currentDoc.isLocked) {
            withContext(Dispatchers.IO) {
                try {
                    LockedDocCryptoManager.prepareUnlockedSession(activity, currentDoc.directory)
                } catch (e: Exception) {
                    android.util.Log.e("DocDetailScreen", "Failed preparing session: ${e.message}")
                }
            }
        }
        viewModel.loadPages(activity, currentDoc)
    }

    LaunchedEffect(currentDoc.folderName, currentDoc.isLocked) {
        withContext(Dispatchers.IO) {
            val text = try {
                if (currentDoc.isLocked) {
                    val sessionDir = LockedDocCryptoManager.getUnlockedSessionDir(activity, currentDoc.folderName)
                    val ocrFile = if (sessionDir != null) File(sessionDir, "ocr.txt") else null
                    if (ocrFile != null && ocrFile.exists()) ocrFile.readText() else ""
                } else {
                    val ocrFile = File(currentDoc.directory, "ocr.txt")
                    if (ocrFile.exists()) ocrFile.readText() else ""
                }
            } catch (_: Exception) { "" }
            docOcrText = text
        }
    }

    val isSensitiveId = remember(docOcrText, uiState.isLocked, dismissedSensitiveBanner) {
        !uiState.isLocked && !dismissedSensitiveBanner && OcrManager.isSensitiveIdDoc(docOcrText)
    }
    val detectedExpiryStr = remember(docOcrText, uiState.expiryDate, dismissedExpiryBanner) {
        if (uiState.expiryDate == null && !dismissedExpiryBanner) {
            OcrManager.detectExpiryDate(docOcrText)
        } else null
    }
    val detectedExpiryEpoch = remember(detectedExpiryStr, docOcrText) {
        if (detectedExpiryStr != null) OcrManager.detectExpiryDateEpoch(docOcrText) else null
    }

    // Intercept back press during selection mode
    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    // Launchers for Scan and Import
    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(25)
            .setResultFormats(RESULT_FORMAT_JPEG)
            .setScannerMode(SCANNER_MODE_FULL)
            .build()
    }
    val scanner = remember { GmsDocumentScanning.getClient(scannerOptions) }

    val appendScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        onSetAwaitingExternalResult(false)
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages?.map { it.imageUri } ?: emptyList()
            if (pages.isNotEmpty()) {
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        appendPagesToDocFolder(activity, currentDoc.directory, pages)
                        if (currentDoc.isLocked) {
                            try {
                                LockedDocCryptoManager.prepareUnlockedSession(activity, currentDoc.directory)
                            } catch (_: Exception) {}
                        }
                    }
                    viewModel.loadPages(activity, currentDoc)
                    onDocUpdated()
                    snackbarHostState.showSnackbar("Added ${pages.size} scanned page(s)")
                }
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        onSetAwaitingExternalResult(false)
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    appendPagesToDocFolder(activity, currentDoc.directory, uris)
                    if (currentDoc.isLocked) {
                        try {
                            LockedDocCryptoManager.prepareUnlockedSession(activity, currentDoc.directory)
                        } catch (_: Exception) {}
                    }
                }
                viewModel.loadPages(activity, currentDoc)
                onDocUpdated()
                snackbarHostState.showSnackbar("Imported ${uris.size} image(s)")
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        onSetAwaitingExternalResult(false)
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val added = withContext(Dispatchers.IO) {
                    val count = appendFilesToDocFolder(activity, currentDoc.directory, uris)
                    if (currentDoc.isLocked) {
                        try {
                            LockedDocCryptoManager.prepareUnlockedSession(activity, currentDoc.directory)
                        } catch (_: Exception) {}
                    }
                    count
                }
                viewModel.loadPages(activity, currentDoc)
                onDocUpdated()
                snackbarHostState.showSnackbar("Added $added page(s) from files")
            }
        }
    }

    // Grid state and Calvin reorderable setup
    val gridState = rememberLazyGridState()
    var dragMovedDuringSession by remember { mutableStateOf(false) }

    val reorderableLazyGridState = rememberReorderableLazyGridState(
        lazyGridState = gridState,
        onMove = { from, to ->
            dragMovedDuringSession = true
            viewModel.onMove(from.index, to.index)
        }
    )

    // Helper: Execute action with authentication check if locked
    val requireAuthIfLocked: (actionTitle: String, onAuthenticated: () -> Unit) -> Unit = { title, onAuth ->
        if (uiState.isLocked) {
            BiometricAuthManager.authenticate(
                activity = activity as FragmentActivity,
                title = title,
                subtitle = "Verify biometric or device credential to continue",
                onSuccess = onAuth,
                onError = { err -> Toast.makeText(activity, "Authentication failed: $err", Toast.LENGTH_SHORT).show() }
            )
        } else {
            onAuth()
        }
    }

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (uiState.isSelectionMode) {
                // Contextual Selection Action Bar
                Surface(
                    color = colors.surface,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close Selection", tint = colors.textPrimary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${uiState.selectedCount} selected",
                            color = colors.textPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                if (uiState.selectedCount == uiState.pageCount) {
                                    viewModel.clearSelection()
                                } else {
                                    viewModel.selectAll()
                                }
                            }
                        ) {
                            Text(
                                text = if (uiState.selectedCount == uiState.pageCount) "Clear" else "Select All",
                                color = colors.accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                // Normal Mode Top Bar
                Surface(
                    color = colors.background,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = uiState.title.ifEmpty { currentDoc.displayName },
                                    color = colors.textPrimary,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (uiState.isLocked) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("🔒", fontSize = 14.sp)
                                }
                                if (uploadingFolderNames.contains(currentDoc.folderName)) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("⏳", fontSize = 14.sp)
                                } else if (backedUpFolderNames.contains(currentDoc.folderName)) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("☁️", fontSize = 14.sp)
                                }
                            }
                            Text(
                                text = "${uiState.pageCount} ${if (uiState.pageCount == 1) "page" else "pages"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Overflow Menu Button (⋮)
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = colors.textPrimary)
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                modifier = Modifier.background(colors.surface)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Save to device", color = colors.textPrimary) },
                                    leadingIcon = { Text("💾", fontSize = 16.sp) },
                                    onClick = {
                                        showOverflowMenu = false
                                        requireAuthIfLocked("Save to device") {
                                            showSaveToDeviceDialog = true
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (uiState.expiryDate != null) "Change Expiry Date" else "Set Expiry Date",
                                            color = colors.textPrimary
                                        )
                                    },
                                    leadingIcon = { Text("📅", fontSize = 16.sp) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showExpiryDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (uiState.isLocked) "Unlock Document" else "Lock Document",
                                            color = colors.textPrimary
                                        )
                                    },
                                    leadingIcon = { Text(if (uiState.isLocked) "🔓" else "🔒", fontSize = 16.sp) },
                                    onClick = {
                                        showOverflowMenu = false
                                        if (uiState.isLocked) {
                                            BiometricAuthManager.authenticate(
                                                activity = activity as FragmentActivity,
                                                title = "Unlock ${uiState.title}",
                                                onSuccess = {
                                                    coroutineScope.launch {
                                                        val ok = DocumentRepository.updateDocLockStatus(activity, currentDoc, false)
                                                        if (ok) {
                                                            viewModel.updateLockStatus(false)
                                                            onDocUpdated()
                                                            snackbarHostState.showSnackbar("Document unlocked")
                                                        }
                                                    }
                                                },
                                                onError = { err ->
                                                    Toast.makeText(activity, err, Toast.LENGTH_SHORT).show()
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
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Secondary Action: Import
                    FilledTonalButton(
                        onClick = { showImportSheet = true },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = colors.cardSubtle,
                            contentColor = colors.textPrimary
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }

                    // Primary Action: + Scan FAB
                    ExtendedFloatingActionButton(
                        onClick = {
                            scanner.getStartScanIntent(activity)
                                .addOnSuccessListener { intentSender ->
                                    onSetAwaitingExternalResult(true)
                                    appendScannerLauncher.launch(
                                        IntentSenderRequest.Builder(intentSender).build()
                                    )
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                        },
                        containerColor = colors.accent,
                        contentColor = Color.White,
                        shape = RoundedCornerShape(16.dp),
                        icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                        text = { Text("+ Scan", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    )
                }
            }
        },
        bottomBar = {
            if (uiState.isSelectionMode) {
                Surface(
                    color = colors.surface,
                    shadowElevation = 16.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                        // Copy
                        BottomBarAction(
                            icon = "📋",
                            label = "Copy",
                            onClick = {
                                requireAuthIfLocked("Copy Pages") {
                                    pageTransferMode = PageTransferMode.COPY
                                }
                            }
                        )

                        // Move
                        BottomBarAction(
                            icon = "📦",
                            label = "Move",
                            onClick = {
                                requireAuthIfLocked("Move Pages") {
                                    pageTransferMode = PageTransferMode.MOVE
                                }
                            }
                        )

                        // Share
                        BottomBarAction(
                            icon = "📤",
                            label = "Share",
                            onClick = {
                                requireAuthIfLocked("Share Pages") {
                                    showShareFormatSheet = true
                                }
                            }
                        )

                        // Edit (Only enabled when exactly 1 page selected)
                        val canEdit = uiState.selectedCount == 1
                        BottomBarAction(
                            icon = "✏️",
                            label = "Edit",
                            textColor = if (canEdit) colors.textPrimary else colors.textMuted.copy(alpha = 0.4f),
                            onClick = {
                                if (canEdit) {
                                    val singleFile = uiState.selectedPages.firstOrNull()
                                    if (singleFile != null) {
                                        requireAuthIfLocked("Edit Page") {
                                            onEditPage(singleFile)
                                        }
                                    }
                                }
                            }
                        )

                        // Delete
                        BottomBarAction(
                            icon = "🗑️",
                            label = "Delete",
                            textColor = MaterialTheme.colorScheme.error,
                            onClick = {
                                requireAuthIfLocked("Delete Pages") {
                                    showDeleteConfirmDialog = true
                                }
                            }
                        )
                    }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Sensitive ID Warning Banner
                if (isSensitiveId) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.accentRed.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, colors.accentRed.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("🛡️", fontSize = 20.sp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sensitive ID Card Detected", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = colors.textPrimary)
                                Text("Aadhaar/PAN/ID card detected. Protect with biometric lock?", fontSize = 11.sp, color = colors.textMuted)
                            }
                            Button(
                                onClick = {
                                    if (!BiometricAuthManager.isAuthAvailable(activity)) {
                                        showDeviceLockRequiredDialog = true
                                    } else {
                                        showLockGateDialog = true
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accentRed),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Lock", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = { dismissIdCardPermanently() }) {
                                Text("✕", fontSize = 14.sp, color = colors.textMuted)
                            }
                        }
                    }
                }

                // Detected Expiry Banner
                if (detectedExpiryStr != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.accentGreen.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, colors.accentGreen.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("📅", fontSize = 20.sp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Expiry Date: ${ExpiryHelper.formatDate(detectedExpiryEpoch)}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = colors.textPrimary)
                                Text("Set reminder before this document expires?", fontSize = 11.sp, color = colors.textMuted)
                            }
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        DocumentRepository.updateDocExpiryDate(activity, currentDoc, detectedExpiryEpoch)
                                        viewModel.updateExpiryDate(detectedExpiryEpoch)
                                        onDocUpdated()
                                        snackbarHostState.showSnackbar("Reminder set for ${ExpiryHelper.formatDate(detectedExpiryEpoch)}")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Remind", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = { dismissedExpiryBanner = true }) {
                                Text("✕", fontSize = 14.sp, color = colors.textMuted)
                            }
                        }
                    }
                }

                // Empty State
                if (uiState.pages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("📄", fontSize = 54.sp)
                            Text("No pages in this document", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                            Text("Tap '+ Scan' to scan pages with your camera, or import images/PDFs from your device.", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        scanner.getStartScanIntent(activity)
                                            .addOnSuccessListener { intentSender ->
                                                onSetAwaitingExternalResult(true)
                                                appendScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                                            }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                                ) {
                                    Text("+ Scan Pages", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(onClick = { showImportSheet = true }) {
                                    Text("Import", color = colors.textPrimary)
                                }
                            }
                        }
                    }
                } else {
                    // 2-Column Grid with Drag & Drop Reordering
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(
                            items = uiState.pages,
                            key = { _, file -> file.name }
                        ) { index, file ->
                            val isSelected = file.name in uiState.selectedPageNames

                            ReorderableItem(
                                state = reorderableLazyGridState,
                                key = file.name
                            ) { isDragging ->
                                val elevationAnim by animateDpAsState(
                                    targetValue = if (isDragging) 12.dp else 1.dp,
                                    label = "elevation"
                                )
                                val scaleAnim by animateFloatAsState(
                                    targetValue = if (isDragging) 1.05f else 1.0f,
                                    label = "scale"
                                )

                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                                    border = BorderStroke(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) colors.accent else colors.cardBorder
                                    ),
                                    modifier = Modifier
                                        .animateItem()
                                        .fillMaxWidth()
                                        .aspectRatio(3f / 4f)
                                        .scale(scaleAnim)
                                        .shadow(elevationAnim, RoundedCornerShape(12.dp))
                                        .zIndex(if (isDragging) 10f else 1f)
                                        .clickable {
                                            if (uiState.isSelectionMode) {
                                                viewModel.toggleSelect(file.name)
                                            } else {
                                                onOpenFullGallery(index)
                                            }
                                        }
                                        .longPressDraggableHandle(
                                            onDragStarted = {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                dragMovedDuringSession = false
                                            },
                                            onDragStopped = {
                                                if (!dragMovedDuringSession) {
                                                    // Long press without moving = select page
                                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    viewModel.toggleSelect(file.name)
                                                } else {
                                                    // Reorder completed = persist new order
                                                    viewModel.persistOrder(activity, currentDoc.directory, currentDoc.isLocked)
                                                    onDocUpdated()
                                                }
                                                dragMovedDuringSession = false
                                            }
                                        )
                                ) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        // Image Thumbnail decoded sharply at target size
                                        if (currentDoc.isLocked && file.name.endsWith(".enc", ignoreCase = true)) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(colors.cardSubtle),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("🔒", fontSize = 28.sp)
                                            }
                                        } else {
                                            AsyncImage(
                                                model = ImageRequest.Builder(LocalContext.current)
                                                    .data(file)
                                                    .size(Size(600, 800))
                                                    .crossfade(true)
                                                    .build(),
                                                contentDescription = "Page ${index + 1}",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }

                                        // Selection overlay tint
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(colors.accent.copy(alpha = 0.22f))
                                            )
                                        }

                                        // Page-number badge in top-start corner
                                        Surface(
                                            shape = RoundedCornerShape(topStart = 12.dp, bottomEnd = 8.dp),
                                            color = Color.Black.copy(alpha = 0.70f),
                                            modifier = Modifier.align(Alignment.TopStart)
                                        ) {
                                            Text(
                                                text = "${index + 1}",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }

                                        // Checkmark badge in top-end corner when selected
                                        if (isSelected) {
                                            Surface(
                                                shape = CircleShape,
                                                color = colors.accent,
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(8.dp)
                                                    .size(24.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Selected",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(16.dp)
                                                    )
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

            // Loading overlay for PDF generation / batch operations
            if (uiState.isSharing) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.surface),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                CircularProgressIndicator(color = colors.accent)
                                Text(
                                    text = uiState.shareProgressMessage ?: "Preparing...",
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- DIALOGS & BOTTOM SHEETS ---

    // Import Options Bottom Sheet
    if (showImportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImportSheet = false },
            containerColor = colors.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Add Pages to Document",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))

                ListItem(
                    headlineContent = { Text("Import Images", fontWeight = FontWeight.SemiBold, color = colors.textPrimary) },
                    supportingContent = { Text("Select pictures from your photo gallery", color = colors.textMuted) },
                    leadingContent = { Text("🖼️", fontSize = 24.sp) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            showImportSheet = false
                            onSetAwaitingExternalResult(true)
                            imagePickerLauncher.launch("image/*")
                        }
                )

                ListItem(
                    headlineContent = { Text("Import Files (PDF / Image)", fontWeight = FontWeight.SemiBold, color = colors.textPrimary) },
                    supportingContent = { Text("Extract pages from PDF documents or external files", color = colors.textMuted) },
                    leadingContent = { Text("📁", fontSize = 24.sp) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            showImportSheet = false
                            onSetAwaitingExternalResult(true)
                            filePickerLauncher.launch(arrayOf("application/pdf", "image/*"))
                        }
                )
            }
        }
    }

    // Share Format Bottom Sheet
    if (showShareFormatSheet && uiState.selectedPages.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { showShareFormatSheet = false },
            containerColor = colors.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Share ${uiState.selectedCount} Selected Page(s)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Option 1: Share as PDF
                ListItem(
                    headlineContent = { Text("Share as PDF", fontWeight = FontWeight.SemiBold, color = colors.textPrimary) },
                    supportingContent = { Text("Compile selected pages into one PDF in current order", color = colors.textMuted) },
                    leadingContent = { Text("📄", fontSize = 24.sp) },
                    trailingContent = {
                        TextButton(onClick = {
                            showShareFormatSheet = false
                            showAdvancedPdfDialog = true
                        }) {
                            Text("Options", fontSize = 12.sp, color = colors.accent)
                        }
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            showShareFormatSheet = false
                            viewModel.setSharingState(true, "Building PDF...")
                            val orderedSelection = uiState.selectedPages
                            coroutineScope.launch {
                                val generatedFile = withContext(Dispatchers.IO) {
                                    val resolved = resolveDecryptedImageFiles(activity, currentDoc, orderedSelection)
                                    if (resolved.isEmpty()) return@withContext null
                                    val shareDir = getSharedCacheDir(activity)
                                    val target = File(shareDir, "${currentDoc.displayName}_${System.currentTimeMillis()}.pdf")
                                    val pdf = PasswordPdfExporter.generatePdfFile(
                                        context = activity,
                                        baseName = currentDoc.displayName,
                                        imageFiles = resolved
                                    )
                                    if (pdf != null && pdf.exists() && pdf.length() > 0L) {
                                        pdf.copyTo(target, overwrite = true)
                                        pdf.delete()
                                        if (target.exists() && target.length() > 0L) {
                                            target
                                        } else {
                                            target.delete()
                                            null
                                        }
                                    } else {
                                        pdf?.delete()
                                        null
                                    }
                                }
                                viewModel.setSharingState(false)
                                if (generatedFile != null && generatedFile.exists() && generatedFile.length() > 0L) {
                                    val uri = FileProvider.getUriForFile(
                                        activity,
                                        "${activity.packageName}.fileprovider",
                                        generatedFile
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    activity.startActivity(Intent.createChooser(shareIntent, "Share PDF"))
                                } else {
                                    snackbarHostState.showSnackbar("Couldn't prepare the file")
                                }
                            }
                        }
                )

                // Option 2: Share as JPG
                ListItem(
                    headlineContent = { Text("Share as JPG Images", fontWeight = FontWeight.SemiBold, color = colors.textPrimary) },
                    supportingContent = { Text("Share individual image files", color = colors.textMuted) },
                    leadingContent = { Text("🖼️", fontSize = 24.sp) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            showShareFormatSheet = false
                            viewModel.setSharingState(true, "Preparing images...")
                            val orderedSelection = uiState.selectedPages
                            coroutineScope.launch {
                                val uris = withContext(Dispatchers.IO) {
                                    val resolved = resolveDecryptedImageFiles(activity, currentDoc, orderedSelection)
                                    if (resolved.isEmpty()) return@withContext emptyList<Uri>()
                                    val shareDir = getSharedCacheDir(activity)
                                    val uriList = ArrayList<Uri>()
                                    resolved.forEachIndexed { i, file ->
                                        try {
                                            if (file.exists() && file.length() > 0L) {
                                                val shareFile = File(shareDir, "${currentDoc.displayName}_p${i + 1}_${System.currentTimeMillis()}.jpg")
                                                file.copyTo(shareFile, overwrite = true)
                                                if (shareFile.exists() && shareFile.length() > 0L) {
                                                    val u = FileProvider.getUriForFile(
                                                        activity,
                                                        "${activity.packageName}.fileprovider",
                                                        shareFile
                                                    )
                                                    uriList.add(u)
                                                } else {
                                                    shareFile.delete()
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    uriList
                                }
                                viewModel.setSharingState(false)
                                if (uris.isNotEmpty()) {
                                    val shareIntent = if (uris.size == 1) {
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "image/jpeg"
                                            putExtra(Intent.EXTRA_STREAM, uris.first())
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    } else {
                                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                            type = "image/jpeg"
                                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    }
                                    activity.startActivity(Intent.createChooser(shareIntent, "Share Images"))
                                } else {
                                    snackbarHostState.showSnackbar("Couldn't prepare the file")
                                }
                            }
                        }
                )
            }
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirmDialog && uiState.selectedPages.isNotEmpty()) {
        val isAllSelected = uiState.selectedCount >= uiState.pageCount
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text(
                    text = if (isAllSelected) "Move Entire Document to Trash?" else "Delete ${uiState.selectedCount} Pages?",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isAllSelected)
                        "All pages are selected. Moving them will move this entire document to Trash (restorable within 30 days)."
                    else
                        "Move ${uiState.selectedCount} selected page(s) to Trash? They can be restored within 30 days.",
                    color = colors.textMuted
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showDeleteConfirmDialog = false
                        val count = uiState.selectedCount
                        val selected = uiState.selectedPages
                        coroutineScope.launch {
                            if (isAllSelected) {
                                DocumentRepository.moveToTrash(activity, currentDoc)
                                if (currentDoc.isLocked) {
                                    LockedDocCryptoManager.wipeDecryptedSession(activity, currentDoc.folderName)
                                }
                                DriveBackupManager.removeDocIdFromLocalCache(activity, currentDoc.folderName)
                                viewModel.clearSelection()
                                onDocUpdated()
                                onBack()
                                Toast.makeText(activity, "'${currentDoc.displayName}' moved to Trash", Toast.LENGTH_SHORT).show()
                            } else {
                                val stamp = System.currentTimeMillis()
                                val trashedFolderName = "${stamp}__${currentDoc.displayName}_${selected.size}_Pages"
                                val trashedDir = File(getPrivateDocsDir(activity), trashedFolderName)

                                withContext(Dispatchers.IO) {
                                    movePagesToTrash(activity, currentDoc, selected)
                                    viewModel.persistOrder(activity, currentDoc.directory, currentDoc.isLocked)
                                }
                                viewModel.setLastDeletedInfo(
                                    LastDeletedInfo(
                                        trashedDir = trashedDir,
                                        deletedPageNames = selected.map { it.name },
                                        docFolder = currentDoc.directory,
                                        isLocked = currentDoc.isLocked
                                    )
                                )
                                viewModel.clearSelection()
                                viewModel.loadPages(activity, currentDoc)
                                onDocUpdated()

                                val result = snackbarHostState.showSnackbar(
                                    message = "$count page(s) moved to Trash",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.undoLastDelete(activity) {
                                        viewModel.loadPages(activity, currentDoc)
                                        onDocUpdated()
                                        Toast.makeText(activity, "Restored $count page(s)", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Text("Move to Trash", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = colors.textMuted)
                }
            }
        )
    }

    // Move & Copy Dialog
    if (pageTransferMode != null && uiState.selectedPages.isNotEmpty()) {
        val transferMode = pageTransferMode!!
        val availableDocs by produceState<List<LocalDoc>>(initialValue = emptyList(), pageTransferMode) {
            value = withContext(Dispatchers.IO) {
                loadAllInAppDocuments(activity)
            }
        }
        val orderedSelection = uiState.selectedPages

        MoveCopyPagesDialog(
            mode = transferMode,
            selectedPages = orderedSelection,
            currentDoc = currentDoc,
            availableDocs = availableDocs,
            onDismiss = { pageTransferMode = null },
            onTransferToNewDoc = { newDocTitle ->
                if (transferMode == PageTransferMode.COPY) {
                    coroutineScope.launch {
                        val newDoc = withContext(Dispatchers.IO) {
                            copyPagesToNewDoc(activity, orderedSelection, newDocTitle)
                        }
                        if (newDoc != null) {
                            DocumentRepository.indexExistingDocs(activity)
                            viewModel.clearSelection()
                            pageTransferMode = null
                            onDocUpdated()
                            snackbarHostState.showSnackbar("Copied ${orderedSelection.size} page(s) to '${newDoc.displayName}'")
                        } else {
                            snackbarHostState.showSnackbar("Failed to create document")
                        }
                    }
                } else {
                    coroutineScope.launch {
                        val isAll = orderedSelection.size >= uiState.pageCount
                        val newDoc = withContext(Dispatchers.IO) {
                            movePagesToNewDoc(activity, currentDoc, orderedSelection, newDocTitle)
                        }
                        if (newDoc != null) {
                            DocumentRepository.indexExistingDocs(activity)
                            viewModel.clearSelection()
                            pageTransferMode = null
                            if (isAll) {
                                onDocUpdated()
                                onBack()
                                Toast.makeText(activity, "Moved all pages to '${newDoc.displayName}'", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.loadPages(activity, currentDoc)
                                onDocUpdated()
                                snackbarHostState.showSnackbar("Moved ${orderedSelection.size} page(s) to '${newDoc.displayName}'")
                            }
                        } else {
                            snackbarHostState.showSnackbar("Failed to move pages")
                        }
                    }
                }
            },
            onTransferToExistingDoc = { targetDoc ->
                if (transferMode == PageTransferMode.COPY) {
                    coroutineScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            copyPagesToDoc(activity, orderedSelection, targetDoc)
                        }
                        if (ok) {
                            DocumentRepository.indexExistingDocs(activity)
                            viewModel.clearSelection()
                            pageTransferMode = null
                            if (targetDoc.folderName == currentDoc.folderName) {
                                viewModel.loadPages(activity, currentDoc)
                            }
                            onDocUpdated()
                            snackbarHostState.showSnackbar("Copied ${orderedSelection.size} page(s) to '${targetDoc.displayName}'")
                        } else {
                            snackbarHostState.showSnackbar("Failed to copy pages")
                        }
                    }
                } else {
                    coroutineScope.launch {
                        val isAll = orderedSelection.size >= uiState.pageCount
                        val ok = withContext(Dispatchers.IO) {
                            movePagesToDoc(activity, currentDoc, orderedSelection, targetDoc)
                        }
                        if (ok) {
                            DocumentRepository.indexExistingDocs(activity)
                            viewModel.clearSelection()
                            pageTransferMode = null
                            if (isAll) {
                                onDocUpdated()
                                onBack()
                                Toast.makeText(activity, "Moved all pages to '${targetDoc.displayName}'", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.loadPages(activity, currentDoc)
                                onDocUpdated()
                                snackbarHostState.showSnackbar("Moved ${orderedSelection.size} page(s) to '${targetDoc.displayName}'")
                            }
                        } else {
                            snackbarHostState.showSnackbar("Failed to move pages")
                        }
                    }
                }
            }
        )
    }

    // Expiry Date Dialog
    if (showExpiryDialog) {
        ExpiryDatePickerDialog(
            colors = colors,
            initialDate = uiState.expiryDate,
            onDismiss = { showExpiryDialog = false },
            onDateSelected = { newExpiry ->
                showExpiryDialog = false
                coroutineScope.launch {
                    DocumentRepository.updateDocExpiryDate(activity, currentDoc, newExpiry)
                    viewModel.updateExpiryDate(newExpiry)
                    onDocUpdated()
                    snackbarHostState.showSnackbar(
                        if (newExpiry != null) "Expiry date set: ${ExpiryHelper.formatDate(newExpiry)}" else "Expiry date cleared"
                    )
                }
            }
        )
    }

    // Lock Access Dialog
    if (showLockGateDialog) {
        LockAccessGateDialog(
            colors = colors,
            itemName = uiState.title,
            isFolder = false,
            onDismiss = { showLockGateDialog = false },
            onConfirmLock = {
                showLockGateDialog = false
                BiometricAuthManager.authenticate(
                    activity = activity as FragmentActivity,
                    title = "Lock '${uiState.title}'",
                    subtitle = "Verify biometric or device credential to lock this document",
                    onSuccess = {
                        coroutineScope.launch {
                            val ok = DocumentRepository.updateDocLockStatus(activity, currentDoc, true)
                            if (ok) {
                                viewModel.updateLockStatus(true)
                                onDocUpdated()
                                snackbarHostState.showSnackbar("Document locked")
                            } else {
                                snackbarHostState.showSnackbar("Failed to lock document")
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

    // Device Lock Required Dialog
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

    // Advanced PDF Export Dialog
    if (showAdvancedPdfDialog) {
        var protectPwd by remember { mutableStateOf(false) }
        var pdfPwd by remember { mutableStateOf("") }
        var addWatermark by remember { mutableStateOf(false) }
        var watermarkText by remember { mutableStateOf("FOR KYC ONLY") }

        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showAdvancedPdfDialog = false },
            title = { Text("PDF Export Options", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Configure PDF options for ${uiState.selectedCount} page(s)", color = colors.textMuted, fontSize = 13.sp)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { protectPwd = !protectPwd }
                    ) {
                        Checkbox(
                            checked = protectPwd,
                            onCheckedChange = { protectPwd = it },
                            colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("🔒 Protect PDF with Password", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    if (protectPwd) {
                        OutlinedTextField(
                            value = pdfPwd,
                            onValueChange = { pdfPwd = it },
                            singleLine = true,
                            label = { Text("Password", color = colors.textMuted) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { addWatermark = !addWatermark }
                    ) {
                        Checkbox(
                            checked = addWatermark,
                            onCheckedChange = { addWatermark = it },
                            colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("🌊 Add Watermark", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    if (addWatermark) {
                        OutlinedTextField(
                            value = watermarkText,
                            onValueChange = { watermarkText = it },
                            singleLine = true,
                            label = { Text("Watermark text", color = colors.textMuted) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAdvancedPdfDialog = false
                        viewModel.setSharingState(true, "Exporting PDF...")
                        val orderedSelection = uiState.selectedPages
                        val pwd = if (protectPwd) pdfPwd.trim() else null
                        val wm = if (addWatermark && watermarkText.isNotBlank()) watermarkText.trim() else null
                        coroutineScope.launch {
                            val uri = PasswordPdfExporter.exportPdf(
                                activity,
                                currentDoc.displayName,
                                orderedSelection,
                                pwd,
                                watermarkText = wm
                            )
                            viewModel.setSharingState(false)
                            if (uri != null) {
                                snackbarHostState.showSnackbar("PDF exported to Downloads/ScanVault")
                                openUri(activity, uri, "application/pdf")
                            } else {
                                snackbarHostState.showSnackbar("Failed to export PDF")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Export", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdvancedPdfDialog = false }) {
                    Text("Cancel", color = colors.textMuted)
                }
            }
        )
    }

    if (showSaveToDeviceDialog || isSavingToDevice) {
        val targetPages = remember(uiState.selectedPages, currentDoc.imageFiles) {
            if (uiState.selectedPages.isNotEmpty()) uiState.selectedPages else currentDoc.imageFiles
        }
        SaveToDeviceDialog(
            docTitle = uiState.title.ifEmpty { currentDoc.displayName },
            isSaving = isSavingToDevice,
            progressText = saveProgressText,
            onDismiss = { showSaveToDeviceDialog = false },
            onSaveJpg = {
                isSavingToDevice = true
                saveProgressText = "Preparing pages..."
                coroutineScope.launch {
                    val res = SaveToDeviceManager.saveDocAsJpg(
                        context = activity,
                        doc = currentDoc,
                        customPages = targetPages,
                        onProgress = { current, total ->
                            saveProgressText = "Saving page $current of $total to Pictures/ScanVault..."
                        }
                    )
                    isSavingToDevice = false
                    showSaveToDeviceDialog = false
                    when (res) {
                        is SaveToDeviceResult.Success -> {
                            val msg = "Saved to Pictures/ScanVault"
                            val snackbarResult = snackbarHostState.showSnackbar(
                                message = msg,
                                actionLabel = "Open",
                                duration = SnackbarDuration.Short
                            )
                            if (snackbarResult == SnackbarResult.ActionPerformed && res.firstUri != null) {
                                SaveToDeviceManager.openSavedFile(activity, res.firstUri, res.mimeType)
                            }
                        }
                        is SaveToDeviceResult.Failure -> {
                            snackbarHostState.showSnackbar(res.message)
                        }
                    }
                }
            },
            onSavePdf = {
                isSavingToDevice = true
                saveProgressText = "Building PDF..."
                coroutineScope.launch {
                    val res = SaveToDeviceManager.saveDocAsPdf(
                        context = activity,
                        doc = currentDoc,
                        customPages = targetPages,
                        onProgress = { current, total ->
                            saveProgressText = if (current == 1) "Generating PDF..." else "Writing to Documents/ScanVault..."
                        }
                    )
                    isSavingToDevice = false
                    showSaveToDeviceDialog = false
                    when (res) {
                        is SaveToDeviceResult.Success -> {
                            val msg = "Saved to Documents/ScanVault"
                            val snackbarResult = snackbarHostState.showSnackbar(
                                message = msg,
                                actionLabel = "Open",
                                duration = SnackbarDuration.Short
                            )
                            if (snackbarResult == SnackbarResult.ActionPerformed && res.firstUri != null) {
                                SaveToDeviceManager.openSavedFile(activity, res.firstUri, res.mimeType)
                            }
                        }
                        is SaveToDeviceResult.Failure -> {
                            snackbarHostState.showSnackbar(res.message)
                        }
                    }
                }
            }
        )
    }
}
