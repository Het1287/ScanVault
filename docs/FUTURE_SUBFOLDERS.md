# Future Subfolders Feature Archive & Roadmap

This document archives the work-in-progress (WIP) implementation of the nested subfolders feature ("folder inside a folder") for ScanVault. The feature has been completely removed from the active codebase to keep folders strictly single-level for now, preserving stability while capturing the architecture, code snippets, known issues, and recovery instructions for a future update.

---

## 1. How to Recover the WIP Code via Git

Before cleaning the working copy, the uncommitted subfolder implementation can be preserved in a dedicated branch and tag with these exact git commands:

```bash
# From the project root (C:\Users\ASUS\AndroidStudioProjects\OwnScan):
# 1. Create and switch to a temporary archive branch
git checkout -b subfolders-wip

# 2. Stage and commit all WIP changes
git add -A
git commit -m "Archive: subfolders WIP implementation before rollback"

# 3. Create a permanent git tag
git tag subfolders-wip

# 4. Switch back to main to apply the clean single-level codebase
git checkout main
```

If you ever wish to inspect the WIP branch in the future:
```bash
git checkout subfolders-wip
```

---

## 2. Data Model Design & Architecture

### A. Identifier & Relative Path Model
* **Path-based Identifier**: Each folder is represented with a relative path string `id` (e.g. `Finance/2026/Q1`), where `name` is the display name (`Q1`) and `parentFolderId` is the parent path (`Finance/2026`).
* **Root Folders**: Have `parentFolderId = null`, and `id = name`.
* **Depth Limit**: Capped at 5 levels (`getFolderDepth <= 5`) to prevent unbounded nesting, UI overflows, and filesystem path length limits on older Android storage providers.

### B. Cycle Prevention (Move Picker)
* When moving a folder `F`, any folder whose ID equals `F.id` or starts with `"${F.id}/"` is filtered out from the target selection list.
* This prevents cyclic graphs (moving a folder inside itself or inside any of its own descendants).

### C. Recursive Operations
* **Folder Rename**: Renaming `oldPath` to `newPath` recursively finds all child folders and child documents in Room (`documents_metadata.parentFolder`) whose paths start with `oldPath/` and updates their prefix to `newPath/`.
* **Folder Deletion / Trash**: Deleting a folder recursively collects all documents contained in the folder or any descendant subfolder, moves each document to the ScanVault Trash system (`DocumentRepository.moveToTrash`), and then deletes the folder directory tree.
* **Restore & Purge**: Restoring documents moves them back to their saved paths, recreating parent directories if absent.

### D. Backup and Restore Handling
* Manifest metadata in backups captures `parentFolder` as a relative path string.
* On restore, directories are created recursively matching the relative path.

---

## 3. Removed Code Grouped by File

### 1) `app/src/main/java/com/example/ownscan/Models.kt`

```kotlin
// In UserFolder data class:
data class UserFolder(
    val name: String,
    val docCount: Int,
    val parentFolderId: String? = null,
    val id: String = if (parentFolderId != null) "$parentFolderId/$name" else name
)
```

### 2) `app/src/main/java/com/example/ownscan/DocStorage.kt`

```kotlin
// Depth calculation helper
fun getFolderDepth(folderId: String?): Int {
    if (folderId.isNullOrBlank()) return 0
    return folderId.split('/', '\\').filter { it.isNotBlank() }.size
}

// Recursive user folders loader
fun loadUserFolders(context: Context): List<UserFolder> {
    val userFoldersDir = getUserFoldersDir(context)
    val result = mutableListOf<UserFolder>()

    fun scan(currentDir: File, parentId: String?) {
        val children = currentDir.listFiles { f -> f.isDirectory } ?: return
        for (child in children) {
            if (isDocDir(child) || child.name.startsWith(".backup_old_") || child.name.startsWith(".temp_staging_")) {
                continue
            }
            val relPath = child.relativeTo(userFoldersDir).path.replace('\\', '/')
            val docCount = child.listFiles { it.isDirectory && isDocDir(it) }?.size ?: 0
            result.add(UserFolder(name = child.name, docCount = docCount, parentFolderId = parentId, id = relPath))
            scan(child, relPath)
        }
    }

    scan(userFoldersDir, null)
    return result.sortedWith(compareBy({ it.parentFolderId ?: "" }, { it.name.lowercase() }))
}

// Folder creation with parent folder ID and depth validation
fun createUserFolder(context: Context, name: String, parentFolderId: String? = null): Boolean {
    val sanitized = sanitizeName(name)
    if (sanitized.isBlank()) return false
    if (getFolderDepth(parentFolderId) >= 5) {
        return false
    }
    val parentDir = if (!parentFolderId.isNullOrBlank()) {
        File(getUserFoldersDir(context), parentFolderId)
    } else {
        getUserFoldersDir(context)
    }
    if (!parentDir.exists()) parentDir.mkdirs()
    val folder = File(parentDir, sanitized)
    val created = if (!folder.exists()) folder.mkdirs() else false
    if (created) {
        val relPath = folder.relativeTo(getUserFoldersDir(context)).path.replace('\\', '/')
        try {
            val db = AppDatabase.getInstance(context)
            db.folderDao().insertOrUpdate(
                FolderEntity(
                    id = relPath,
                    name = sanitized,
                    parentFolderId = parentFolderId
                )
            )
        } catch (_: Exception) {}
    }
    return created
}

// Subfolder-aware folder rename with prefix remapping in Room DB
fun renameUserFolder(context: Context, oldNameOrPath: String, newName: String): Boolean {
    val sanitized = sanitizeName(newName)
    if (sanitized.isBlank()) return false
    val userFoldersDir = getUserFoldersDir(context)
    val src = File(userFoldersDir, oldNameOrPath)
    if (!src.exists()) return false
    val dest = File(src.parentFile, sanitized)
    if (dest.exists() && dest.absolutePath != src.absolutePath) return false
    val oldRelPath = src.relativeTo(userFoldersDir).path.replace('\\', '/')
    val newRelPath = dest.relativeTo(userFoldersDir).path.replace('\\', '/')
    val renamed = src.renameTo(dest)
    if (renamed) {
        thumbnailMemoryCache.evictAll()
        try {
            val db = AppDatabase.getInstance(context)
            val parentId = if (dest.parentFile == userFoldersDir) null else dest.parentFile?.relativeTo(userFoldersDir)?.path?.replace('\\', '/')
            db.folderDao().deleteById(oldRelPath)
            db.folderDao().insertOrUpdate(
                FolderEntity(
                    id = newRelPath,
                    name = sanitized,
                    parentFolderId = parentId
                )
            )
            val docs = db.documentDao().getAllDocumentsList()
            for (d in docs) {
                if (d.parentFolder == oldRelPath) {
                    db.documentDao().updateParentFolder(d.folderName, newRelPath)
                } else if (d.parentFolder?.startsWith("$oldRelPath/") == true) {
                    val updatedParent = newRelPath + d.parentFolder.removePrefix(oldRelPath)
                    db.documentDao().updateParentFolder(d.folderName, updatedParent)
                }
            }
        } catch (_: Exception) {}
    }
    return renamed
}

// Move folder into another parent folder
fun moveUserFolder(context: Context, sourceFolderId: String, targetParentFolderId: String?): Boolean {
    if (sourceFolderId == targetParentFolderId) return false
    if (targetParentFolderId != null && (targetParentFolderId == sourceFolderId || targetParentFolderId.startsWith("$sourceFolderId/"))) {
        return false // Prevent cyclic hierarchy
    }
    val userFoldersDir = getUserFoldersDir(context)
    val src = File(userFoldersDir, sourceFolderId)
    if (!src.exists()) return false
    val destParent = if (targetParentFolderId != null) File(userFoldersDir, targetParentFolderId) else userFoldersDir
    if (!destParent.exists()) destParent.mkdirs()
    val dest = File(destParent, src.name)
    if (dest.exists()) return false
    val renamed = src.renameTo(dest)
    if (renamed) {
        val newRelPath = dest.relativeTo(userFoldersDir).path.replace('\\', '/')
        thumbnailMemoryCache.evictAll()
        try {
            val db = AppDatabase.getInstance(context)
            db.folderDao().deleteById(sourceFolderId)
            db.folderDao().insertOrUpdate(
                FolderEntity(
                    id = newRelPath,
                    name = dest.name,
                    parentFolderId = targetParentFolderId
                )
            )
            val docs = db.documentDao().getAllDocumentsList()
            for (d in docs) {
                if (d.parentFolder == sourceFolderId) {
                    db.documentDao().updateParentFolder(d.folderName, newRelPath)
                } else if (d.parentFolder?.startsWith("$sourceFolderId/") == true) {
                    val updatedParent = newRelPath + d.parentFolder.removePrefix(sourceFolderId)
                    db.documentDao().updateParentFolder(d.folderName, updatedParent)
                }
            }
        } catch (_: Exception) {}
    }
    return renamed
}

// Recursive document loading in loadAllInAppDocuments
fun scanUserFolderDocs(dir: File) {
    val entries = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".backup_old_") && !f.name.startsWith(".temp_staging_") } ?: return
    for (entry in entries) {
        if (isDocDir(entry)) {
            val parentRelPath = entry.parentFile?.relativeTo(userFoldersDir)?.path?.replace('\\', '/') ?: ""
            parseDocDir(entry, parentRelPath)?.let { docs.add(it) }
        } else {
            scanUserFolderDocs(entry)
        }
    }
}
scanUserFolderDocs(userFoldersDir)
```

### 3) `app/src/main/java/com/example/ownscan/FilesScreen.kt`

```kotlin
// Breadcrumb Row in FilesScreen
if (activeFolder != null) {
    val segments = activeFolder.split('/')
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Root",
            color = colors.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onSelectFolder(null) }
        )
        segments.forEachIndexed { idx, seg ->
            Text(" > ", color = colors.textMuted, fontSize = 12.sp)
            val targetPath = segments.take(idx + 1).joinToString("/")
            val isLast = idx == segments.size - 1
            Text(
                text = seg,
                color = if (isLast) colors.textPrimary else colors.accent,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                fontSize = 13.sp,
                modifier = if (!isLast) Modifier.clickable { onSelectFolder(targetPath) } else Modifier
            )
        }
    }
}

// Subfolders List Rendering inside an open folder
val currentSubfolders = remember(userFolders, activeFolder) {
    if (activeFolder == null) {
        userFolders.filter { it.parentFolderId == null }
    } else {
        userFolders.filter { it.parentFolderId == activeFolder }
    }
}

if (currentSubfolders.isNotEmpty()) {
    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Subfolders", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "+ New Folder",
                color = colors.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    if (getFolderDepth(activeFolder) >= 5) {
                        Toast.makeText(activity, "Maximum folder depth (5 levels) reached", Toast.LENGTH_SHORT).show()
                    } else {
                        newFolderName = ""
                        showCreateFolderDialog = true
                    }
                }
            )
        }
    }
    items(currentSubfolders, key = { it.id }) { folder ->
        // Folder card item rendering with subfolder arrow icon "↳ 📁"
        ...
    }
}
```

### 4) `app/src/main/java/com/example/ownscan/SelectionState.kt`

```kotlin
// In BulkMoveDialog:
val validFolders = remember(userFolders, movingFolderId) {
    if (movingFolderId == null) {
        userFolders
    } else {
        userFolders.filter { f ->
            f.id != movingFolderId && !f.id.startsWith("$movingFolderId/")
        }
    }
}

// Depth indentation in move picker
items(validFolders, key = { it.id }) { folder ->
    val depth = folder.id.count { it == '/' }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
            .clickable {
                onMoveToFolder(folder.id)
                onDismiss()
            },
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (depth > 0) "↳ 📁" else "📁", fontSize = 18.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(folder.name, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                if (depth > 0) {
                    Text(text = folder.id, color = colors.textMuted, fontSize = 11.sp)
                }
            }
        }
    }
}
```

### 5) `app/src/main/java/com/example/ownscan/MainApp.kt`

```kotlin
// Hierarchical lock authentication traversal
onSelectFolder = { folderName ->
    if (folderName == null) {
        activeFolder = null
    } else {
        val segments = folderName.split('/')
        var pathAccum = ""
        var isAnyParentLocked = false
        for (seg in segments) {
            pathAccum = if (pathAccum.isEmpty()) seg else "$pathAccum/$seg"
            val docsInPath = storedDocs.filter { it.parentFolder == pathAccum }
            val allLocked = docsInPath.isNotEmpty() && docsInPath.all { it.isLocked }
            if (allLocked && !sessionUnlockedFolders.contains(pathAccum)) {
                isAnyParentLocked = true
                BiometricAuthManager.authenticate(
                    activity = activity as FragmentActivity,
                    title = "Unlock Folder '$seg'",
                    onSuccess = {
                        sessionUnlockedFolders.add(pathAccum)
                        activeFolder = folderName
                    },
                    onError = { err ->
                        Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                    }
                )
                break
            }
        }
        if (!isAnyParentLocked) {
            activeFolder = folderName
        }
    }
},
onBackFolder = {
    activeFolder = if (activeFolder?.contains('/') == true) activeFolder?.substringBeforeLast('/') else null
}
```

---

## 4. Known Issues & Architecture Challenges in the WIP Implementation

1. **Path Delimiter Inconsistencies**:
   - `sanitizeName()` in `DocStorage.kt` removes `/` and `\` characters. When folder identifiers use `/` as path separators (`Parent/Child`), passing IDs to functions that sanitize names strips delimiters or truncates paths unexpectedly.
2. **Non-Atomic Disk & Database Updates**:
   - Renaming or moving a parent folder requires renaming directories on disk, updating Room database entries for the folder and all its descendant subfolders, and updating `documents_metadata.parentFolder` for all contained documents.
   - If the app process is terminated mid-operation, the filesystem and database diverge, leaving orphaned records or inaccessible files.
3. **UI Overflow on Mobile Screens**:
   - Horizontal breadcrumbs and deeply indented lists in dialogs (`BulkMoveDialog`) do not scale well on small phones, causing clipped folder names and awkward scroll states.
4. **Cloud Backup Mapping Mismatch**:
   - Google Drive appDataFolder backup uses a flat naming model with `parentFolder` stored as a string property in metadata. Deep hierarchy renames and moves do not track changes incrementally in Drive without full re-uploads.
5. **Filesystem Traversal Overhead**:
   - Walking directory trees recursively on every UI recomposition or document scan increases disk I/O and battery consumption as document and folder counts grow.

---

## 5. Clean Single-Level State Transition

When re-architecting subfolders in a future release:
* Use a normalized database-first relational model (`FolderEntity` with foreign keys, UUIDs for IDs, and separate path resolution logic).
* Implement nested folders with transactional disk migrations and proper tree data structures.
* Until then, ScanVault maintains a clean, ultra-reliable single-level folder architecture.
