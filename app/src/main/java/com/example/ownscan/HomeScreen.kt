package com.example.ownscan

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

// -------------------------------------------------------------
// SCREEN 0: HOME
// -------------------------------------------------------------

@Composable
fun HomeScreenDesign(
    activity: Activity,
    storedDocs: List<LocalDoc>,
    userFolders: List<UserFolder>,
    favoritePaths: Set<String>,
    backedUpDocFolderNames: Set<String>,
    sessionUnlockedFolders: List<String> = emptyList(),
    onOpenDoc: (LocalDoc) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onEditPage: (File) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    isSelectionMode: Boolean = false,
    selectedDocPaths: Set<String> = emptySet(),
    onUpdateSelectionMode: (Boolean) -> Unit = {},
    onUpdateSelectedDocPaths: (Set<String>) -> Unit = {}
) {
    val colors = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()
    val recentPaths = remember(storedDocs) { getRecentlyOpenedList(activity) }
    val recentlyOpenedDocs = remember(storedDocs, recentPaths) {
        val mapped = recentPaths.mapNotNull { path -> storedDocs.find { it.directory.absolutePath == path } }
        if (mapped.isNotEmpty()) mapped else storedDocs.take(5)
    }

    val onToggleDocSelect = { path: String ->
        val next = if (selectedDocPaths.contains(path)) selectedDocPaths - path else selectedDocPaths + path
        onUpdateSelectedDocPaths(next)
        if (next.isEmpty()) onUpdateSelectionMode(false)
    }

    val totalDocs = storedDocs.size
    val totalPages = storedDocs.sumOf { it.pageCount }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 22.dp, end = 22.dp, top = 10.dp, bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            if (isSelectionMode) {
                MultiSelectTopBar(
                    selectedCount = selectedDocPaths.size,
                    totalCount = recentlyOpenedDocs.size,
                    onSelectAll = {
                        val next = if (selectedDocPaths.size == recentlyOpenedDocs.size) {
                            emptySet()
                        } else {
                            recentlyOpenedDocs.map { it.directory.absolutePath }.toSet()
                        }
                        onUpdateSelectedDocPaths(next)
                        if (next.isEmpty()) onUpdateSelectionMode(false)
                    },
                    onClearSelection = {
                        onUpdateSelectedDocPaths(emptySet())
                        onUpdateSelectionMode(false)
                    }
                )
            } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Home",
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    IconButton(
                        onClick = onNavigateToSearch,
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
            }
        }

        item {
            Column {
                Text(
                    text = "ScanVault",
                    color = colors.textPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Your docs your control",
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = colors.accent)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp, horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$totalDocs",
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Total docs",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$totalPages",
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Total pages",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Recently opened",
                color = colors.textPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (recentlyOpenedDocs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder))
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No documents scanned yet\nTap 📷 below to scan",
                            color = colors.textMuted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(recentlyOpenedDocs, key = { it.directory.absolutePath }) { doc ->
                DocCardWithMenu(
                    activity = activity,
                    doc = doc,
                    userFolders = userFolders,
                    isFavorite = favoritePaths.contains(doc.directory.absolutePath),
                    isBackedUpToDrive = backedUpDocFolderNames.contains(doc.folderName),
                    onBackupStatusChanged = { /* parent handled */ },
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
                    isUnlockedInSession = sessionUnlockedFolders.contains(doc.folderName),
                    onOpenLocalBackup = onNavigateToSettings
                )
            }
        }
    }
    }
}

