package com.example.ownscan

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SEARCHES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addSearchQuery(context: Context, query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        val current = getRecentSearches(context).toMutableList()
        current.remove(clean)
        current.add(0, clean)
        val trimmed = if (current.size > MAX_SEARCHES) current.take(MAX_SEARCHES) else current
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SEARCHES, Gson().toJson(trimmed)).apply()
    }

    fun clearRecentSearches(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SEARCHES).apply()
    }
}

// -------------------------------------------------------------
// SMART COLLECTIONS & TAG FILTERING
// -------------------------------------------------------------

enum class SmartCollection(val label: String, val icon: String) {
    ALL("All", "📄"),
    RECENT("Recent", "🕒"),
    FAVORITES("Favorites", "★"),
    RECEIPTS("Receipts", "🧾"),
    IDS("IDs", "🪪"),
    EXPIRING_SOON("Expiring Soon", "⏳"),
    LOCKED("Locked", "🔒"),
    NOT_BACKED_UP("Not Backed Up", "☁️")
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
        return doc.parentFolder.equals("IDs", ignoreCase = true)
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
                INVOICE_WORD_REGEX.matcher(name).find()
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
                docs.filter { it.timestamp >= sevenDaysAgo }
            }
            SmartCollection.FAVORITES -> docs.filter {
                favoritePaths.contains(it.folderName) || favoritePaths.contains(it.directory.absolutePath)
            }
            SmartCollection.RECEIPTS -> docs.filter { matchesReceiptCollection(it) }
            SmartCollection.IDS -> docs.filter { matchesIdCollection(it) }
            SmartCollection.EXPIRING_SOON -> docs.filter { doc ->
                ExpiryHelper.isExpiringSoon(doc.expiryDate)
            }
            SmartCollection.LOCKED -> docs.filter { it.isLocked }
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
