package com.example.ownscan

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.ownscan.ui.icons.AppIcons
import java.util.regex.Pattern

enum class SortOption(val label: String) {
    DATE_DESC("Date: Newest First"),
    DATE_ASC("Date: Oldest First"),
    NAME_ASC("Name: A to Z"),
    NAME_DESC("Name: Z to A"),
    SIZE_DESC("Size: Largest First"),
    SIZE_ASC("Size: Smallest First"),
    PAGES_DESC("Pages: Most First"),
    PAGES_ASC("Pages: Fewest First")
}

object SortManager {
    private const val PREFS_NAME = "scanvault_settings"
    private const val KEY_SORT = "selected_sort_option"

    fun getSavedSortOption(context: Context): SortOption {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SORT, SortOption.DATE_DESC.name) ?: SortOption.DATE_DESC.name
        return try {
            SortOption.valueOf(saved)
        } catch (e: Exception) {
            SortOption.DATE_DESC
        }
    }

    fun saveSortOption(context: Context, option: SortOption) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SORT, option.name).apply()
    }

    /**
     * Sorts documents. For size-based sorting, precomputes sizes once in O(N) rather than
     * repeatedly performing disk file queries inside the comparator in O(N log N).
     */
    fun sortDocs(docs: List<LocalDoc>, option: SortOption): List<LocalDoc> {
        return when (option) {
            SortOption.DATE_DESC -> docs.sortedByDescending { it.timestamp }
            SortOption.DATE_ASC -> docs.sortedBy { it.timestamp }
            SortOption.NAME_ASC -> docs.sortedBy { it.displayName.lowercase() }
            SortOption.NAME_DESC -> docs.sortedByDescending { it.displayName.lowercase() }
            SortOption.SIZE_DESC -> {
                val sizeMap = docs.associate { it.folderName to it.imageFiles.sumOf { f -> f.length() } }
                docs.sortedByDescending { sizeMap[it.folderName] ?: 0L }
            }
            SortOption.SIZE_ASC -> {
                val sizeMap = docs.associate { it.folderName to it.imageFiles.sumOf { f -> f.length() } }
                docs.sortedBy { sizeMap[it.folderName] ?: 0L }
            }
            SortOption.PAGES_DESC -> docs.sortedByDescending { it.pageCount }
            SortOption.PAGES_ASC -> docs.sortedBy { it.pageCount }
        }
    }
}

object RecentSearchesManager {
    private const val PREFS_NAME = "scanvault_recent_searches"
    private const val KEY_SEARCHES = "recent_queries_json"
    private const val MAX_SEARCHES = 10

    fun getRecentSearches(context: Context): List<String> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_SEARCHES, null) ?: return emptyList()
            val type = object : TypeToken<List<String>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addSearchQuery(context: Context, query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        try {
            val current = getRecentSearches(context).toMutableList()
            current.removeAll { it.equals(clean, ignoreCase = true) }
            current.add(0, clean)
            val trimmed = if (current.size > MAX_SEARCHES) current.take(MAX_SEARCHES) else current
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SEARCHES, Gson().toJson(trimmed)).apply()
        } catch (e: Exception) {
            // Safe storage
        }
    }

    fun removeSearchQuery(context: Context, query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        try {
            val current = getRecentSearches(context).toMutableList()
            current.removeAll { it.equals(clean, ignoreCase = true) }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SEARCHES, Gson().toJson(current)).apply()
        } catch (e: Exception) {
            // Safe storage
        }
    }

    fun clearRecentSearches(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_SEARCHES).apply()
        } catch (e: Exception) {
            // Safe storage
        }
    }
}

// -------------------------------------------------------------
// SMART COLLECTIONS & TAG FILTERING
// -------------------------------------------------------------

enum class SmartCollection(val label: String, val icon: String) {
    ALL("All", AppIcons.FilePdf),
    RECENT("Recent", AppIcons.Clock),
    FAVORITES("Favorites", AppIcons.Star),
    RECEIPTS("Receipts", AppIcons.Receipt),
    IDS("ID Cards", AppIcons.IdCard),
    NOTES("Notes", "📝"),
    BOOKS("Books", "📚"),
    BUSINESS("Business", "💼"),
    LOCKED("Locked", AppIcons.Lock),
    BACKED_UP("Backed up to cloud", AppIcons.Cloud),
    EXPIRING_SOON("Expiring Soon", AppIcons.Hourglass),
    NOT_BACKED_UP("Not Backed Up", AppIcons.Cloud);

    companion object {
        val ID_CARDS: SmartCollection get() = IDS
    }
}

object SmartCollectionsManager {

    // Precompiled word-boundary regex patterns to prevent false positives like "Valid", "Paid", "Company", "Panel"
    private val ID_WORD_REGEX = Pattern.compile("\\bID\\b", Pattern.CASE_INSENSITIVE)
    private val PAN_WORD_REGEX = Pattern.compile("\\bPAN\\b", Pattern.CASE_INSENSITIVE)
    private val AADHAAR_WORD_REGEX = Pattern.compile("\\bAadhaar\\b", Pattern.CASE_INSENSITIVE)
    private val PASSPORT_WORD_REGEX = Pattern.compile("\\bPassport\\b", Pattern.CASE_INSENSITIVE)
    private val DL_WORD_REGEX = Pattern.compile("\\b(?:Driving\\s+Licen[sc]e|DL)\\b", Pattern.CASE_INSENSITIVE)

    private val RECEIPT_WORD_REGEX = Pattern.compile("\\bReceipt\\b", Pattern.CASE_INSENSITIVE)
    private val BILL_WORD_REGEX = Pattern.compile("\\bBill\\b", Pattern.CASE_INSENSITIVE)
    private val INVOICE_WORD_REGEX = Pattern.compile("\\bInvoice\\b", Pattern.CASE_INSENSITIVE)

    private val NOTES_WORD_REGEX = Pattern.compile("\\b(?:Notes?|Memo|Diary|Journal|Meeting\\s+Notes?)\\b", Pattern.CASE_INSENSITIVE)
    private val BOOKS_WORD_REGEX = Pattern.compile("\\b(?:Books?|eBook|Textbook|Study|Chapter|Novel|Manual|Guide)\\b", Pattern.CASE_INSENSITIVE)
    private val BUSINESS_WORD_REGEX = Pattern.compile("\\b(?:Business|Work|Office|Contract|Company|Corporate|Agreement|Proposal|Client)\\b", Pattern.CASE_INSENSITIVE)

    /**
     * Checks if any tag or display name matches the ID criteria using word-boundary regexes,
     * giving priority to exact tag matches.
     */
    fun matchesIdCollection(doc: LocalDoc): Boolean {
        // 1. Prefer exact tag match
        for (tag in doc.tags) {
            val trimmed = tag.trim()
            if (trimmed.equals("ID", ignoreCase = true) ||
                trimmed.equals("PAN", ignoreCase = true) ||
                trimmed.equals("Aadhaar", ignoreCase = true) ||
                trimmed.equals("Passport", ignoreCase = true) ||
                trimmed.equals("DL", ignoreCase = true) ||
                trimmed.equals("Driving License", ignoreCase = true)
            ) {
                return true
            }
            if (ID_WORD_REGEX.matcher(trimmed).find() ||
                PAN_WORD_REGEX.matcher(trimmed).find() ||
                AADHAAR_WORD_REGEX.matcher(trimmed).find() ||
                PASSPORT_WORD_REGEX.matcher(trimmed).find() ||
                DL_WORD_REGEX.matcher(trimmed).find()
            ) {
                return true
            }
        }

        // 2. Word-boundary regex on display name
        val name = doc.displayName
        if (ID_WORD_REGEX.matcher(name).find() ||
            PAN_WORD_REGEX.matcher(name).find() ||
            AADHAAR_WORD_REGEX.matcher(name).find() ||
            PASSPORT_WORD_REGEX.matcher(name).find() ||
            DL_WORD_REGEX.matcher(name).find()
        ) {
            return true
        }

        // 3. Parent folder match
        return doc.parentFolder.equals("IDs", ignoreCase = true) || doc.parentFolder.equals("ID Cards", ignoreCase = true)
    }

    /**
     * Checks if document matches Receipt criteria using word-boundary matching.
     */
    fun matchesReceiptCollection(doc: LocalDoc): Boolean {
        for (tag in doc.tags) {
            val trimmed = tag.trim()
            if (trimmed.equals("Receipt", ignoreCase = true) ||
                trimmed.equals("Bill", ignoreCase = true) ||
                trimmed.equals("Invoice", ignoreCase = true)
            ) {
                return true
            }
            if (RECEIPT_WORD_REGEX.matcher(trimmed).find() ||
                BILL_WORD_REGEX.matcher(trimmed).find() ||
                INVOICE_WORD_REGEX.matcher(trimmed).find()
            ) {
                return true
            }
        }

        val name = doc.displayName
        return RECEIPT_WORD_REGEX.matcher(name).find() ||
                BILL_WORD_REGEX.matcher(name).find() ||
                INVOICE_WORD_REGEX.matcher(name).find() ||
                doc.parentFolder.equals("Receipts", ignoreCase = true)
    }

    /**
     * Checks if document matches Notes criteria using word-boundary matching.
     */
    fun matchesNotesCollection(doc: LocalDoc): Boolean {
        for (tag in doc.tags) {
            val trimmed = tag.trim()
            if (trimmed.equals("Note", ignoreCase = true) ||
                trimmed.equals("Notes", ignoreCase = true) ||
                trimmed.equals("Memo", ignoreCase = true) ||
                trimmed.equals("Diary", ignoreCase = true) ||
                trimmed.equals("Journal", ignoreCase = true)
            ) {
                return true
            }
            if (NOTES_WORD_REGEX.matcher(trimmed).find()) return true
        }

        val name = doc.displayName
        return NOTES_WORD_REGEX.matcher(name).find() ||
                doc.parentFolder.equals("Notes", ignoreCase = true)
    }

    /**
     * Checks if document matches Books criteria using word-boundary matching.
     */
    fun matchesBooksCollection(doc: LocalDoc): Boolean {
        for (tag in doc.tags) {
            val trimmed = tag.trim()
            if (trimmed.equals("Book", ignoreCase = true) ||
                trimmed.equals("Books", ignoreCase = true) ||
                trimmed.equals("Study", ignoreCase = true) ||
                trimmed.equals("eBook", ignoreCase = true) ||
                trimmed.equals("Textbook", ignoreCase = true) ||
                trimmed.equals("Manual", ignoreCase = true) ||
                trimmed.equals("Guide", ignoreCase = true)
            ) {
                return true
            }
            if (BOOKS_WORD_REGEX.matcher(trimmed).find()) return true
        }

        val name = doc.displayName
        return BOOKS_WORD_REGEX.matcher(name).find() ||
                doc.parentFolder.equals("Books", ignoreCase = true)
    }

    /**
     * Checks if document matches Business criteria using word-boundary matching.
     */
    fun matchesBusinessCollection(doc: LocalDoc): Boolean {
        for (tag in doc.tags) {
            val trimmed = tag.trim()
            if (trimmed.equals("Business", ignoreCase = true) ||
                trimmed.equals("Work", ignoreCase = true) ||
                trimmed.equals("Office", ignoreCase = true) ||
                trimmed.equals("Contract", ignoreCase = true) ||
                trimmed.equals("Company", ignoreCase = true) ||
                trimmed.equals("Agreement", ignoreCase = true)
            ) {
                return true
            }
            if (BUSINESS_WORD_REGEX.matcher(trimmed).find()) return true
        }

        val name = doc.displayName
        return BUSINESS_WORD_REGEX.matcher(name).find() ||
                doc.parentFolder.equals("Business", ignoreCase = true)
    }

    /**
     * Returns a friendly user-facing empty state message for each category.
     */
    fun getEmptyCategoryMessage(collection: SmartCollection): String {
        return when (collection) {
            SmartCollection.ALL -> "No documents found"
            SmartCollection.RECENT -> "No recent documents yet"
            SmartCollection.FAVORITES -> "No favorites yet"
            SmartCollection.RECEIPTS -> "No receipts yet"
            SmartCollection.IDS -> "No ID cards yet"
            SmartCollection.NOTES -> "No notes yet"
            SmartCollection.BOOKS -> "No books yet"
            SmartCollection.BUSINESS -> "No business documents yet"
            SmartCollection.LOCKED -> "No locked documents yet"
            SmartCollection.BACKED_UP -> "No documents backed up to cloud yet"
            SmartCollection.EXPIRING_SOON -> "No expiring documents yet"
            SmartCollection.NOT_BACKED_UP -> "No unbacked documents yet"
        }
    }

    /**
     * Filters documents using in-memory Room fields on LocalDoc (isLocked, expiryDate, tags)
     * without performing disk I/O to read meta.json on each filter pass.
     */
    fun filterDocs(
        docs: List<LocalDoc>,
        collection: SmartCollection,
        favoritePaths: Set<String>,
        backedUpFolderNames: Set<String>,
        activeTag: String? = null
    ): List<LocalDoc> {
        val now = System.currentTimeMillis()

        val baseFiltered = when (collection) {
            SmartCollection.ALL -> docs
            SmartCollection.RECENT -> {
                val sevenDaysAgo = now - (7L * 24 * 60 * 60 * 1000L)
                docs.filter { it.timestamp >= sevenDaysAgo }.sortedByDescending { it.timestamp }
            }
            SmartCollection.FAVORITES -> docs.filter {
                favoritePaths.contains(it.folderName) || favoritePaths.contains(it.directory.absolutePath)
            }
            SmartCollection.RECEIPTS -> docs.filter { matchesReceiptCollection(it) }
            SmartCollection.IDS -> docs.filter { matchesIdCollection(it) }
            SmartCollection.NOTES -> docs.filter { matchesNotesCollection(it) }
            SmartCollection.BOOKS -> docs.filter { matchesBooksCollection(it) }
            SmartCollection.BUSINESS -> docs.filter { matchesBusinessCollection(it) }
            SmartCollection.LOCKED -> docs.filter { it.isLocked }
            SmartCollection.BACKED_UP -> docs.filter { doc ->
                backedUpFolderNames.contains(doc.folderName)
            }
            SmartCollection.EXPIRING_SOON -> docs.filter { doc ->
                ExpiryHelper.isExpiringSoon(doc.expiryDate)
            }
            SmartCollection.NOT_BACKED_UP -> docs.filter { doc ->
                !backedUpFolderNames.contains(doc.folderName)
            }
        }

        return if (activeTag != null) {
            baseFiltered.filter { it.tags.any { tag -> tag.equals(activeTag, ignoreCase = true) } }
        } else {
            baseFiltered
        }
    }
}

object ViewModeManager {
    private const val PREFS_NAME = "scanvault_settings"
    private const val KEY_VIEW_MODE_GRID = "files_view_mode_grid"

    fun isGridView(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_VIEW_MODE_GRID, false)
    }

    fun saveGridView(context: Context, isGrid: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_VIEW_MODE_GRID, isGrid).apply()
    }
}
