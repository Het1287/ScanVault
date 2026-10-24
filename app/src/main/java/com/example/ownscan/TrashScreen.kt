package com.example.ownscan

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.fragment.app.FragmentActivity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    activity: Activity,
    onBack: () -> Unit,
    onRefreshMainDocs: () -> Unit
) {
    BackHandler(onBack = onBack)

    val colors = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getInstance(activity).documentDao() }

    val trashedEntities by dao.getTrashedDocuments().collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DocumentRepository.syncTrashedDocsFromDisk(activity)
        }
    }

    // Load local documents asynchronously on Dispatchers.IO via produceState to eliminate main thread disk I/O
    val allLocalDocs by produceState<Map<String, LocalDoc>>(initialValue = emptyMap(), trashedEntities) {
        value = withContext(Dispatchers.IO) {
            loadAllInAppDocuments(activity, includeTrashed = true).associateBy { it.folderName }
        }
    }

    var showEmptyTrashWarning by remember { mutableStateOf(false) }
    var entityToDeleteForever by remember { mutableStateOf<DocumentMetadataEntity?>(null) }

    if (showEmptyTrashWarning) {
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { showEmptyTrashWarning = false },
            title = {
                Text(
                    text = "Empty Trash?",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "All documents in Trash will be permanently deleted from this device. This cannot be undone.",
                    color = colors.textPrimary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEmptyTrashWarning = false
                        coroutineScope.launch {
                            DocumentRepository.emptyTrash(activity)
                            onRefreshMainDocs()
                            Toast.makeText(activity, "Trash emptied", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Empty Trash", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashWarning = false }) {
                    Text("Cancel", color = colors.textMuted)
                }
            }
        )
    }

    if (entityToDeleteForever != null) {
        val targetEntity = entityToDeleteForever!!
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { entityToDeleteForever = null },
            title = {
                Text(
                    text = "Delete Forever?",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Permanently delete '${targetEntity.displayName}'? This cannot be undone.",
                    color = colors.textPrimary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        entityToDeleteForever = null
                        coroutineScope.launch {
                            val local = allLocalDocs[targetEntity.folderName]
                            val docDir = local?.directory ?: DocumentRepository.resolveDocDir(activity, targetEntity.folderName)
                            DocumentRepository.permanentlyDeleteDoc(activity, targetEntity.folderName, docDir)
                            onRefreshMainDocs()
                            Toast.makeText(activity, "'${targetEntity.displayName}' permanently deleted", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Forever", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { entityToDeleteForever = null }) {
                    Text("Cancel", color = colors.textMuted)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Trash", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("${trashedEntities.size} item(s)", color = colors.textMuted, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back" }
                    ) {
                        Text("❮", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (trashedEntities.isNotEmpty()) {
                        TextButton(onClick = {
                            val anyLocked = trashedEntities.any { it.isLocked }
                            if (anyLocked) {
                                BiometricAuthManager.authenticate(
                                    activity = activity as FragmentActivity,
                                    title = "Empty Trash",
                                    subtitle = "Trash contains locked documents. Verify biometric to permanently delete all items.",
                                    onSuccess = {
                                        showEmptyTrashWarning = true
                                    },
                                    onError = { err ->
                                        Toast.makeText(activity, "Authentication failed: $err", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            } else {
                                showEmptyTrashWarning = true
                            }
                        }) {
                            Text("Empty Trash", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colors.cardSubtle)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ℹ️", fontSize = 18.sp, modifier = Modifier.semantics { contentDescription = "Information" })
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Documents in Trash are permanently purged after 30 days. Trashed documents are not synced to Google Drive.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (trashedEntities.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🗑️", fontSize = 48.sp, modifier = Modifier.semantics { contentDescription = "Empty trash" })
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Trash is empty", color = colors.textMuted, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(trashedEntities, key = { it.folderName }) { entity ->
                        val localDoc = allLocalDocs[entity.folderName]
                        val now = System.currentTimeMillis()
                        val trashedTime = entity.trashedDate ?: now
                        val daysPassed = ((now - trashedTime) / (1000 * 60 * 60 * 24)).toInt()
                        val daysRemaining = (30 - daysPassed).coerceAtLeast(0)

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.surface),
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = androidx.compose.ui.graphics.SolidColor(colors.cardBorder)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entity.displayName,
                                            color = colors.textPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "$daysRemaining days remaining until auto-purge",
                                            color = colors.accentAmber,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "Trashed on ${formatDate(trashedTime)}",
                                            color = colors.textMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val docDir = localDoc?.directory ?: DocumentRepository.resolveDocDir(activity, entity.folderName) ?: File(getPrivateDocsDir(activity), entity.folderName)
                                            coroutineScope.launch {
                                                DocumentRepository.restoreFromTrash(activity, docDir, entity.folderName)
                                                onRefreshMainDocs()
                                                Toast.makeText(activity, "'${entity.displayName}' restored!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                                    ) {
                                        Text("Restore", color = Color.White)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            if (entity.isLocked) {
                                                BiometricAuthManager.authenticate(
                                                    activity = activity as FragmentActivity,
                                                    title = "Delete '${entity.displayName}' Permanently",
                                                    subtitle = "Verify biometric or device credential to delete locked document forever",
                                                    onSuccess = {
                                                        entityToDeleteForever = entity
                                                    },
                                                    onError = { err ->
                                                        Toast.makeText(activity, "Authentication failed: $err", Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            } else {
                                                entityToDeleteForever = entity
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        border = ButtonDefaults.outlinedButtonBorder.copy(
                                            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                        )
                                    ) {
                                        Text("Delete Forever", color = MaterialTheme.colorScheme.error)
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
