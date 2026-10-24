package com.example.ownscan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import java.io.File


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// -------------------------------------------------------------
// DOCUMENT TAG EDIT DIALOG (SINGLE DOC)
// -------------------------------------------------------------

@Composable
fun DocTagEditDialog(
    doc: LocalDoc,
    colors: AppColors,
    onDismiss: () -> Unit,
    onSaveTags: (List<String>) -> Unit
) {
    var tagInput by remember { mutableStateOf("") }
    val currentTags = remember { mutableStateListOf<String>().apply { addAll(doc.tags) } }

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏷️", fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Tags for '${doc.displayName}'",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Add tags to categorize and easily find this document:",
                    color = colors.textMuted,
                    fontSize = 13.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        placeholder = { Text("e.g. Receipt, Work", color = colors.textMuted, fontSize = 13.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.cardBorder
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = {
                            val cleanTags = tagInput.split(",")
                                .map { it.trim().removePrefix("#") }
                                .filter { it.isNotEmpty() }
                            for (t in cleanTags) {
                                if (!currentTags.contains(t)) {
                                    currentTags.add(t)
                                }
                            }
                            tagInput = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("Add", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                if (currentTags.isNotEmpty()) {
                    Text(
                        text = "Current Tags (tap ✕ to remove):",
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        currentTags.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = colors.accent.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                                ) {
                                    Text(
                                        text = "#$tag",
                                        color = colors.accent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { currentTags.remove(tag) },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Text("✕", fontSize = 10.sp, color = colors.accent)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = "No tags added yet.",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveTags(currentTags.toList()) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
            ) {
                Text("Save Tags", color = Color.White)
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
// HIGHLIGHTED SNIPPET TEXT (FTS MATCH HIGHLIGHTING)
// -------------------------------------------------------------

@Composable
fun HighlightedSnippetText(
    snippet: String,
    query: String,
    textColor: Color,
    highlightColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp
) {
    val annotated = remember(snippet, query) {
        buildAnnotatedString {
            if (query.isBlank()) {
                append(snippet)
                return@buildAnnotatedString
            }
            var currentIndex = 0
            val lowerSnippet = snippet.lowercase(Locale.ROOT)
            val lowerQuery = query.lowercase(Locale.ROOT)
            val qLen = query.length

            while (currentIndex < snippet.length) {
                val matchIdx = lowerSnippet.indexOf(lowerQuery, currentIndex)
                if (matchIdx == -1) {
                    append(snippet.substring(currentIndex))
                    break
                }
                if (matchIdx > currentIndex) {
                    append(snippet.substring(currentIndex, matchIdx))
                }
                withStyle(
                    SpanStyle(
                        color = Color.White,
                        background = highlightColor,
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append(snippet.substring(matchIdx, matchIdx + qLen))
                }
                currentIndex = matchIdx + qLen
            }
        }
    }

    Text(
        text = annotated,
        color = textColor,
        fontSize = fontSize,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

// -------------------------------------------------------------
// MOVE / COPY PHOTOS DIALOG
// -------------------------------------------------------------

enum class PageTransferMode {
    MOVE, COPY
}

@Composable
fun MoveCopyPagesDialog(
    mode: PageTransferMode,
    selectedPages: List<File>,
    currentDoc: LocalDoc,
    availableDocs: List<LocalDoc>,
    onDismiss: () -> Unit,
    onTransferToNewDoc: (newDocName: String) -> Unit,
    onTransferToExistingDoc: (targetDoc: LocalDoc) -> Unit
) {
    val colors = LocalAppColors.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var newDocTitle by remember {
        mutableStateOf("${currentDoc.displayName}_${if (mode == PageTransferMode.MOVE) "Moved" else "Copy"}")
    }

    val actionWord = if (mode == PageTransferMode.MOVE) "Move" else "Copy"
    val eligibleDocs = remember(availableDocs, searchQuery, mode) {
        availableDocs
            .filter { doc ->
                (mode == PageTransferMode.COPY || doc.folderName != currentDoc.folderName) &&
                (searchQuery.isBlank() || doc.displayName.contains(searchQuery, ignoreCase = true))
            }
    }

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "$actionWord ${selectedPages.size} Photo${if (selectedPages.size > 1) "s" else ""}",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = colors.cardBackground,
                    contentColor = colors.accent
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("To Existing File", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("To New File", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    )
                }

                if (selectedTab == 0) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search files...", fontSize = 12.sp, color = colors.textMuted) },
                        singleLine = true
                    )

                    if (eligibleDocs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (searchQuery.isBlank()) "No other files available" else "No matching files found",
                                color = colors.textMuted,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(eligibleDocs, key = { it.folderName }) { targetDoc ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = colors.cardBackground,
                                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onTransferToExistingDoc(targetDoc)
                                            onDismiss()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(if (targetDoc.isLocked) "🔒" else "📄", fontSize = 18.sp)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = targetDoc.displayName,
                                                color = colors.textPrimary,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = "${targetDoc.pageCount} page(s)",
                                                color = colors.textMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                        Text(
                                            text = "$actionWord Here →",
                                            color = colors.accent,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Create a new document containing the ${selectedPages.size} selected photo(s):",
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = newDocTitle,
                            onValueChange = { newDocTitle = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Document name", color = colors.textMuted) }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                onTransferToNewDoc(newDocTitle)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("$actionWord to New File", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMuted)
            }
        }
    )
}

