package com.example.ownscan

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

// -------------------------------------------------------------
// METADATA FILE MODEL (meta.json)
// -------------------------------------------------------------

data class DocMeta(
    val tags: List<String> = emptyList(),
    val isLocked: Boolean = false,
    val expiryDate: Long? = null,
    val trashedDate: Long? = null,
    val isOcrDone: Boolean = false,
    val idCardDismissed: Boolean = false
) {
    fun toJson(): String = GsonBuilder().setPrettyPrinting().create().toJson(this)

    companion object {
        fun fromJson(jsonStr: String): DocMeta {
            return try {
                Gson().fromJson(jsonStr, DocMeta::class.java) ?: DocMeta()
            } catch (e: Exception) {
                DocMeta()
            }
        }

        fun readFromDir(directory: File): DocMeta {
            val file = File(directory, "meta.json")
            if (!file.exists()) return DocMeta()
            return try {
                fromJson(file.readText())
            } catch (e: Exception) {
                DocMeta()
            }
        }

        fun writeToDir(directory: File, meta: DocMeta) {
            try {
                if (!directory.exists()) directory.mkdirs()
                val file = File(directory, "meta.json")
                val newJson = meta.toJson()
                if (file.exists() && file.readText() == newJson) {
                    return
                }
                file.writeText(newJson)
            } catch (e: Exception) {
                android.util.Log.e("DocMeta", "Failed writing meta.json to ${directory.path}: ${e.message}")
            }
        }
    }
}

// -------------------------------------------------------------
// ROOM ENTITY
// -------------------------------------------------------------

@Entity(
    tableName = "documents_metadata",
    indices = [Index(value = ["trashedDate"])]
)
data class DocumentMetadataEntity(
    @PrimaryKey
    val folderName: String,
    val displayName: String,
    val timestamp: Long,
    val pageCount: Int,
    val parentFolder: String? = null,
    val tagsCsv: String = "",
    val isLocked: Boolean = false,
    val expiryDate: Long? = null,
    val trashedDate: Long? = null,
    val isOcrDone: Boolean = false,
    val ocrText: String = ""
) {
    fun getTagsList(): List<String> {
        return if (tagsCsv.isBlank()) emptyList() else tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun toDocMeta(): DocMeta {
        return DocMeta(
            tags = getTagsList(),
            isLocked = isLocked,
            expiryDate = expiryDate,
            trashedDate = trashedDate,
            isOcrDone = isOcrDone
        )
    }
}

// -------------------------------------------------------------
// ROOM DAO
// -------------------------------------------------------------

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents_metadata WHERE trashedDate IS NULL ORDER BY timestamp DESC")
    fun getAllActiveDocuments(): Flow<List<DocumentMetadataEntity>>

    @Query("SELECT * FROM documents_metadata WHERE trashedDate IS NULL ORDER BY timestamp DESC")
    fun getAllActiveDocumentsList(): List<DocumentMetadataEntity>

    @Query("SELECT * FROM documents_metadata WHERE trashedDate IS NOT NULL ORDER BY trashedDate DESC")
    fun getTrashedDocuments(): Flow<List<DocumentMetadataEntity>>

    @Query("SELECT * FROM documents_metadata WHERE trashedDate IS NOT NULL ORDER BY trashedDate DESC")
    fun getTrashedDocumentsList(): List<DocumentMetadataEntity>

    @Query("SELECT * FROM documents_metadata")
    fun getAllDocumentsList(): List<DocumentMetadataEntity>

    @Query("SELECT * FROM documents_metadata WHERE folderName = :folderName LIMIT 1")
    fun getByFolderName(folderName: String): DocumentMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(doc: DocumentMetadataEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdateAll(docs: List<DocumentMetadataEntity>): List<Long>

    @Query("UPDATE documents_metadata SET trashedDate = :trashedDate WHERE folderName = :folderName")
    fun setTrashedDate(folderName: String, trashedDate: Long?): Int

    @Query("UPDATE documents_metadata SET tagsCsv = :tagsCsv WHERE folderName = :folderName")
    fun updateTags(folderName: String, tagsCsv: String): Int

    @Query("UPDATE documents_metadata SET parentFolder = :parentFolder WHERE folderName = :folderName")
    fun updateParentFolder(folderName: String, parentFolder: String?): Int

    @Query("UPDATE documents_metadata SET isLocked = :isLocked WHERE folderName = :folderName")
    fun updateLockStatus(folderName: String, isLocked: Boolean): Int

    @Query("UPDATE documents_metadata SET expiryDate = :expiryDate WHERE folderName = :folderName")
    fun updateExpiryDate(folderName: String, expiryDate: Long?): Int

    @Query("DELETE FROM documents_metadata WHERE folderName = :folderName")
    fun deleteByFolderName(folderName: String): Int

    @Query("DELETE FROM documents_metadata WHERE trashedDate IS NOT NULL")
    fun deleteAllTrashed(): Int

    @Query("DELETE FROM documents_metadata WHERE trashedDate IS NOT NULL AND trashedDate < :thresholdTime")
    fun purgeOldTrashed(thresholdTime: Long): Int
}

// -------------------------------------------------------------
// ROOM FTS (FULL TEXT SEARCH) ENTITY & DAO
// -------------------------------------------------------------

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "documents_fts")
data class DocumentFtsEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowid: Int = 0,
    val folderName: String,
    val displayName: String,
    val tagsText: String,
    val pageText: String
)

@Dao
interface DocumentFtsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(doc: DocumentFtsEntity): Long

    @Query("SELECT rowid, * FROM documents_fts WHERE folderName = :folderName LIMIT 1")
    fun getByFolderName(folderName: String): DocumentFtsEntity?

    @Query("SELECT rowid, * FROM documents_fts WHERE documents_fts MATCH :query LIMIT 50")
    fun search(query: String): List<DocumentFtsEntity>

    @Query("DELETE FROM documents_fts WHERE folderName = :folderName")
    fun deleteByFolderName(folderName: String): Int

    @Query("DELETE FROM documents_fts")
    fun clearAll(): Int

    @Transaction
    fun insertOrUpdate(doc: DocumentFtsEntity): Long {
        deleteByFolderName(doc.folderName)
        return insert(doc.copy(rowid = 0))
    }
}

data class SearchResultSnippet(
    val folderName: String,
    val displayName: String,
    val matchType: String,
    val snippet: String,
    val queryTerm: String
)

// -------------------------------------------------------------
// ROOM DATABASE
// -------------------------------------------------------------

@Database(entities = [DocumentMetadataEntity::class, DocumentFtsEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun documentFtsDao(): DocumentFtsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `documents_fts` USING FTS4(`folderName` TEXT NOT NULL, `displayName` TEXT NOT NULL, `tagsText` TEXT NOT NULL, `pageText` TEXT NOT NULL)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_documents_metadata_trashedDate` ON `documents_metadata` (`trashedDate`)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `documents_fts`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `documents_fts` USING FTS4(`folderName` TEXT NOT NULL, `displayName` TEXT NOT NULL, `tagsText` TEXT NOT NULL, `pageText` TEXT NOT NULL, tokenize=unicode61)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scanvault_database.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}

// -------------------------------------------------------------
// FTS QUERY SANITIZATION
// -------------------------------------------------------------

fun sanitizeFtsQuery(rawQuery: String): String {
    val trimmed = rawQuery.trim()
    if (trimmed.isEmpty()) return ""
    val cleaned = trimmed.replace(Regex("""[*"':^()\-_+/{}\[\];]"""), " ")
    val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return ""
    return tokens.joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"*" }
}

// -------------------------------------------------------------
// DOCUMENT REPOSITORY & INDEXER (SINGLE SOURCE OF TRUTH ON DISK)
// -------------------------------------------------------------

object DocumentRepository {
    private const val PREFS_NAME = "scanvault_db_indexing"
    private const val KEY_INITIAL_INDEXED = "initial_db_indexed_v1"

    /**
     * Resolves the actual directory of a document across both root privateDocsDir
     * and any user folder subdirectories.
     */
    fun resolveDocDir(context: Context, folderName: String): File? {
        val rootDir = File(getPrivateDocsDir(context), folderName)
        if (rootDir.exists()) return rootDir

        val userFoldersDir = getUserFoldersDir(context)
        if (userFoldersDir.exists()) {
            val found = userFoldersDir.listFiles { f -> f.isDirectory }
                ?.asSequence()
                ?.map { File(it, folderName) }
                ?.firstOrNull { it.exists() }
            if (found != null) return found
        }
        return null
    }

    suspend fun indexExistingDocs(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val dao = db.documentDao()
        val ftsDao = db.documentFtsDao()
        val allLocalDocs = loadAllInAppDocuments(context, includeTrashed = true)

        val entities = allLocalDocs.map { localDoc ->
            LockedDocCryptoManager.recoverInterruptedLockOperations(localDoc.directory)

            val meta = DocMeta.readFromDir(localDoc.directory)
            DocMeta.writeToDir(localDoc.directory, meta)

            val existing = dao.getByFolderName(localDoc.folderName)

            val ocrText = if (meta.isLocked) {
                ""
            } else {
                val ocrTxtFile = File(localDoc.directory, "ocr.txt")
                if (ocrTxtFile.exists()) {
                    try {
                        ocrTxtFile.readText()
                    } catch (e: Exception) {
                        ""
                    }
                } else {
                    existing?.ocrText ?: ""
                }
            }

            if (!meta.isLocked && ocrText.isNotBlank()) {
                val ocrTxtFile = File(localDoc.directory, "ocr.txt")
                if (!ocrTxtFile.exists()) {
                    try {
                        ocrTxtFile.writeText(ocrText)
                    } catch (e: Exception) {
                        android.util.Log.e("DocumentRepository", "Failed writing ocr.txt: ${e.message}")
                    }
                }
            }

            ftsDao.deleteByFolderName(localDoc.folderName)
            ftsDao.insertOrUpdate(
                DocumentFtsEntity(
                    rowid = 0,
                    folderName = localDoc.folderName,
                    displayName = localDoc.displayName,
                    tagsText = meta.tags.joinToString(" "),
                    pageText = if (meta.isLocked) "" else ocrText
                )
            )

            DocumentMetadataEntity(
                folderName = localDoc.folderName,
                displayName = localDoc.displayName,
                timestamp = localDoc.timestamp,
                pageCount = localDoc.pageCount,
                parentFolder = localDoc.parentFolder,
                tagsCsv = meta.tags.joinToString(","),
                isLocked = meta.isLocked,
                expiryDate = meta.expiryDate,
                trashedDate = meta.trashedDate,
                isOcrDone = meta.isOcrDone,
                ocrText = if (meta.isLocked) "" else ocrText
            )
        }
        dao.insertOrUpdateAll(entities)

        val diskFolderNames = allLocalDocs.map { it.folderName }.toSet()
        val allDbDocs = dao.getAllDocumentsList()
        for (dbDoc in allDbDocs) {
            if (!diskFolderNames.contains(dbDoc.folderName)) {
                dao.deleteByFolderName(dbDoc.folderName)
                ftsDao.deleteByFolderName(dbDoc.folderName)
            }
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INITIAL_INDEXED, true)
            .apply()
    }

    suspend fun moveToTrash(context: Context, doc: LocalDoc) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val dao = db.documentDao()
        val now = System.currentTimeMillis()
        val meta = DocMeta.readFromDir(doc.directory).copy(trashedDate = now)
        DocMeta.writeToDir(doc.directory, meta)
        val updated = dao.setTrashedDate(doc.folderName, now)
        if (updated == 0) {
            dao.insertOrUpdate(
                DocumentMetadataEntity(
                    folderName = doc.folderName,
                    displayName = doc.displayName,
                    timestamp = doc.timestamp,
                    pageCount = doc.pageCount,
                    parentFolder = doc.parentFolder,
                    tagsCsv = meta.tags.joinToString(","),
                    isLocked = meta.isLocked,
                    expiryDate = meta.expiryDate,
                    trashedDate = now,
                    isOcrDone = meta.isOcrDone,
                    ocrText = ""
                )
            )
        }
        db.documentFtsDao().deleteByFolderName(doc.folderName)
        thumbnailMemoryCache.evictAll()
    }

    suspend fun restoreFromTrash(context: Context, docDir: File, folderName: String) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val dao = db.documentDao()
        val targetDir = if (docDir.exists()) docDir else resolveDocDir(context, folderName) ?: docDir
        var meta = if (targetDir.exists()) DocMeta.readFromDir(targetDir) else DocMeta()
        if (targetDir.exists()) {
            meta = meta.copy(trashedDate = null)
            DocMeta.writeToDir(targetDir, meta)
        }
        val updated = dao.setTrashedDate(folderName, null)
        if (updated == 0 && targetDir.exists()) {
            val parts = targetDir.name.split("__")
            val ts = parts.getOrNull(0)?.toLongOrNull() ?: System.currentTimeMillis()
            val name = if (parts.size >= 2) parts[1] else targetDir.name
            val parentFolder = if (targetDir.parentFile?.name != "documents") targetDir.parentFile?.name else null
            dao.insertOrUpdate(
                DocumentMetadataEntity(
                    folderName = folderName,
                    displayName = name,
                    timestamp = ts,
                    pageCount = targetDir.listFiles()?.count {
                        it.isFile && (it.extension in listOf("jpg", "png", "jpeg") ||
                            (it.name.endsWith(".enc", ignoreCase = true) &&
                             it.name != ".dek.enc" && it.name != "ocr.txt.enc" &&
                             !it.name.endsWith(".lock_tmp") && !it.name.endsWith(".unlock_tmp")))
                    } ?: 0,
                    parentFolder = parentFolder,
                    tagsCsv = meta.tags.joinToString(","),
                    isLocked = meta.isLocked,
                    expiryDate = meta.expiryDate,
                    trashedDate = null,
                    isOcrDone = meta.isOcrDone,
                    ocrText = ""
                )
            )
        }

        // Re-index into FTS
        val displayName = dao.getByFolderName(folderName)?.displayName ?: targetDir.name.substringAfter("__")
        val ocrText = if (meta.isLocked) "" else {
            val f = File(targetDir, "ocr.txt")
            if (f.exists()) { try { f.readText() } catch (_: Exception) { "" } } else ""
        }
        db.documentFtsDao().deleteByFolderName(folderName)
        db.documentFtsDao().insertOrUpdate(
            DocumentFtsEntity(
                rowid = 0,
                folderName = folderName,
                displayName = displayName,
                tagsText = meta.tags.joinToString(" "),
                pageText = ocrText
            )
        )
        thumbnailMemoryCache.evictAll()
    }

    suspend fun syncTrashedDocsFromDisk(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val dao = db.documentDao()
        val allOnDisk = loadAllInAppDocuments(context, includeTrashed = true)
        val diskTrashed = allOnDisk.filter { doc ->
            val meta = DocMeta.readFromDir(doc.directory)
            meta.trashedDate != null
        }
        for (doc in diskTrashed) {
            val meta = DocMeta.readFromDir(doc.directory)
            val trashedTime = meta.trashedDate ?: System.currentTimeMillis()
            val existing = dao.getByFolderName(doc.folderName)
            if (existing == null) {
                dao.insertOrUpdate(
                    DocumentMetadataEntity(
                        folderName = doc.folderName,
                        displayName = doc.displayName,
                        timestamp = doc.timestamp,
                        pageCount = doc.pageCount,
                        parentFolder = doc.parentFolder,
                        tagsCsv = meta.tags.joinToString(","),
                        isLocked = meta.isLocked,
                        expiryDate = meta.expiryDate,
                        trashedDate = trashedTime,
                        isOcrDone = meta.isOcrDone,
                        ocrText = ""
                    )
                )
            } else if (existing.trashedDate == null) {
                dao.setTrashedDate(doc.folderName, trashedTime)
            }
            db.documentFtsDao().deleteByFolderName(doc.folderName)
        }

        // Clean up database rows for deleted files
        val diskFolderNames = allOnDisk.map { it.folderName }.toSet()
        val allTrashedDb = dao.getTrashedDocumentsList()
        for (item in allTrashedDb) {
            if (!diskFolderNames.contains(item.folderName)) {
                dao.deleteByFolderName(item.folderName)
                db.documentFtsDao().deleteByFolderName(item.folderName)
            }
        }
    }

    /**
     * Shared, single-point permanent deletion method.
     * Deletes directory on disk (if present), Room metadata row, Room FTS index row,
     * decrypted session artifacts, local Drive cache entry, and Google Drive account fingerprints.
     */
    suspend fun permanentlyDeleteDoc(context: Context, folderName: String, docDir: File? = null) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val dir = docDir ?: resolveDocDir(context, folderName) ?: File(getPrivateDocsDir(context), folderName)

        if (dir.exists()) {
            dir.deleteRecursively()
        }

        db.documentDao().deleteByFolderName(folderName)
        db.documentFtsDao().deleteByFolderName(folderName)
        LockedDocCryptoManager.wipeDecryptedSession(context, folderName)
        DriveBackupManager.removeDocIdFromLocalCache(context, folderName)
        DriveBackupManager.getSignedInAccount(context)?.email?.let { email ->
            DriveBackupManager.removeStoredFingerprint(context, email, folderName)
        }
        thumbnailMemoryCache.evictAll()
    }

    suspend fun permanentlyDelete(context: Context, doc: LocalDoc) = permanentlyDeleteDoc(context, doc.folderName, doc.directory)

    suspend fun emptyTrash(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val dao = db.documentDao()
        val trashed = dao.getTrashedDocumentsList()

        for (item in trashed) {
            permanentlyDeleteDoc(context, item.folderName)
        }
        dao.deleteAllTrashed()

        // Purge any trashed document directories remaining on disk
        val allDocsOnDisk = loadAllInAppDocuments(context, includeTrashed = true)
        for (doc in allDocsOnDisk) {
            val meta = DocMeta.readFromDir(doc.directory)
            if (meta.trashedDate != null) {
                permanentlyDeleteDoc(context, doc.folderName, doc.directory)
            }
        }
        thumbnailMemoryCache.evictAll()
    }

    suspend fun updateDocTags(context: Context, doc: LocalDoc, tags: List<String>) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val meta = DocMeta.readFromDir(doc.directory).copy(tags = tags)
        DocMeta.writeToDir(doc.directory, meta)
        db.documentDao().updateTags(doc.folderName, tags.joinToString(","))

        val ftsExisting = db.documentFtsDao().getByFolderName(doc.folderName)
        if (ftsExisting != null) {
            db.documentFtsDao().deleteByFolderName(doc.folderName)
            db.documentFtsDao().insertOrUpdate(
                ftsExisting.copy(rowid = 0, tagsText = tags.joinToString(" "))
            )
        }
    }

    suspend fun updateDocLockStatus(context: Context, doc: LocalDoc, isLocked: Boolean): Boolean = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val success = if (isLocked) {
            LockedDocCryptoManager.lockDocument(context, doc.directory)
        } else {
            LockedDocCryptoManager.unlockDocumentPermanently(context, doc.directory)
        }

        if (success) {
            val meta = DocMeta.readFromDir(doc.directory).copy(isLocked = isLocked)
            DocMeta.writeToDir(doc.directory, meta)

            val ocrText = if (isLocked) {
                ""
            } else {
                val ocrTxtFile = File(doc.directory, "ocr.txt")
                if (ocrTxtFile.exists()) {
                    try { ocrTxtFile.readText() } catch (_: Exception) { "" }
                } else ""
            }

            db.documentDao().updateLockStatus(doc.folderName, isLocked)
            val existing = db.documentDao().getByFolderName(doc.folderName)
            if (existing != null) {
                val newPageCount = doc.directory.listFiles()?.count {
                    it.isFile && (it.extension in listOf("jpg", "png", "jpeg") ||
                        (it.name.endsWith(".enc", ignoreCase = true) &&
                         it.name != ".dek.enc" && it.name != "ocr.txt.enc" &&
                         !it.name.endsWith(".lock_tmp") && !it.name.endsWith(".unlock_tmp")))
                } ?: existing.pageCount
                db.documentDao().insertOrUpdate(existing.copy(isLocked = isLocked, ocrText = ocrText, pageCount = newPageCount))
            }

            db.documentFtsDao().deleteByFolderName(doc.folderName)
            db.documentFtsDao().insertOrUpdate(
                DocumentFtsEntity(
                    rowid = 0,
                    folderName = doc.folderName,
                    displayName = doc.displayName,
                    tagsText = meta.tags.joinToString(" "),
                    pageText = if (isLocked) "" else ocrText
                )
            )

            LockedDocCryptoManager.wipeDecryptedSession(context, doc.folderName)
            thumbnailMemoryCache.evictAll()
        }
        success
    }

    suspend fun updateDocExpiryDate(context: Context, doc: LocalDoc, expiryDate: Long?) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val meta = DocMeta.readFromDir(doc.directory).copy(expiryDate = expiryDate)
        DocMeta.writeToDir(doc.directory, meta)
        db.documentDao().updateExpiryDate(doc.folderName, expiryDate)
    }

    /**
     * Batch-optimized FTS-first search:
     * - FTS is primary path.
     * - Queries Room once in batch (avoids N+1 getByFolderName).
     * - Avoids rescanning the disk on every keystroke.
     * - Guaranteed privacy: Locked documents never leak page text or snippets.
     */
    suspend fun searchWithSnippets(context: Context, rawQuery: String): List<SearchResultSnippet> = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return@withContext emptyList()

        val db = AppDatabase.getInstance(context)
        val dao = db.documentDao()
        val ftsDao = db.documentFtsDao()

        // Batch query active docs once from Room database
        val activeEntities = dao.getAllActiveDocumentsList().associateBy { it.folderName }
        val results = mutableListOf<SearchResultSnippet>()
        val seenFolders = mutableSetOf<String>()

        // 1. Primary path: Room FTS MATCH with sanitized query
        try {
            val ftsQuery = sanitizeFtsQuery(query)
            if (ftsQuery.isNotBlank()) {
                val ftsResults = ftsDao.search(ftsQuery)
                for (item in ftsResults) {
                    val entity = activeEntities[item.folderName] ?: continue
                    val snippet = if (entity.isLocked) {
                        "🔒 Protected Document • Unlock to view contents"
                    } else {
                        extractSnippet(item.pageText, query)
                    }
                    val matchType = when {
                        item.displayName.contains(query, ignoreCase = true) -> "Matched Title"
                        item.tagsText.contains(query, ignoreCase = true) -> "Matched Tag"
                        else -> if (entity.isLocked) "Matched Title" else "Matched Page Text"
                    }
                    results.add(
                        SearchResultSnippet(
                            folderName = item.folderName,
                            displayName = item.displayName,
                            matchType = matchType,
                            snippet = snippet,
                            queryTerm = query
                        )
                    )
                    seenFolders.add(item.folderName)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DocumentRepository", "FTS search exception: ${e.message}")
        }

        // 2. Fast in-memory fallback on active Room entities (title, tags, and unlocked OCR text)
        for ((folderName, entity) in activeEntities) {
            if (seenFolders.contains(folderName)) continue

            val nameMatch = entity.displayName.contains(query, ignoreCase = true)
            val tagsList = entity.getTagsList()
            val tagMatch = tagsList.any { it.contains(query, ignoreCase = true) }
            val ocr = if (entity.isLocked) "" else entity.ocrText
            val ocrMatch = !entity.isLocked && ocr.contains(query, ignoreCase = true)

            if (nameMatch || tagMatch || ocrMatch) {
                val matchType = when {
                    nameMatch -> "Matched Title"
                    tagMatch -> "Matched Tag: #" + tagsList.firstOrNull { it.contains(query, ignoreCase = true) }
                    else -> "Matched Page Text"
                }
                val snippet = if (entity.isLocked) {
                    "🔒 Protected Document • Unlock to view contents"
                } else if (ocrMatch) {
                    extractSnippet(ocr, query)
                } else if (tagMatch) {
                    "Tags: " + tagsList.joinToString(", ")
                } else {
                    entity.displayName
                }
                results.add(
                    SearchResultSnippet(
                        folderName = folderName,
                        displayName = entity.displayName,
                        matchType = matchType,
                        snippet = snippet,
                        queryTerm = query
                    )
                )
                seenFolders.add(folderName)
            }
        }

        // Rank results by relevance: Exact Title (1) > Title Contains (2) > Tag Match (3) > Page Text (4)
        val rankedResults = results.sortedWith(
            compareBy<SearchResultSnippet> { snippet ->
                when {
                    snippet.displayName.equals(query, ignoreCase = true) -> 1
                    snippet.displayName.contains(query, ignoreCase = true) -> 2
                    snippet.matchType.startsWith("Matched Tag", ignoreCase = true) -> 3
                    else -> 4
                }
            }.thenByDescending { snippet ->
                activeEntities[snippet.folderName]?.timestamp ?: 0L
            }
        ).take(50)

        rankedResults
    }

    private fun extractSnippet(fullText: String, term: String): String {
        if (fullText.isBlank() || term.isBlank()) return ""
        val index = fullText.indexOf(term, ignoreCase = true)
        if (index == -1) {
            return fullText.take(120).replace("\n", " ").trim() + (if (fullText.length > 120) "..." else "")
        }
        val start = (index - 40).coerceAtLeast(0)
        val end = (index + term.length + 50).coerceAtMost(fullText.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < fullText.length) "..." else ""
        return prefix + fullText.substring(start, end).replace("\n", " ").trim() + suffix
    }

    suspend fun rebuildRoomFromMetaJson(context: Context) = withContext(Dispatchers.IO) {
        indexExistingDocs(context)
    }
}
