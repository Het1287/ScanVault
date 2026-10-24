package com.example.ownscan

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class DocDetailUiState(
    val title: String = "",
    val folderName: String = "",
    val isLocked: Boolean = false,
    val expiryDate: Long? = null,
    val pages: List<File> = emptyList(),
    val selectedPageNames: Set<String> = emptySet(),
    val isSharing: Boolean = false,
    val shareProgressMessage: String? = null
) {
    val pageCount: Int get() = pages.size
    val isSelectionMode: Boolean get() = selectedPageNames.isNotEmpty()
    val selectedCount: Int get() = selectedPageNames.size
    val selectedPages: List<File> get() = pages.filter { it.name in selectedPageNames }
}

data class LastDeletedInfo(
    val trashedDir: File,
    val deletedPageNames: List<String>,
    val docFolder: File,
    val isLocked: Boolean
)

class DocDetailViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val KEY_SELECTED_PAGES = "doc_detail_selected_pages"

    private val _uiState = MutableStateFlow(
        DocDetailUiState(
            selectedPageNames = savedStateHandle.get<List<String>>(KEY_SELECTED_PAGES)?.toSet() ?: emptySet()
        )
    )
    val uiState: StateFlow<DocDetailUiState> = _uiState.asStateFlow()

    private var lastDeletedInfo: LastDeletedInfo? = null

    fun loadPages(context: Context, doc: LocalDoc) {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = if (doc.isLocked) {
                val sessionDir = LockedDocCryptoManager.getUnlockedSessionDir(context, doc.folderName)
                val rawPages = sessionDir?.listFiles { f ->
                    val ext = f.extension.lowercase(Locale.ROOT)
                    f.isFile && (ext == "jpg" || ext == "jpeg" || ext == "png")
                }?.filter { it.exists() && it.length() > 0 }?.distinctBy { it.name } ?: emptyList()

                val orderNames = readPageOrder(doc.directory).distinct()
                if (orderNames.isNotEmpty()) {
                    val fileMap = rawPages.associateBy { it.name }
                    val ordered = orderNames.mapNotNull {
                        fileMap[it] ?: fileMap[it.removeSuffix(".enc")]
                    }.distinctBy { it.name }.toMutableList()
                    rawPages.forEach { if (!ordered.contains(it)) ordered.add(it) }
                    ordered
                } else {
                    rawPages.sortedBy { it.name }
                }
            } else {
                val dir = doc.directory
                val rawPages = dir.listFiles { f ->
                    val ext = f.extension.lowercase(Locale.ROOT)
                    f.isFile && (ext == "jpg" || ext == "jpeg" || ext == "png")
                }?.filter { it.exists() && it.length() > 0 }?.distinctBy { it.name } ?: emptyList()

                val orderNames = readPageOrder(dir).distinct()
                if (orderNames.isNotEmpty()) {
                    val fileMap = rawPages.associateBy { it.name }
                    val ordered = orderNames.mapNotNull {
                        fileMap[it] ?: fileMap["$it.enc"] ?: fileMap[it.removeSuffix(".enc")]
                    }.distinctBy { it.name }.toMutableList()
                    rawPages.forEach { if (!ordered.contains(it)) ordered.add(it) }
                    ordered
                } else {
                    rawPages.sortedBy { it.name }
                }
            }

            withContext(Dispatchers.Main) {
                _uiState.update { current ->
                    val existingNames = loaded.map { it.name }.toSet()
                    val validSelection = current.selectedPageNames.filter { it in existingNames }.toSet()
                    savedStateHandle[KEY_SELECTED_PAGES] = ArrayList(validSelection)
                    current.copy(
                        title = doc.displayName,
                        folderName = doc.folderName,
                        isLocked = doc.isLocked,
                        expiryDate = doc.expiryDate,
                        pages = loaded,
                        selectedPageNames = validSelection
                    )
                }
            }
        }
    }

    fun toggleSelect(pageName: String) {
        _uiState.update { current ->
            val newSet = if (current.selectedPageNames.contains(pageName)) {
                current.selectedPageNames - pageName
            } else {
                current.selectedPageNames + pageName
            }
            savedStateHandle[KEY_SELECTED_PAGES] = ArrayList(newSet)
            current.copy(selectedPageNames = newSet)
        }
    }

    fun selectPage(pageName: String) {
        _uiState.update { current ->
            val newSet = current.selectedPageNames + pageName
            savedStateHandle[KEY_SELECTED_PAGES] = ArrayList(newSet)
            current.copy(selectedPageNames = newSet)
        }
    }

    fun selectAll() {
        _uiState.update { current ->
            val newSet = current.pages.map { it.name }.toSet()
            savedStateHandle[KEY_SELECTED_PAGES] = ArrayList(newSet)
            current.copy(selectedPageNames = newSet)
        }
    }

    fun clearSelection() {
        _uiState.update { current ->
            savedStateHandle[KEY_SELECTED_PAGES] = ArrayList<String>()
            current.copy(selectedPageNames = emptySet())
        }
    }

    fun onMove(fromIndex: Int, toIndex: Int) {
        _uiState.update { current ->
            if (fromIndex !in current.pages.indices || toIndex !in current.pages.indices) return@update current
            val list = current.pages.toMutableList()
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            current.copy(pages = list)
        }
    }

    fun persistOrder(context: Context, docFolder: File, isLocked: Boolean) {
        val currentPages = _uiState.value.pages
        viewModelScope.launch(Dispatchers.IO) {
            savePageOrder(docFolder, currentPages)
            if (isLocked) {
                try {
                    LockedDocCryptoManager.prepareUnlockedSession(context, docFolder)
                } catch (e: Exception) {
                    android.util.Log.e("DocDetailViewModel", "Failed updating session after reorder: ${e.message}")
                }
            }
        }
    }

    fun setSharingState(isSharing: Boolean, message: String? = null) {
        _uiState.update { it.copy(isSharing = isSharing, shareProgressMessage = message) }
    }

    fun updateLockStatus(isLocked: Boolean) {
        _uiState.update { it.copy(isLocked = isLocked) }
    }

    fun updateExpiryDate(expiryDate: Long?) {
        _uiState.update { it.copy(expiryDate = expiryDate) }
    }

    fun setLastDeletedInfo(info: LastDeletedInfo?) {
        lastDeletedInfo = info
    }

    fun undoLastDelete(context: Context, onRestored: () -> Unit) {
        val info = lastDeletedInfo ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trashedDir = info.trashedDir
                if (trashedDir.exists()) {
                    val pageFiles = trashedDir.listFiles { f ->
                        val ext = f.extension.lowercase(Locale.ROOT)
                        f.isFile && (ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "enc") &&
                                f.name != ".dek.enc" && f.name != "ocr.txt.enc"
                    } ?: emptyArray()

                    for (f in pageFiles) {
                        val dest = File(info.docFolder, f.name)
                        f.renameTo(dest)
                    }
                    trashedDir.deleteRecursively()
                    DocumentRepository.indexExistingDocs(context)
                    if (info.isLocked) {
                        try {
                            LockedDocCryptoManager.prepareUnlockedSession(context, info.docFolder)
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ScanVault", "undoLastDelete failed", e)
            }
            withContext(Dispatchers.Main) {
                lastDeletedInfo = null
                onRestored()
            }
        }
    }
}
