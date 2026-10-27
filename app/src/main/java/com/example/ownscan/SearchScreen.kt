package com.example.ownscan

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ownscan.ui.icons.AppIcons
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    activity: Activity,
    storedDocs: List<LocalDoc>,
    userFolders: List<UserFolder>,
    favoritePaths: Set<String>,
    backedUpDocFolderNames: Set<String>,
    sessionUnlockedFolders: List<String>,
    onOpenDoc: (LocalDoc) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onEditPage: (File) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    val colors = LocalAppColors.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val isImeVisible = WindowInsets.isImeVisible

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var recentSearches by remember { mutableStateOf(RecentSearchesManager.getRecentSearches(activity)) }
    val searchResultsMap = remember { mutableStateMapOf<String, SearchResultSnippet>() }
    var isSearching by remember { mutableStateOf(false) }

    // Close keyboard first on back press, then exit screen
    BackHandler {
        if (isImeVisible) {
            keyboardController?.hide()
        } else {
            onBack()
        }
    }

    // Auto-focus the search field when screen opens
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // Debounced live search querying
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            isSearching = true
            delay(250)
            val trimmed = searchQuery.trim()
            val snippets = DocumentRepository.searchWithSnippets(activity, trimmed)
            searchResultsMap.clear()
            snippets.forEach { searchResultsMap[it.folderName] = it }
            isSearching = false
        } else {
            searchResultsMap.clear()
            isSearching = false
        }
    }

    // Map search results preserving ranking order
    val matchingDocs = remember(searchResultsMap.keys, storedDocs) {
        val folderOrder = searchResultsMap.keys.toList()
        folderOrder.mapNotNull { folderName ->
            storedDocs.find { it.folderName == folderName }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 22.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Top Row: Back button (44dp box, same style as other pages) + Search pill field
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 44dp back button
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accent)
                    .clickable {
                        keyboardController?.hide()
                        onBack()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = AppIcons.BackChevron,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Search pill text field
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Search",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(10.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search documents",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    keyboardController?.hide()
                                    val trimmed = searchQuery.trim()
                                    if (trimmed.isNotBlank()) {
                                        RecentSearchesManager.addSearchQuery(activity, trimmed)
                                        recentSearches = RecentSearchesManager.getRecentSearches(activity)
                                    }
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    }

                    if (searchQuery.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { searchQuery = "" },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Content Area: Empty Field State vs Typing / Results State
        if (searchQuery.isBlank()) {
            if (recentSearches.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Your recent searches will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent searches",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = {
                            RecentSearchesManager.clearRecentSearches(activity)
                            recentSearches = emptyList()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Clear all",
                            color = colors.accent,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(recentSearches, key = { it }) { term ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    searchQuery = term
                                    RecentSearchesManager.addSearchQuery(activity, term)
                                    recentSearches = RecentSearchesManager.getRecentSearches(activity)
                                    keyboardController?.hide()
                                }
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = term,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        RecentSearchesManager.removeSearchQuery(activity, term)
                                        recentSearches = RecentSearchesManager.getRecentSearches(activity)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove $term",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Typing state: Results
            if (!isSearching && matchingDocs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No results for \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(matchingDocs, key = { it.folderName }) { doc ->
                        DocCardWithMenu(
                            activity = activity,
                            doc = doc,
                            userFolders = userFolders,
                            isFavorite = favoritePaths.contains(doc.directory.absolutePath),
                            isBackedUpToDrive = backedUpDocFolderNames.contains(doc.folderName),
                            onBackupStatusChanged = {},
                            isSelectionMode = false,
                            isSelected = false,
                            onToggleSelect = {},
                            onLongPress = {},
                            onToggleFavorite = { onToggleFavorite(doc.directory.absolutePath) },
                            onOpenDoc = {
                                val trimmed = searchQuery.trim()
                                if (trimmed.isNotBlank()) {
                                    RecentSearchesManager.addSearchQuery(activity, trimmed)
                                    recentSearches = RecentSearchesManager.getRecentSearches(activity)
                                }
                                onOpenDoc(doc)
                            },
                            onEditPage = onEditPage,
                            onRefresh = onRefresh,
                            searchSnippet = searchResultsMap[doc.folderName],
                            isUnlockedInSession = sessionUnlockedFolders.contains(doc.folderName),
                            isGrid = false
                        )
                    }
                }
            }
        }
    }
}
