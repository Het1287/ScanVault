package com.example.ownscan

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// -------------------------------------------------------------
// SCREEN 1: ALL FILES (PULL-AND-HOLD CLOUD SYNC + INSTANT ICONS)
// -------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreenDesign(
    activity: Activity,
    storedDocs: List<LocalDoc>,
    userFolders: List<UserFolder>,
    favoritePaths: Set<String>,
    backedUpDocFolderNames: Set<String>,
    sessionUnlockedFolders: List<String> = emptyList(),
    onUpdateBackedUpList: (Set<String>) -> Unit,
    onTriggerCloudSync: () -> Unit,
    isSyncInProgress: Boolean,
    activeFolder: String?,
    onSelectFolder: (String) -> Unit,
    onBackFolder: () -> Unit,
    onOpenDoc: (LocalDoc) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onEditPage: (File) -> Unit,
    onScanTrigger: (String?) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToSettings: () -> Unit,
    isSelectionMode: Boolean = false,
    selectedDocPaths: Set<String> = emptySet(),
    onUpdateSelectionMode: (Boolean) -> Unit = {},
    onUpdateSelectedDocPaths: (Set<String>) -> Unit = {}
) {
    val colors = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var isSearchVisible by remember { mutableStateOf(false) }
    var selectedCollection by remember { mutableStateOf(SmartCollection.ALL) }
    var selectedTagFilter by remember { mutableStateOf<String?>(null) }
    val searchResultsMap = remember { mutableStateMapOf<String, SearchResultSnippet>() }
    var recentSearches by remember(isSearchVisible) {
        mutableStateOf(RecentSearchesManager.getRecentSearches(activity))
    }

    var folderForLockGate by remember { mutableStateOf<UserFolder?>(null) }

    val onToggleDocSelect = { path: String ->
        val next = if (selectedDocPaths.contains(path)) selectedDocPaths - path else selectedDocPaths + path
        onUpdateSelectedDocPaths(next)
        if (next.isEmpty()) onUpdateSelectionMode(false)
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            kotlinx.coroutines.delay(300)
            val trimmed = searchQuery.trim()
            val snippets = DocumentRepository.searchWithSnippets(activity, trimmed)
            searchResultsMap.clear()
            snippets.forEach { searchResultsMap[it.folderName] = it }
            RecentSearchesManager.addSearchQuery(activity, trimmed)
            recentSearches = RecentSearchesManager.getRecentSearches(activity)
        } else {
            searchResultsMap.clear()
        }
    }

    var selectedSortOption by remember { mutableStateOf(SortManager.getSavedSortOption(activity)) }
    var showSortDropdown by remember { mutableStateOf(false) }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    var folderForAction by remember { mutableStateOf<UserFolder?>(null) }
    var showFolderMenu by remember { mutableStateOf(false) }
    var showRenameFolderDialog by remember { mutableStateOf(false) }
    var renameFolderText by remember { mutableStateOf("") }
    var showDeleteFolderConfirm by remember { mutableStateOf(false) }

    var pendingImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showImageDocNameDialog by remember { mutableStateOf(false) }
    var imageDocName by remember { mutableStateOf("") }

    // PULL AND HOLD FOR 1.5 - 2 SECONDS LOGIC (ONLY ACTIVE WHEN AUTO-SYNC IS ENABLED)
    val prefs = remember { activity.getSharedPreferences("scanvault_settings", Context.MODE_PRIVATE) }
    val isAutoSyncEnabled = prefs.getBoolean("auto_sync", false) && DriveBackupManager.getSignedInAccount(activity) != null

    var pullDragY by remember { mutableFloatStateOf(0f) }
    var isHoldingToSync by remember { mutableStateOf(false) }
    var holdCountdownProgress by remember { mutableFloatStateOf(0f) }

    val isPullThresholdReached = pullDragY >= 140f

    LaunchedEffect(isPullThresholdReached, isSyncInProgress, isAutoSyncEnabled) {
        if (!isAutoSyncEnabled) {
            isHoldingToSync = false
            holdCountdownProgress = 0f
            return@LaunchedEffect
        }
        if (isPullThresholdReached && !isSyncInProgress) {
            isHoldingToSync = true
            holdCountdownProgress = 0f
            val totalDurationMs = 1500L
            val intervalMs = 50L
            var elapsed = 0L

            while (elapsed < totalDurationMs && pullDragY >= 140f) {
                delay(intervalMs)
                elapsed += intervalMs
                holdCountdownProgress = (elapsed.toFloat() / totalDurationMs).coerceIn(0f, 1f)
            }

            if (elapsed >= totalDurationMs && pullDragY >= 140f) {
                isHoldingToSync = false
                pullDragY = 0f
                onTriggerCloudSync()
            } else {
                isHoldingToSync = false
                holdCountdownProgress = 0f
            }
        } else if (!isPullThresholdReached) {
            isHoldingToSync = false
            holdCountdownProgress = 0f
        }
    }

    val nestedScrollConnection = remember(isAutoSyncEnabled) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!isAutoSyncEnabled) return Offset.Zero
                if (available.y > 0 && !isSyncInProgress) {
                    pullDragY = (pullDragY + available.y * 0.45f).coerceAtMost(220f)
                } else if (available.y < 0 && pullDragY > 0f) {
                    pullDragY = (pullDragY + available.y).coerceAtLeast(0f)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!isHoldingToSync && !isSyncInProgress) {
                    pullDragY = 0f
                }
                return super.onPostFling(consumed, available)
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val defaultName = if (uris.size == 1) {
                queryOriginalFileName(activity, uris.first())
            } else {
                "Images_${SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())}"
            }
            imageDocName = defaultName
            pendingImageUris = uris
            showImageDocNameDialog = true
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                Toast.makeText(activity, "Importing ${uris.size} file(s)...", Toast.LENGTH_SHORT).show()
                val count = withContext(Dispatchers.IO) {
                    importExternalFiles(activity, uris, activeFolder)
                }
                onRefresh()
                Toast.makeText(activity, "Imported $count document(s) successfully!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val searchFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = isSelectionMode || activeFolder != null) {
        if (isSelectionMode) {
            onUpdateSelectionMode(false)
            onUpdateSelectedDocPaths(emptySet())
        } else if (activeFolder != null) {
            onBackFolder()
        }
    }

    if (showImageDocNameDialog && pendingImageUris.isNotEmpty()) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = {
                showImageDocNameDialog = false
                pendingImageUris = emptyList()
            },
            title = { Text("Name Document", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Importing ${pendingImageUris.size} image(s) into: ${activeFolder?.let { "📁 $it" } ?: "Root"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = imageDocName,
                        onValueChange = { imageDocName = it },
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
                        val finalName = sanitizeName(imageDocName).ifEmpty { "Doc_${System.currentTimeMillis()}" }
                        savePagesToAppStorage(activity, pendingImageUris, finalName, activeFolder)
                        onRefresh()
                        showImageDocNameDialog = false
                        pendingImageUris = emptyList()
                        Toast.makeText(activity, "Images imported successfully!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImageDocNameDialog = false
                    pendingImageUris = emptyList()
                }) {
                    Text("Cancel", color = colors.textMuted)
                }
            }
        )
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create New Folder", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    label = { Text("Folder Name", color = colors.textMuted) },
                    placeholder = { Text("e.g. Invoices, Receipts", color = colors.textMuted) },
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
                        val sanitized = sanitizeName(newFolderName)
                        if (sanitized.isNotBlank()) {
                            if (createUserFolder(activity, sanitized)) {
                                onRefresh()
                                showCreateFolderDialog = false
                                newFolderName = ""
                                Toast.makeText(activity, "Folder created!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(activity, "Failed to create folder", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Create", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

    if (folderForLockGate != null) {
        LockAccessGateDialog(
            colors = colors,
            itemName = folderForLockGate!!.name,
            isFolder = true,
            onDismiss = { folderForLockGate = null },
            onConfirmLock = {
                val folderName = folderForLockGate!!.name
                folderForLockGate = null
                if (!BiometricAuthManager.isAuthAvailable(activity)) {
                    Toast.makeText(activity, "Device screen lock required to lock folders", Toast.LENGTH_LONG).show()
                } else {
                    BiometricAuthManager.authenticate(
                        activity = activity as FragmentActivity,
                        title = "Lock Folder '$folderName'",
                        subtitle = "Verify biometric or device credential to lock this folder",
                        onSuccess = {
                            coroutineScope.launch {
                                val docsInFolder = storedDocs.filter { it.parentFolder == folderName }
                                docsInFolder.forEach { doc ->
                                    DocumentRepository.updateDocLockStatus(activity, doc, true)
                                }
                                onRefresh()
                                Toast.makeText(activity, "Folder '$folderName' locked", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onError = { err ->
                            Toast.makeText(activity, "Lock cancelled: $err", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        )
    }

    if (showRenameFolderDialog && folderForAction != null) {
        RenameFolderDialog(
            currentName = folderForAction!!.name,
            onDismiss = { showRenameFolderDialog = false },
            onConfirm = { newName ->
                val sanitized = sanitizeName(newName)
                if (sanitized.isNotBlank()) {
                    if (renameUserFolder(activity, folderForAction!!.name, sanitized)) {
                        if (activeFolder == folderForAction!!.name) onBackFolder()
                        onRefresh()
                        showRenameFolderDialog = false
                        Toast.makeText(activity, "Renamed to '$sanitized'", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showDeleteFolderConfirm && folderForAction != null) {
        val count = storedDocs.count { it.parentFolder == folderForAction!!.name }
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showDeleteFolderConfirm = false },
            title = { Text("Delete Folder '${folderForAction!!.name}'?", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (count > 0) "This folder contains $count document(s). Deleting it will permanently delete all documents inside."
                    else "Are you sure you want to delete this folder?",
                    color = colors.textMuted
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        deleteUserFolder(activity, folderForAction!!.name)
                        if (activeFolder == folderForAction!!.name) onBackFolder()
                        onRefresh()
                        showDeleteFolderConfirm = false
                        Toast.makeText(activity, "Folder deleted", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFolderConfirm = false }) { Text("Cancel", color = colors.textMuted) }
            }
        )
    }

    val displayDocs = remember(
        storedDocs,
        activeFolder,
        searchQuery,
        selectedCollection,
        selectedTagFilter,
        favoritePaths,
        backedUpDocFolderNames,
        selectedSortOption,
        searchResultsMap.size
    ) {
        val filtered = if (searchQuery.isNotBlank()) {
            val matchingFolderNames = searchResultsMap.keys
            val matched = storedDocs.filter { matchingFolderNames.contains(it.folderName) }
            if (activeFolder != null) matched.filter { it.parentFolder == activeFolder } else matched
        } else if (activeFolder != null) {
            val folderDocs = storedDocs.filter { it.parentFolder == activeFolder }
            if (selectedTagFilter != null) folderDocs.filter { it.tags.contains(selectedTagFilter) } else folderDocs
        } else {
            SmartCollectionsManager.filterDocs(
                docs = storedDocs.filter { it.parentFolder == null },
                collection = selectedCollection,
                favoritePaths = favoritePaths,
                backedUpFolderNames = backedUpDocFolderNames,
                activeTag = selectedTagFilter
            )
        }
        SortManager.sortDocs(filtered, selectedSortOption)
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 22.dp)
            .nestedScroll(nestedScrollConnection)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        if (isSelectionMode) {
            MultiSelectTopBar(
                selectedCount = selectedDocPaths.size,
                totalCount = displayDocs.size,
                onSelectAll = {
                    val next = if (selectedDocPaths.size == displayDocs.size) {
                        emptySet()
                    } else {
                        displayDocs.map { it.directory.absolutePath }.toSet()
                    }
                    onUpdateSelectedDocPaths(next)
                    if (next.isEmpty()) onUpdateSelectionMode(false)
                },
                onClearSelection = {
                    onUpdateSelectedDocPaths(emptySet())
                    onUpdateSelectionMode(false)
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // PULL-AND-HOLD HOLDING INDICATOR
        AnimatedVisibility(visible = isAutoSyncEnabled && (isHoldingToSync || (pullDragY > 40f && !isSyncInProgress))) {
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(14.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.accent)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isHoldingToSync) {
                        Text(
                            text = "Keep holding to sync all files (${(holdCountdownProgress * 100).roundToInt()}%)",
                            color = colors.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { holdCountdownProgress },
                            color = colors.accent,
                            trackColor = colors.cardSubtle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    } else {
                        Text("↓ Pull down and hold for 2s to sync", color = colors.textMuted, fontSize = 12.sp)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (activeFolder != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.accent)
                            .clickable { onBackFolder() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("❮", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = activeFolder,
                        color = colors.textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = "All Files",
                        color = colors.textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isSyncInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = colors.accent,
                        strokeWidth = 2.dp
                    )
                }

                IconButton(
                    onClick = { isSearchVisible = !isSearchVisible },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text("🔍", fontSize = 18.sp, color = colors.textPrimary)
                }

                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(CircleShape)
                        .background(colors.cardSubtle)
                        .clickable { onNavigateToSettings() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("👤", fontSize = 16.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SEARCH BAR
        AnimatedVisibility(visible = isSearchVisible || searchQuery.isNotEmpty()) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.cardSubtle)
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text("Search documents...", color = colors.textMuted, fontSize = 14.sp)
                            }
                            androidx.compose.foundation.text.BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester)
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text("✕", color = colors.textMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (searchQuery.isBlank() && recentSearches.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recent Searches", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Clear",
                            color = colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                RecentSearchesManager.clearRecentSearches(activity)
                                recentSearches = emptyList()
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(recentSearches) { term ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = colors.surface,
                                border = BorderStroke(1.dp, colors.cardBorder),
                                modifier = Modifier.clickable { searchQuery = term }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("🕒", fontSize = 11.sp)
                                    Text(term, color = colors.textPrimary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        LaunchedEffect(isSearchVisible) {
            if (isSearchVisible) {
                searchFocusRequester.requestFocus()
            }
        }

        // UNIFIED IMPORT ACTION BAR (HIDDEN IN MULTI-SELECT MODE)
        if (!isSelectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { onScanTrigger(activeFolder) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text("+ Scan", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.cardSubtle),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text("Images", fontSize = 11.sp, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("application/pdf", "image/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.cardSubtle),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text("Files", fontSize = 11.sp, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        val availableTags = remember(storedDocs) { storedDocs.flatMap { it.tags }.distinct().sorted() }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                OutlinedButton(
                    onClick = { showSortDropdown = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(20.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                ) {
                    Text("⇅ Sort", color = colors.textPrimary, fontSize = 12.sp)
                }

                DropdownMenu(
                    expanded = showSortDropdown,
                    onDismissRequest = { showSortDropdown = false },
                    modifier = Modifier.background(colors.surface)
                ) {
                    SortOption.values().forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.label,
                                    color = if (selectedSortOption == option) colors.accent else colors.textPrimary,
                                    fontWeight = if (selectedSortOption == option) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                selectedSortOption = option
                                SortManager.saveSortOption(activity, option)
                                showSortDropdown = false
                            }
                        )
                    }
                }
            }

            TextButton(
                onClick = {
                    val next = !isSelectionMode
                    onUpdateSelectionMode(next)
                    if (!next) onUpdateSelectedDocPaths(emptySet())
                }
            ) {
                Text(
                    text = if (isSelectionMode) "Done" else "Select",
                    color = colors.accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(SmartCollection.values()) { col ->
                val isSelected = selectedCollection == col && selectedTagFilter == null
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) colors.accent else colors.cardSubtle,
                    border = BorderStroke(1.dp, if (isSelected) colors.accent else colors.cardBorder),
                    modifier = Modifier.clickable {
                        selectedCollection = col
                        selectedTagFilter = null
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(col.icon, fontSize = 12.sp)
                        Text(
                            text = col.label,
                            color = if (isSelected) Color.White else colors.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            if (availableTags.isNotEmpty()) {
                item {
                    Box(modifier = Modifier.height(20.dp).width(1.dp).background(colors.cardBorder))
                }
                items(availableTags) { tag ->
                    val isSelected = selectedTagFilter == tag
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) colors.accentGreen else colors.cardSubtle,
                        border = BorderStroke(1.dp, if (isSelected) colors.accentGreen else colors.cardBorder),
                        modifier = Modifier.clickable {
                            selectedTagFilter = if (isSelected) null else tag
                        }
                    ) {
                        Text(
                            text = "#$tag",
                            color = if (isSelected) Color.White else colors.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            if (searchQuery.isBlank() && selectedCollection == SmartCollection.ALL && selectedTagFilter == null && !isSelectionMode) {
                if (activeFolder == null) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Folders", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = "+ New",
                                color = colors.accent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    newFolderName = ""
                                    showCreateFolderDialog = true
                                }
                            )
                        }
                    }

                    if (userFolders.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(26.dp))
                                    .clickable {
                                        newFolderName = ""
                                        showCreateFolderDialog = true
                                    },
                                shape = RoundedCornerShape(26.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.surface),
                                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📁", fontSize = 24.sp)
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text("Tap + New to create a folder", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    } else {
                        items(userFolders, key = { it.name }) { folder ->
                            Box {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(26.dp))
                                        .combinedClickable(
                                            onClick = { onSelectFolder(folder.name) },
                                            onLongClick = {
                                                folderForAction = folder
                                                showFolderMenu = true
                                            }
                                        ),
                                    shape = RoundedCornerShape(26.dp),
                                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 20.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("📁", fontSize = 24.sp)
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Text(folder.name, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Text("${folder.docCount}", color = colors.textMuted, fontSize = 13.sp)
                                    }
                                }

                                DropdownMenu(
                                    expanded = showFolderMenu && folderForAction?.name == folder.name,
                                    onDismissRequest = { showFolderMenu = false },
                                    modifier = Modifier.background(colors.surface)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Rename Folder", color = colors.textPrimary) },
                                        leadingIcon = { Text("✏️") },
                                        onClick = {
                                            showFolderMenu = false
                                            renameFolderText = folder.name
                                            showRenameFolderDialog = true
                                        }
                                    )
                                    val docsInFolder = storedDocs.filter { it.parentFolder == folder.name }
                                    val anyUnlockedInFolder = docsInFolder.any { !it.isLocked }
                                    DropdownMenuItem(
                                        text = { Text(if (anyUnlockedInFolder) "Lock Folder" else "Unlock Folder", color = colors.textPrimary) },
                                        leadingIcon = { Text(if (anyUnlockedInFolder) "🔒" else "🔓") },
                                        onClick = {
                                            showFolderMenu = false
                                            if (anyUnlockedInFolder) {
                                                folderForLockGate = folder
                                            } else {
                                                BiometricAuthManager.authenticate(
                                                    activity = activity as FragmentActivity,
                                                    title = "Unlock Folder '${folder.name}'",
                                                    onSuccess = {
                                                        coroutineScope.launch {
                                                            docsInFolder.forEach { doc ->
                                                                DocumentRepository.updateDocLockStatus(activity, doc, false)
                                                            }
                                                            onRefresh()
                                                            Toast.makeText(activity, "Folder '${folder.name}' unlocked", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    onError = { err ->
                                                        Toast.makeText(activity, err, Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete Folder", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Text("🗑️") },
                                        onClick = {
                                            showFolderMenu = false
                                            showDeleteFolderConfirm = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = if (searchQuery.isNotBlank()) "Search Results (${displayDocs.size})"
                    else if (selectedTagFilter != null) "Tagged with #$selectedTagFilter"
                    else if (selectedCollection != SmartCollection.ALL) "${selectedCollection.label} (${displayDocs.size})"
                    else if (activeFolder != null) "Documents in $activeFolder"
                    else "Documents",
                    color = colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (displayDocs.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .border(1.5.dp, colors.accentGreen, RoundedCornerShape(26.dp)),
                        shape = RoundedCornerShape(26.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (searchQuery.isNotBlank()) "No documents matching '$searchQuery'"
                                else if (selectedTagFilter != null) "No documents with tag '#$selectedTagFilter'"
                                else if (selectedCollection != SmartCollection.ALL) "No documents in ${selectedCollection.label}"
                                else if (activeFolder != null) "Folder is empty"
                                else "No documents found",
                                color = colors.textMuted,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                items(displayDocs, key = { it.directory.absolutePath }) { doc ->
                    DocCardWithMenu(
                        activity = activity,
                        doc = doc,
                        userFolders = userFolders,
                        isFavorite = favoritePaths.contains(doc.directory.absolutePath),
                        isBackedUpToDrive = backedUpDocFolderNames.contains(doc.folderName),
                        onBackupStatusChanged = { isBackedUp ->
                            val updated = if (isBackedUp) {
                                backedUpDocFolderNames + doc.folderName
                            } else {
                                backedUpDocFolderNames - doc.folderName
                            }
                            onUpdateBackedUpList(updated)
                        },
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedDocPaths.contains(doc.directory.absolutePath),
                        onToggleSelect = { onToggleDocSelect(doc.directory.absolutePath) },
                        onLongPress = {
                            if (!isSelectionMode) {
                                onUpdateSelectionMode(true)
                                onUpdateSelectedDocPaths(setOf(doc.directory.absolutePath))
                            } else {
                                onToggleDocSelect(doc.directory.absolutePath)
                            }
                        },
                        onToggleFavorite = { onToggleFavorite(doc.directory.absolutePath) },
                        onOpenDoc = { onOpenDoc(doc) },
                        onEditPage = onEditPage,
                        onRefresh = onRefresh,
                        searchSnippet = searchResultsMap[doc.folderName],
                        isUnlockedInSession = sessionUnlockedFolders.contains(doc.folderName),
                        onOpenLocalBackup = onNavigateToSettings
                    )
                }
            }
        }
    }
    }
}



@Composable
fun RenameFolderDialog(
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
        renameValue = renameValue.copy(
            selection = TextRange(0, renameValue.text.length)
        )
    }

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = { Text("Rename Folder", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = renameValue,
                onValueChange = { renameValue = it },
                singleLine = true,
                label = { Text("Folder Name", color = colors.textMuted) },
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
                onClick = { onConfirm(renameValue.text.trim()) },
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
