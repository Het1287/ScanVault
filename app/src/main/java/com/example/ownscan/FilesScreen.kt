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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.example.ownscan.ui.components.AppHeader
import com.example.ownscan.ui.icons.AppIcons
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
    onNavigateToSearch: () -> Unit = {},
    isSelectionMode: Boolean = false,
    selectedDocPaths: Set<String> = emptySet(),
    onUpdateSelectionMode: (Boolean) -> Unit = {},
    onUpdateSelectedDocPaths: (Set<String>) -> Unit = {}
) {
    val colors = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var isSearchVisible by remember { mutableStateOf(false) }
    var selectedCollection by rememberSaveable(
        stateSaver = Saver(
            save = { it.name },
            restore = { name -> runCatching { SmartCollection.valueOf(name) }.getOrDefault(SmartCollection.ALL) }
        )
    ) { mutableStateOf(SmartCollection.ALL) }
    var selectedTagFilter by rememberSaveable { mutableStateOf<String?>(null) }
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

    var isGridView by remember { mutableStateOf(ViewModeManager.isGridView(activity)) }
    var showFolderActionsMenu by remember { mutableStateOf(false) }
    var showFolderSortDialog by remember { mutableStateOf(false) }
    var isFabOpen by remember { mutableStateOf(false) }
    var showImportSubmenu by remember { mutableStateOf(false) }

    val fabRotation by animateFloatAsState(
        targetValue = if (isFabOpen) 45f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "fabRotation"
    )

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

    BackHandler(enabled = isFabOpen || isSelectionMode || activeFolder != null) {
        if (isFabOpen) {
            isFabOpen = false
            showImportSubmenu = false
        } else if (isSelectionMode) {
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

    if (showRenameFolderDialog && (folderForAction != null || activeFolder != null)) {
        val targetFolderName = folderForAction?.name ?: activeFolder!!
        RenameFolderDialog(
            currentName = targetFolderName,
            onDismiss = { showRenameFolderDialog = false },
            onConfirm = { newName ->
                val sanitized = sanitizeName(newName)
                if (sanitized.isNotBlank()) {
                    if (renameUserFolder(activity, targetFolderName, sanitized)) {
                        if (activeFolder == targetFolderName) {
                            onSelectFolder(sanitized)
                        }
                        onRefresh()
                        showRenameFolderDialog = false
                        Toast.makeText(activity, "Renamed to '$sanitized'", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showDeleteFolderConfirm && (folderForAction != null || activeFolder != null)) {
        val targetFolderName = folderForAction?.name ?: activeFolder!!
        val count = storedDocs.count { it.parentFolder == targetFolderName }
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showDeleteFolderConfirm = false },
            title = { Text("Delete Folder '$targetFolderName'?", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
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
                        deleteUserFolder(activity, targetFolderName)
                        if (activeFolder == targetFolderName) onBackFolder()
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

    if (showFolderSortDialog) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showFolderSortDialog = false },
            title = { Text("Sort By", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SortOption.values().forEach { option ->
                        val isSelected = selectedSortOption == option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    selectedSortOption = option
                                    SortManager.saveSortOption(activity, option)
                                    showFolderSortDialog = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = option.label,
                                color = if (isSelected) colors.accent else colors.textPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 14.sp
                            )
                            if (isSelected) {
                                Text(AppIcons.CheckmarkGlyph, color = colors.accent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFolderSortDialog = false }) {
                    Text("Cancel", color = colors.textMuted)
                }
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
            val inFolder = if (activeFolder != null) matched.filter { it.parentFolder == activeFolder } else matched
            SmartCollectionsManager.filterDocs(
                docs = inFolder,
                collection = selectedCollection,
                favoritePaths = favoritePaths,
                backedUpFolderNames = backedUpDocFolderNames,
                activeTag = selectedTagFilter
            )
        } else if (activeFolder != null) {
            val folderDocs = storedDocs.filter { it.parentFolder == activeFolder }
            SmartCollectionsManager.filterDocs(
                docs = folderDocs,
                collection = selectedCollection,
                favoritePaths = favoritePaths,
                backedUpFolderNames = backedUpDocFolderNames,
                activeTag = selectedTagFilter
            )
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
        if (activeFolder != null) {
            val folderDocs = remember(storedDocs, activeFolder) {
                storedDocs.filter { it.parentFolder == activeFolder }
            }
            val displayFolderDocs = remember(
                folderDocs,
                searchQuery,
                selectedCollection,
                selectedTagFilter,
                favoritePaths,
                backedUpDocFolderNames,
                selectedSortOption,
                searchResultsMap.size
            ) {
                val categoryFiltered = SmartCollectionsManager.filterDocs(
                    docs = folderDocs,
                    collection = selectedCollection,
                    favoritePaths = favoritePaths,
                    backedUpFolderNames = backedUpDocFolderNames,
                    activeTag = selectedTagFilter
                )
                val filtered = if (searchQuery.isNotBlank()) {
                    val trimmed = searchQuery.trim().lowercase(Locale.ROOT)
                    categoryFiltered.filter { doc ->
                        doc.displayName.lowercase(Locale.ROOT).contains(trimmed) ||
                        searchResultsMap.containsKey(doc.folderName)
                    }
                } else {
                    categoryFiltered
                }
                SortManager.sortDocs(filtered, selectedSortOption)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 22.dp)
            ) {
                Spacer(modifier = Modifier.height(10.dp))

                if (isSelectionMode) {
                    MultiSelectTopBar(
                        selectedCount = selectedDocPaths.size,
                        totalCount = displayFolderDocs.size,
                        onSelectAll = {
                            val next = if (selectedDocPaths.size == displayFolderDocs.size) {
                                emptySet()
                            } else {
                                displayFolderDocs.map { it.directory.absolutePath }.toSet()
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
                } else {
                    // TOP BAR
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Back button (44x44dp Box, content centered, blue rounded square with chevron)
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.accent)
                                .clickable(onClick = onBackFolder),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = AppIcons.BackChevron,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        // Folder name title (single line, ellipsis if long, takes remaining space)
                        Text(
                            text = activeFolder,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        // Search and 3-dot buttons with 8dp gap
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isSyncInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = colors.accent,
                                    strokeWidth = 2.dp
                                )
                            }

                            // Search button (44x44dp Box, content centered)
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable { isSearchVisible = !isSearchVisible },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = AppIcons.Search,
                                    fontSize = 20.sp,
                                    color = colors.textPrimary
                                )
                            }

                            // 3-dot menu button (44x44dp Box, content centered)
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable { showFolderActionsMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = AppIcons.MoreVert,
                                    contentDescription = "Folder options",
                                    tint = colors.textPrimary,
                                    modifier = Modifier.size(24.dp)
                                )

                                DropdownMenu(
                                    expanded = showFolderActionsMenu,
                                    onDismissRequest = { showFolderActionsMenu = false },
                                    modifier = Modifier.background(colors.surface)
                                ) {
                                    // 1. View mode (list / grid)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = if (isGridView) "View as list" else "View as grid",
                                                color = colors.textPrimary,
                                                fontSize = 14.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isGridView) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                                                contentDescription = "Change view",
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            val next = !isGridView
                                            isGridView = next
                                            ViewModeManager.saveGridView(activity, next)
                                            showFolderActionsMenu = false
                                        }
                                    )

                                    // 2. Sort by
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Sort by",
                                                color = colors.textPrimary,
                                                fontSize = 14.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Text("⇅", fontSize = 16.sp)
                                        },
                                        onClick = {
                                            showFolderActionsMenu = false
                                            showFolderSortDialog = true
                                        }
                                    )

                                    // 3. Rename
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Rename",
                                                color = colors.textPrimary,
                                                fontSize = 14.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = AppIcons.RenameVector,
                                                    contentDescription = "Rename",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        },
                                        onClick = {
                                            showFolderActionsMenu = false
                                            renameFolderText = activeFolder
                                            showRenameFolderDialog = true
                                        }
                                    )

                                    // 4. Select
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Select",
                                                color = colors.textPrimary,
                                                fontSize = 14.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Text(AppIcons.CheckmarkGlyph, fontSize = 16.sp)
                                        },
                                        onClick = {
                                            showFolderActionsMenu = false
                                            onUpdateSelectionMode(true)
                                        }
                                    )

                                    // 5. Lock folder / Unlock folder
                                    val docsInThisFolder = storedDocs.filter { it.parentFolder == activeFolder }
                                    val anyUnlocked = docsInThisFolder.any { !it.isLocked }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = if (anyUnlocked) "Lock folder" else "Unlock folder",
                                                color = colors.textPrimary,
                                                fontSize = 14.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = if (anyUnlocked) AppIcons.LockVector else AppIcons.UnlockVector,
                                                    contentDescription = if (anyUnlocked) "Lock folder" else "Unlock folder",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        },
                                        onClick = {
                                            showFolderActionsMenu = false
                                            if (anyUnlocked) {
                                                folderForLockGate = userFolders.find { it.name == activeFolder }
                                                    ?: UserFolder(activeFolder, docsInThisFolder.size)
                                            } else {
                                                BiometricAuthManager.authenticate(
                                                    activity = activity as FragmentActivity,
                                                    title = "Unlock Folder '$activeFolder'",
                                                    onSuccess = {
                                                        coroutineScope.launch {
                                                            docsInThisFolder.forEach { doc ->
                                                                DocumentRepository.updateDocLockStatus(activity, doc, false)
                                                            }
                                                            onRefresh()
                                                            Toast.makeText(activity, "Folder '$activeFolder' unlocked", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    onError = { err ->
                                                        Toast.makeText(activity, err, Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            }
                                        }
                                    )

                                    // Divider before Delete folder
                                    HorizontalDivider(
                                        color = colors.divider,
                                        thickness = 0.8.dp,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )

                                    // 6. Delete folder
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Delete folder",
                                                color = MaterialTheme.colorScheme.error,
                                                fontSize = 14.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = AppIcons.DeleteVector,
                                                    contentDescription = "Delete folder",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        },
                                        onClick = {
                                            showFolderActionsMenu = false
                                            showDeleteFolderConfirm = true
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }

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
                                        Text("Search in $activeFolder...", color = colors.textMuted, fontSize = 14.sp)
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
                                        Text(AppIcons.CloseGlyph, color = colors.textMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }

                // EMPTY STATE OR DOCUMENT LIST/GRID
                if (displayFolderDocs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 88.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (searchQuery.isNotBlank()) {
                                Text(
                                    text = AppIcons.Search,
                                    fontSize = 56.sp
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(56.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = AppIcons.FolderVector,
                                        contentDescription = null,
                                        modifier = Modifier.size(56.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "No documents matching '$searchQuery'"
                                else if (selectedTagFilter != null) "No documents with tag '#$selectedTagFilter'"
                                else if (selectedCollection != SmartCollection.ALL) SmartCollectionsManager.getEmptyCategoryMessage(selectedCollection)
                                else "This folder is empty",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "Try a different search term" else "Tap + to scan or import",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMuted
                            )
                        }
                    }
                } else if (isGridView) {
                    val configuration = LocalConfiguration.current
                    val isWideScreen = configuration.screenWidthDp >= 600
                    val folderGridCells = if (isWideScreen) GridCells.Adaptive(minSize = 180.dp) else GridCells.Fixed(2)
                    LazyVerticalGrid(
                        columns = folderGridCells,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 88.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(displayFolderDocs, key = { it.directory.absolutePath }) { doc ->
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
                                onOpenLocalBackup = onNavigateToSettings,
                                isGrid = true
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(displayFolderDocs, key = { it.directory.absolutePath }) { doc ->
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
                                onOpenLocalBackup = onNavigateToSettings,
                                isGrid = false
                            )
                        }
                    }
                }
            }
        } else {
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

        if (!isSelectionMode) {
            AppHeader(
                title = "All Files",
                isProcessing = isSyncInProgress,
                onSearchClick = onNavigateToSearch
            )

            Spacer(modifier = Modifier.height(12.dp))
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
        var showCategoryDropdown by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category Dropdown Chip
            Box(modifier = Modifier.weight(1f, fill = false)) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selectedCollection != SmartCollection.ALL || selectedTagFilter != null) colors.accent else colors.cardSubtle,
                    border = BorderStroke(
                        1.dp,
                        if (selectedCollection != SmartCollection.ALL || selectedTagFilter != null) colors.accent else colors.cardBorder
                    ),
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { showCategoryDropdown = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (selectedTagFilter != null) {
                            Text("#", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                text = selectedTagFilter!!,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            when (selectedCollection) {
                                SmartCollection.LOCKED -> {
                                    Icon(
                                        painter = AppIcons.LockVector,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = if (selectedCollection != SmartCollection.ALL) Color.White else MaterialTheme.colorScheme.primary
                                    )
                                }
                                SmartCollection.FAVORITES -> {
                                    Icon(
                                        painter = AppIcons.FavoriteVector,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = if (selectedCollection != SmartCollection.ALL) Color.White else colors.accentAmber
                                    )
                                }
                                SmartCollection.EXPIRING_SOON -> {
                                    Icon(
                                        painter = AppIcons.TimerVector,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = if (selectedCollection != SmartCollection.ALL) Color.White else MaterialTheme.colorScheme.primary
                                    )
                                }
                                SmartCollection.BACKED_UP, SmartCollection.NOT_BACKED_UP -> {
                                    Icon(
                                        painter = AppIcons.BackupDriveVector,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = if (selectedCollection != SmartCollection.ALL) Color.White else MaterialTheme.colorScheme.primary
                                    )
                                }
                                else -> {
                                    Text(selectedCollection.icon, fontSize = 12.sp)
                                }
                            }
                            Text(
                                text = selectedCollection.label,
                                color = if (selectedCollection != SmartCollection.ALL) Color.White else colors.textPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = if (showCategoryDropdown) "▴" else "▾",
                            color = if (selectedCollection != SmartCollection.ALL || selectedTagFilter != null) Color.White else colors.textMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                DropdownMenu(
                    expanded = showCategoryDropdown,
                    onDismissRequest = { showCategoryDropdown = false },
                    modifier = Modifier.background(colors.surface)
                ) {
                    SmartCollection.values().forEach { col ->
                        val isSelected = selectedCollection == col && selectedTagFilter == null
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = col.label,
                                        color = if (isSelected) colors.accent else colors.textPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = AppIcons.CheckmarkGlyph,
                                            color = colors.accent,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            },
                            leadingIcon = {
                                when (col) {
                                    SmartCollection.LOCKED -> {
                                        Icon(
                                            painter = AppIcons.LockVector,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isSelected) colors.accent else colors.textPrimary
                                        )
                                    }
                                    SmartCollection.FAVORITES -> {
                                        Icon(
                                            painter = AppIcons.FavoriteVector,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isSelected) colors.accent else colors.accentAmber
                                        )
                                    }
                                    SmartCollection.EXPIRING_SOON -> {
                                        Icon(
                                            painter = AppIcons.TimerVector,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isSelected) colors.accent else colors.textPrimary
                                        )
                                    }
                                    SmartCollection.BACKED_UP -> {
                                        Icon(
                                            painter = AppIcons.BackupDriveVector,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isSelected) colors.accent else colors.accentGreen
                                        )
                                    }
                                    SmartCollection.NOT_BACKED_UP -> {
                                        Icon(
                                            painter = AppIcons.BackupDriveVector,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isSelected) colors.accent else colors.textMuted
                                        )
                                    }
                                    else -> {
                                        Text(col.icon, fontSize = 14.sp)
                                    }
                                }
                            },
                            onClick = {
                                selectedCollection = col
                                selectedTagFilter = null
                                showCategoryDropdown = false
                            }
                        )
                    }

                    if (availableTags.isNotEmpty()) {
                        HorizontalDivider(
                            color = colors.divider,
                            thickness = 0.8.dp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        availableTags.forEach { tag ->
                            val isSelected = selectedTagFilter == tag
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "#$tag",
                                            color = if (isSelected) colors.accentGreen else colors.textPrimary,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = AppIcons.CheckmarkGlyph,
                                                color = colors.accentGreen,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Text(AppIcons.Tag, fontSize = 14.sp)
                                },
                                onClick = {
                                    selectedTagFilter = if (isSelected) null else tag
                                    showCategoryDropdown = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Right Actions: Sort, View, Select
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Sort
                Box {
                    OutlinedButton(
                        onClick = { showSortDropdown = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(20.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(colors.cardBorder)),
                        modifier = Modifier
                            .height(34.dp)
                            .semantics { contentDescription = "Sort" }
                    ) {
                        Text("⇅ Sort", color = colors.textPrimary, fontSize = 12.sp)
                    }

                    DropdownMenu(
                        expanded = showSortDropdown,
                        onDismissRequest = { showSortDropdown = false },
                        modifier = Modifier.background(colors.surface)
                    ) {
                        SortOption.values().forEach { option ->
                            val isSelected = selectedSortOption == option
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = option.label,
                                            color = if (isSelected) colors.accent else colors.textPrimary,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(AppIcons.CheckmarkGlyph, color = colors.accent, fontWeight = FontWeight.Bold)
                                        }
                                    }
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

                // View Toggle (between Sort and Select)
                OutlinedButton(
                    onClick = {
                        val next = !isGridView
                        isGridView = next
                        ViewModeManager.saveGridView(activity, next)
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(20.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(colors.cardBorder)),
                    modifier = Modifier
                        .height(34.dp)
                        .semantics { contentDescription = "Change view" }
                ) {
                    Icon(
                        imageVector = if (isGridView) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                        contentDescription = "Change view",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Select
                TextButton(
                    onClick = {
                        val next = !isSelectionMode
                        onUpdateSelectionMode(next)
                        if (!next) onUpdateSelectedDocPaths(emptySet())
                    },
                    modifier = Modifier
                        .height(34.dp)
                        .semantics { contentDescription = "Select" }
                ) {
                    Text(
                        text = if (isSelectionMode) "Done" else "Select",
                        color = colors.accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (isGridView) {
            val configuration = LocalConfiguration.current
            val isWideScreen = configuration.screenWidthDp >= 600
            val rootGridCells = if (isWideScreen) GridCells.Adaptive(minSize = 180.dp) else GridCells.Fixed(2)
            LazyVerticalGrid(
                columns = rootGridCells,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (searchQuery.isBlank() && selectedCollection == SmartCollection.ALL && selectedTagFilter == null && !isSelectionMode) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                        item(span = { GridItemSpan(maxLineSpan) }) {
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
                                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(colors.cardBorder))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = AppIcons.FolderVector,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text("Tap + New to create a folder", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    } else {
                        items(userFolders, key = { it.name }, span = { GridItemSpan(maxLineSpan) }) { folder ->
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
                                    border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(colors.cardBorder))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 20.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier.size(24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = AppIcons.FolderVector,
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Text(folder.name, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        val docsInFolder = storedDocs.filter { it.parentFolder == folder.name }
                                        val isFolderLocked = docsInFolder.isNotEmpty() && docsInFolder.none { !it.isLocked }
                                        if (isFolderLocked) {
                                            Box(
                                                modifier = Modifier.size(16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    painter = AppIcons.LockVector,
                                                    contentDescription = "Locked folder",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
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
                                        leadingIcon = {
                                            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = AppIcons.RenameVector,
                                                    contentDescription = "Rename Folder",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        },
                                        onClick = {
                                            showFolderMenu = false
                                            renameFolderText = folder.name
                                            showRenameFolderDialog = true
                                        }
                                    )
                                    val docsInFolder = storedDocs.filter { it.parentFolder == folder.name }
                                    val anyUnlockedInFolder = docsInFolder.any { !it.isLocked }
                                    DropdownMenuItem(
                                        text = { Text(if (anyUnlockedInFolder) "Lock folder" else "Unlock folder", color = colors.textPrimary) },
                                        leadingIcon = {
                                            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = if (anyUnlockedInFolder) AppIcons.LockVector else AppIcons.UnlockVector,
                                                    contentDescription = if (anyUnlockedInFolder) "Lock folder" else "Unlock folder",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        },
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
                                        leadingIcon = {
                                            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = AppIcons.DeleteVector,
                                                    contentDescription = "Delete Folder",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        },
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

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "Search Results (${displayDocs.size})"
                        else if (selectedTagFilter != null) "Tagged with #$selectedTagFilter"
                        else if (selectedCollection != SmartCollection.ALL) "${selectedCollection.label} (${displayDocs.size})"
                        else "Documents",
                        color = colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (displayDocs.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                                    else SmartCollectionsManager.getEmptyCategoryMessage(selectedCollection),
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
                            onOpenLocalBackup = onNavigateToSettings,
                            isGrid = true
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

            if (searchQuery.isBlank() && selectedCollection == SmartCollection.ALL && selectedTagFilter == null && !isSelectionMode) {
                if (true) {
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
                                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = AppIcons.FolderVector,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
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
                                        Box(
                                            modifier = Modifier.size(24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = AppIcons.FolderVector,
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Text(folder.name, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        val docsInFolder = storedDocs.filter { it.parentFolder == folder.name }
                                        val isFolderLocked = docsInFolder.isNotEmpty() && docsInFolder.none { !it.isLocked }
                                        if (isFolderLocked) {
                                            Box(
                                                modifier = Modifier.size(16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    painter = AppIcons.LockVector,
                                                    contentDescription = "Locked folder",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary
 )
 }
 Spacer(modifier = Modifier.width(8.dp))
 }
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
                                        leadingIcon = {
                                            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = AppIcons.RenameVector,
                                                    contentDescription = "Rename Folder",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        },
                                        onClick = {
                                            showFolderMenu = false
                                            renameFolderText = folder.name
                                            showRenameFolderDialog = true
                                        }
                                    )
                                    val docsInFolder = storedDocs.filter { it.parentFolder == folder.name }
                                    val anyUnlockedInFolder = docsInFolder.any { !it.isLocked }
                                    DropdownMenuItem(
                                        text = { Text(if (anyUnlockedInFolder) "Lock folder" else "Unlock folder", color = colors.textPrimary) },
                                        leadingIcon = {
                                            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = if (anyUnlockedInFolder) AppIcons.LockVector else AppIcons.UnlockVector,
                                                    contentDescription = if (anyUnlockedInFolder) "Lock folder" else "Unlock folder",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
 },
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
                                        leadingIcon = {
                                            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = AppIcons.DeleteVector,
                                                    contentDescription = "Delete Folder",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        },
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
                                else SmartCollectionsManager.getEmptyCategoryMessage(selectedCollection),
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
        val isAnyDialogOpen = showRenameFolderDialog ||
            showDeleteFolderConfirm ||
            folderForLockGate != null ||
            showImageDocNameDialog ||
            showFolderSortDialog ||
            showCreateFolderDialog

        val showFab = activeFolder != null && !isSelectionMode && !isAnyDialogOpen

        if (showFab) {
            AnimatedVisibility(
                visible = isFabOpen,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                isFabOpen = false
                                showImportSubmenu = false
                            }
                        )
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AnimatedVisibility(
                    visible = isFabOpen,
                    enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 2 },
                    exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 2 }
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (!showImportSubmenu) {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = colors.surface,
                                shadowElevation = 6.dp,
                                border = BorderStroke(1.dp, colors.cardBorder),
                                modifier = Modifier.clickable {
                                    isFabOpen = false
                                    onScanTrigger(activeFolder)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = AppIcons.Camera,
                                        contentDescription = "Scan",
                                        tint = colors.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Scan",
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = colors.surface,
                                shadowElevation = 6.dp,
                                border = BorderStroke(1.dp, colors.cardBorder),
                                modifier = Modifier.clickable {
                                    showImportSubmenu = true
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(AppIcons.Import, fontSize = 16.sp)
                                    Text(
                                        text = "Import",
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "›",
                                        color = colors.textMuted,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = colors.surface,
                                shadowElevation = 6.dp,
                                border = BorderStroke(1.dp, colors.cardBorder),
                                modifier = Modifier.clickable {
                                    isFabOpen = false
                                    showImportSubmenu = false
                                    filePickerLauncher.launch(arrayOf("application/pdf", "image/*"))
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(AppIcons.FilePdf, fontSize = 16.sp)
                                    Text(
                                        text = "Import files",
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = colors.surface,
                                shadowElevation = 6.dp,
                                border = BorderStroke(1.dp, colors.cardBorder),
                                modifier = Modifier.clickable {
                                    isFabOpen = false
                                    showImportSubmenu = false
                                    imagePickerLauncher.launch("image/*")
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = AppIcons.AddPhoto,
                                        contentDescription = "Import image",
                                        tint = colors.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Import image",
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }

                FloatingActionButton(
                    onClick = {
                        if (isFabOpen) {
                            isFabOpen = false
                            showImportSubmenu = false
                        } else {
                            isFabOpen = true
                            showImportSubmenu = false
                        }
                    },
                    containerColor = colors.accent,
                    contentColor = Color.White,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = if (isFabOpen) "Close" else "Add",
                        modifier = Modifier
                            .size(26.dp)
                            .graphicsLayer { rotationZ = fabRotation }
                    )
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
