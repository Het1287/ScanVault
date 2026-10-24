package com.example.ownscan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// -------------------------------------------------------------
// DRIVE RESULT MODELS & CONTRACTS
// -------------------------------------------------------------

sealed class DriveResult<out T> {
    data class Success<out T>(val data: T) : DriveResult<T>()
    sealed class Failure : DriveResult<Nothing>() {
        object NoNetwork : Failure()
        object AuthExpired : Failure()
        object QuotaFull : Failure()
        data class Unknown(val message: String, val cause: Throwable? = null) : Failure()

        val displayMessage: String
            get() = when (this) {
                is NoNetwork -> "No network connection. Please check your internet."
                is AuthExpired -> "Google Drive session expired. Please sign in again."
                is QuotaFull -> "Google Drive storage quota is full."
                is Unknown -> message.ifBlank { "An unexpected error occurred." }
            }
    }
}

data class SyncResult(
    val uploaded: Int,
    val skipped: Int,
    val failed: Int
) {
    val totalProcessed: Int get() = uploaded + skipped + failed
    val isNothingChanged: Boolean get() = uploaded == 0 && failed == 0
}

data class CloudBackupInfo(
    val timestamp: Long,
    val byteSize: Long,
    val docCount: Int
)

data class CloudDocBackupItem(
    val fileId: String,
    val name: String,
    val folderName: String,
    val displayName: String,
    val parentFolder: String?,
    val modifiedTime: Long,
    val byteSize: Long,
    val isLockedBackup: Boolean = false
)

enum class ConflictResolution {
    KEEP_BOTH,
    NEWEST_WINS,
    SKIP
}

sealed class RestoreDocResult {
    data class Restored(val folderName: String, val isCopy: Boolean) : RestoreDocResult()
    object Skipped : RestoreDocResult()
}

data class RestoreSummaryResult(
    val restoredCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val badZipEntriesCount: Int,
    val lockedSkippedCount: Int = 0
)

data class UnzipResult(
    val extractedCount: Int,
    val skippedBadEntriesCount: Int
)

// -------------------------------------------------------------
// DRIVE BACKUP MANAGER IMPLEMENTATION
// -------------------------------------------------------------

object DriveBackupManager {
    private const val TAG = "DriveBackupManager"
    private const val LEGACY_BACKUP_FILE_NAME = "scanvault_cloud_backup.zip"
    private const val CLOUD_CACHE_PREF = "scanvault_cloud_cache"
    private const val KEY_CACHED_DOC_IDS = "cached_doc_folder_names"

    /**
     * Escapes single quotes and backslashes for Drive REST API v3 queries.
     */
    fun escapeDriveQueryString(value: String): String {
        return value.replace("\\", "\\\\").replace("'", "\\'")
    }

    fun getGoogleSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun getSignedInAccount(context: Context): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return if (account != null && GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_APPDATA))) {
            account
        } else {
            null
        }
    }

    private fun getDriveService(context: Context, account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            Collections.singleton(DriveScopes.DRIVE_APPDATA)
        ).apply {
            selectedAccount = account.account
        }

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("ScanVault").build()
    }

    // ---------------------------------------------------------
    // LOCAL CACHE MANAGEMENT
    // ---------------------------------------------------------

    fun getLocalCachedDocIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(CLOUD_CACHE_PREF, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_CACHED_DOC_IDS, emptySet()) ?: emptySet()
    }

    fun setLocalCachedDocIds(context: Context, ids: Set<String>) {
        val prefs = context.getSharedPreferences(CLOUD_CACHE_PREF, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_CACHED_DOC_IDS, ids).apply()
    }

    fun addDocIdToLocalCache(context: Context, folderName: String) {
        val prefs = context.getSharedPreferences(CLOUD_CACHE_PREF, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_CACHED_DOC_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(folderName)
        prefs.edit().putStringSet(KEY_CACHED_DOC_IDS, current).apply()
    }

    fun removeDocIdFromLocalCache(context: Context, folderName: String) {
        val prefs = context.getSharedPreferences(CLOUD_CACHE_PREF, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_CACHED_DOC_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(folderName)
        prefs.edit().putStringSet(KEY_CACHED_DOC_IDS, current).apply()
    }

    fun clearLocalCloudCache(context: Context) {
        val prefs = context.getSharedPreferences(CLOUD_CACHE_PREF, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CACHED_DOC_IDS).apply()
    }

    // ---------------------------------------------------------
    // FINGERPRINT STORAGE PER ACCOUNT
    // ---------------------------------------------------------

    private fun getFingerprintPrefs(context: Context, email: String) =
        context.getSharedPreferences("scanvault_fingerprints_${email.replace('@', '_').replace('.', '_')}", Context.MODE_PRIVATE)

    fun getStoredFingerprint(context: Context, email: String, folderName: String): String? {
        return getFingerprintPrefs(context, email).getString(folderName, null)
    }

    fun saveFingerprint(context: Context, email: String, folderName: String, fingerprint: String) {
        getFingerprintPrefs(context, email).edit().putString(folderName, fingerprint).apply()
    }

    fun removeStoredFingerprint(context: Context, email: String, folderName: String) {
        getFingerprintPrefs(context, email).edit().remove(folderName).apply()
    }

    fun clearFingerprints(context: Context, email: String) {
        getFingerprintPrefs(context, email).edit().clear().apply()
    }

    /**
     * Computes a deterministic content fingerprint for the document.
     * Excludes volatile lastModified dates and local trashedDate so moving to/from trash
     * does not falsely trigger re-uploads. Triggers re-upload on actual page, original, ocr,
     * tags, lock, or expiry changes.
     */
    fun computeDocFingerprint(doc: LocalDoc): String {
        val sb = StringBuilder()
        sb.append("displayName=").append(doc.displayName).append(";")
        sb.append("parentFolder=").append(doc.parentFolder ?: "").append(";")

        val files = doc.directory.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".temp") && it.name != "meta.json" }
            .sortedBy { it.relativeTo(doc.directory).path.replace('\\', '/') }
            .toList()

        for (file in files) {
            val relPath = file.relativeTo(doc.directory).path.replace('\\', '/')
            sb.append(relPath)
                .append(":")
                .append(file.length())
                .append(":")
                .append(file.lastModified())
                .append(";")
        }

        val meta = DocMeta.readFromDir(doc.directory)
        // Include only meaningful persistent state: tags, isLocked, expiryDate, isOcrDone
        // Explicitly exclude trashedDate so trash state changes don't cause Drive re-uploads
        sb.append("tags=").append(meta.tags.sorted().joinToString(",")).append(";")
        sb.append("isLocked=").append(meta.isLocked).append(";")
        sb.append("expiryDate=").append(meta.expiryDate ?: "").append(";")
        sb.append("isOcrDone=").append(meta.isOcrDone).append(";")

        val ocrFile = File(doc.directory, "ocr.txt")
        if (ocrFile.exists()) {
            sb.append("ocrLength=").append(ocrFile.length()).append(";")
        }

        return sha256Hex(sb.toString())
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------
    // ERROR CLASSIFICATION & NETWORK CHECK
    // ---------------------------------------------------------

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNet = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNet) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isMeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNet = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNet) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun classifyException(e: Exception, context: Context? = null): DriveResult.Failure {
        if (context != null && !isNetworkAvailable(context)) {
            return DriveResult.Failure.NoNetwork
        }
        return when (e) {
            is UnknownHostException, is SocketException, is SocketTimeoutException, is InterruptedIOException -> {
                DriveResult.Failure.NoNetwork
            }
            is UserRecoverableAuthIOException, is UserRecoverableAuthException -> {
                DriveResult.Failure.AuthExpired
            }
            is GoogleJsonResponseException -> {
                val code = e.statusCode
                val reasons = e.details?.errors?.mapNotNull { it.reason } ?: emptyList()
                val message = e.details?.message ?: e.message ?: ""
                if (code == 401 || reasons.any { it.equals("authError", ignoreCase = true) || it.equals("invalid_grant", ignoreCase = true) }) {
                    DriveResult.Failure.AuthExpired
                } else if (code == 403 && reasons.any { it.equals("rateLimitExceeded", ignoreCase = true) || it.equals("userRateLimitExceeded", ignoreCase = true) || it.equals("dailyLimitExceeded", ignoreCase = true) }) {
                    DriveResult.Failure.Unknown("Google Drive is busy. Please try again in a few minutes.", e)
                } else if (code == 507 || reasons.any { it.equals("storageQuotaExceeded", ignoreCase = true) } || message.contains("storage quota", ignoreCase = true)) {
                    DriveResult.Failure.QuotaFull
                } else {
                    DriveResult.Failure.Unknown("Drive API error ($code): $message", e)
                }
            }
            else -> {
                val cause = e.cause
                if (cause is GoogleJsonResponseException) {
                    classifyException(cause, context)
                } else if (cause is UnknownHostException || cause is SocketException || cause is SocketTimeoutException) {
                    DriveResult.Failure.NoNetwork
                } else {
                    DriveResult.Failure.Unknown(e.message ?: "Unknown error occurred", e)
                }
            }
        }
    }

    // ---------------------------------------------------------
    // PAGINATION QUERY HELPER
    // ---------------------------------------------------------

    private suspend fun queryAllDriveFiles(
        drive: Drive,
        query: String,
        fields: String = "nextPageToken, files(id, name, size, modifiedTime, properties)"
    ): List<com.google.api.services.drive.model.File> = withContext(Dispatchers.IO) {
        val allFiles = mutableListOf<com.google.api.services.drive.model.File>()
        var pageToken: String? = null
        do {
            val request = drive.files().list()
                .setSpaces("appDataFolder")
                .setQ(query)
                .setPageSize(100)
                .setFields(fields)
            if (pageToken != null) {
                request.pageToken = pageToken
            }
            val fileList = request.execute()
            fileList.files?.let { allFiles.addAll(it) }
            pageToken = fileList.nextPageToken
        } while (!pageToken.isNullOrEmpty())
        allFiles
    }

    // ---------------------------------------------------------
    // LOCAL DOCUMENT FINDER
    // ---------------------------------------------------------

    fun sanitizeFolderName(name: String): String {
        var sanitized = name.replace(Regex("[\\/:*?\"<>|]"), "")
        while (sanitized.contains("..")) {
            sanitized = sanitized.replace("..", "")
        }
        val trimmed = sanitized.trim()
        return if (trimmed.contains("__")) {
            trimmed
        } else {
            val ts = System.currentTimeMillis()
            "${ts}__${trimmed.ifBlank { "restored" }}"
        }
    }

    fun findActiveLocalDoc(context: Context, folderName: String, parentFolder: String? = null): LocalDoc? {
        val all = loadAllInAppDocuments(context, includeTrashed = false)
        val exact = all.find { it.folderName == folderName && (parentFolder == null || it.parentFolder == parentFolder) }
        if (exact != null) return exact
        val exactFolder = all.find { it.folderName == folderName }
        if (exactFolder != null) return exactFolder
        val baseName = if (folderName.contains("__")) folderName.substringAfter("__") else folderName
        return all.find { it.displayName.equals(baseName, ignoreCase = true) && (parentFolder == null || it.parentFolder == parentFolder) }
    }

    fun cleanTrashedDocIfExists(context: Context, folderName: String) {
        val allTrashed = loadAllInAppDocuments(context, includeTrashed = true).filter {
            DocMeta.readFromDir(it.directory).trashedDate != null
        }
        val match = allTrashed.find { it.folderName == folderName }
        match?.directory?.deleteRecursively()
    }

    fun findLocalDocByFolderName(context: Context, folderName: String): LocalDoc? {
        return findActiveLocalDoc(context, folderName)
    }

    // ---------------------------------------------------------
    // DRIVE OPERATIONS
    // ---------------------------------------------------------

    /**
     * Queries Google Drive. Drive is the source of truth; replaces local cache on success.
     */
    suspend fun getBackedUpDocFolderNames(context: Context, account: GoogleSignInAccount): DriveResult<Set<String>> = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService(context, account)
            val files = queryAllDriveFiles(
                drive,
                "trashed = false",
                "nextPageToken, files(id, name, properties)"
            )
            val driveSet = mutableSetOf<String>()
            for (f in files) {
                val folderProp = f.properties?.get("folderName")
                if (!folderProp.isNullOrBlank()) {
                    driveSet.add(folderProp)
                } else if (f.name.startsWith("doc_") && f.name.endsWith(".zip")) {
                    val extracted = f.name.removePrefix("doc_").removeSuffix(".zip")
                    driveSet.add(extracted)
                }
            }

            setLocalCachedDocIds(context, driveSet)
            DriveResult.Success(driveSet)
        } catch (e: Exception) {
            val failure = classifyException(e, context)
            Log.e(TAG, "Failed fetching backed up docs: ${failure.displayMessage}", e)
            failure
        }
    }

    /**
     * Backs up a single document folder to Drive.
     * Excludes trashed documents and skips locked documents. Supports optional Wi-Fi only restriction and upload progress.
     */
    suspend fun backupSingleDoc(
        context: Context,
        account: GoogleSignInAccount,
        doc: LocalDoc,
        forceUpload: Boolean = true,
        wifiOnly: Boolean = false,
        onByteProgress: ((bytesUploaded: Long, totalBytes: Long) -> Unit)? = null
    ): DriveResult<Unit> = withContext(Dispatchers.IO) {
        val safeName = doc.displayName
        val isDocLocked = doc.isLocked || File(doc.directory, ".dek.enc").exists()

        if (isDocLocked) {
            Log.d(TAG, "Document '$safeName' is locked; skipping Drive backup.")
            return@withContext DriveResult.Failure.Unknown("Google Drive cloud backup does not support locked files. Use Local Backup or remove the lock from your file.")
        }

        // Exclude trashed documents
        val meta = DocMeta.readFromDir(doc.directory)
        if (meta.trashedDate != null) {
            Log.d(TAG, "Document '$safeName' is trashed; skipping Drive backup.")
            return@withContext DriveResult.Success(Unit)
        }

        // Wi-Fi only check
        if (wifiOnly && isMeteredNetwork(context)) {
            Log.w(TAG, "Upload skipped for '$safeName': Wi-Fi only enabled on metered network.")
            return@withContext DriveResult.Failure.Unknown("Upload paused: Wi-Fi only is enabled and network is metered.")
        }

        val zipFile = File(context.cacheDir, "temp_doc_${doc.folderName}_${System.currentTimeMillis()}.zip")
        try {
            val email = account.email ?: "unknown"
            val currentFingerprint = computeDocFingerprint(doc)
            val storedFingerprint = getStoredFingerprint(context, email, doc.folderName)
            val backedUpIds = getLocalCachedDocIds(context)

            if (!forceUpload && storedFingerprint == currentFingerprint && backedUpIds.contains(doc.folderName)) {
                Log.d(TAG, "Doc '$safeName' unchanged; skipping upload.")
                return@withContext DriveResult.Success(Unit)
            }

            val drive = getDriveService(context, account)

            val driveFileName = "doc_${doc.folderName}.zip"
            val metadataProps = mapOf(
                "folderName" to doc.folderName,
                "displayName" to doc.displayName,
                "parentFolder" to (doc.parentFolder ?: ""),
                "isLocked" to "false"
            )

            // Write zip archive containing doc files and a doc_info.json fail-safe
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val docInfoObj = org.json.JSONObject().apply {
                    put("folderName", doc.folderName)
                    put("displayName", doc.displayName)
                    put("parentFolder", doc.parentFolder ?: "")
                }
                val infoEntry = ZipEntry("doc_info.json")
                zos.putNextEntry(infoEntry)
                zos.write(docInfoObj.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zipDirectory(doc.directory, "", zos)
            }

            val escapedFileName = escapeDriveQueryString(driveFileName)
            val existingFiles = queryAllDriveFiles(
                drive,
                "name = '$escapedFileName' and trashed = false",
                "nextPageToken, files(id, name)"
            )

            val mediaContent = FileContent("application/zip", zipFile)
            val totalBytes = zipFile.length()

            if (existingFiles.isNotEmpty()) {
                val existingFileId = existingFiles[0].id
                val updateMeta = com.google.api.services.drive.model.File().apply {
                    properties = metadataProps
                }
                val updateRequest = drive.files().update(existingFileId, updateMeta, mediaContent)
                if (onByteProgress != null) {
                    updateRequest.mediaHttpUploader?.setProgressListener { uploader ->
                        onByteProgress(uploader.numBytesUploaded, totalBytes)
                    }
                }
                updateRequest.execute()
            } else {
                val fileMetadata = com.google.api.services.drive.model.File().apply {
                    name = driveFileName
                    parents = listOf("appDataFolder")
                    properties = metadataProps
                }
                val createRequest = drive.files().create(fileMetadata, mediaContent)
                if (onByteProgress != null) {
                    createRequest.mediaHttpUploader?.setProgressListener { uploader ->
                        onByteProgress(uploader.numBytesUploaded, totalBytes)
                    }
                }
                createRequest.execute()
            }

            addDocIdToLocalCache(context, doc.folderName)
            saveFingerprint(context, email, doc.folderName, currentFingerprint)
            DriveResult.Success(Unit)
        } catch (e: Exception) {
            val failure = classifyException(e, context)
            Log.e(TAG, "backupSingleDoc failed for '$safeName': ${failure.displayMessage}", e)
            failure
        } finally {
            if (zipFile.exists()) {
                zipFile.delete()
            }
        }
    }

    /**
     * Incremental sync: uploads only docs whose content fingerprint changed.
     * Excludes trashed docs, skips locked docs, and supports optional Wi-Fi only restriction.
     */
    suspend fun backupAllDocsWithProgress(
        context: Context,
        account: GoogleSignInAccount,
        wifiOnly: Boolean = false,
        onProgress: (current: Int, total: Int, currentDoc: LocalDoc) -> Unit
    ): DriveResult<SyncResult> = withContext(Dispatchers.IO) {
        if (wifiOnly && isMeteredNetwork(context)) {
            Log.w(TAG, "Sync paused: Wi-Fi only is enabled and network is metered.")
            return@withContext DriveResult.Failure.Unknown("Sync paused: Wi-Fi only is enabled and network is metered.")
        }

        // Exclude trashed documents completely from backup
        val allDocs = loadAllInAppDocuments(context, includeTrashed = false)
        if (allDocs.isEmpty()) {
            return@withContext DriveResult.Success(SyncResult(0, 0, 0))
        }

        val email = account.email ?: "unknown"
        var uploadedCount = 0
        var skippedCount = 0
        var failedCount = 0
        var lastFailure: DriveResult.Failure? = null

        val backedUpResult = getBackedUpDocFolderNames(context, account)
        val backedUpIds = if (backedUpResult is DriveResult.Success) {
            backedUpResult.data
        } else {
            getLocalCachedDocIds(context)
        }

        allDocs.forEachIndexed { index, doc ->
            onProgress(index + 1, allDocs.size, doc)
            val isDocLocked = doc.isLocked || File(doc.directory, ".dek.enc").exists()
            if (isDocLocked) {
                Log.d(TAG, "Document '${doc.displayName}' is locked; skipping Drive backup.")
                skippedCount++
                return@forEachIndexed
            }
            val currentFingerprint = computeDocFingerprint(doc)
            val storedFingerprint = getStoredFingerprint(context, email, doc.folderName)

            if (storedFingerprint != null && storedFingerprint == currentFingerprint && backedUpIds.contains(doc.folderName)) {
                skippedCount++
            } else {
                when (val res = backupSingleDoc(
                    context = context,
                    account = account,
                    doc = doc,
                    forceUpload = true,
                    wifiOnly = wifiOnly
                )) {
                    is DriveResult.Success -> {
                        uploadedCount++
                    }
                    is DriveResult.Failure -> {
                        failedCount++
                        lastFailure = res
                        if (res is DriveResult.Failure.AuthExpired || res is DriveResult.Failure.NoNetwork) {
                            return@withContext res
                        }
                    }
                }
            }
        }

        val failure = lastFailure
        if (failedCount > 0 && uploadedCount == 0 && skippedCount == 0 && failure != null) {
            failure
        } else {
            DriveResult.Success(
                SyncResult(
                    uploaded = uploadedCount,
                    skipped = skippedCount,
                    failed = failedCount
                )
            )
        }
    }

    /**
     * Deletes a single document backup from Google Drive and clears its fingerprint.
     */
    suspend fun deleteSingleDocFromDrive(
        context: Context,
        account: GoogleSignInAccount,
        doc: LocalDoc
    ): DriveResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService(context, account)
            val escapedFileName = escapeDriveQueryString("doc_${doc.folderName}.zip")
            val files = queryAllDriveFiles(
                drive,
                "name = '$escapedFileName' and trashed = false",
                "nextPageToken, files(id, name)"
            )
            for (file in files) {
                drive.files().delete(file.id).execute()
            }

            removeDocIdFromLocalCache(context, doc.folderName)
            account.email?.let { email ->
                removeStoredFingerprint(context, email, doc.folderName)
            }
            DriveResult.Success(Unit)
        } catch (e: Exception) {
            val failure = classifyException(e, context)
            val safeName = if (doc.isLocked) "[PROTECTED_DOCUMENT]" else doc.displayName
            Log.e(TAG, "deleteSingleDocFromDrive failed for '$safeName': ${failure.displayMessage}", e)
            failure
        }
    }

    suspend fun deleteCloudItem(
        context: Context,
        account: GoogleSignInAccount,
        item: CloudDocBackupItem
    ): DriveResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService(context, account)
            drive.files().delete(item.fileId).execute()
            removeDocIdFromLocalCache(context, item.folderName)
            account.email?.let { email ->
                removeStoredFingerprint(context, email, item.folderName)
            }
            DriveResult.Success(Unit)
        } catch (e: Exception) {
            val failure = classifyException(e, context)
            Log.e(TAG, "deleteCloudItem failed: ${failure.displayMessage}", e)
            failure
        }
    }

    /**
     * Permanently deletes all app backup files from Google Drive and clears all fingerprints and cache.
     */
    suspend fun deleteCloudBackup(
        context: Context,
        account: GoogleSignInAccount
    ): DriveResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService(context, account)
            val files = queryAllDriveFiles(
                drive,
                "trashed = false",
                "nextPageToken, files(id, name)"
            )

            for (file in files) {
                drive.files().delete(file.id).execute()
            }
            clearLocalCloudCache(context)
            account.email?.let { email ->
                clearFingerprints(context, email)
            }
            DriveResult.Success(Unit)
        } catch (e: Exception) {
            val failure = classifyException(e, context)
            Log.e(TAG, "deleteCloudBackup failed: ${failure.displayMessage}", e)
            failure
        }
    }

    /**
     * Queries storage and cloud information from Drive across all pages.
     */
    suspend fun checkExistingBackupInfo(
        context: Context,
        account: GoogleSignInAccount
    ): DriveResult<CloudBackupInfo?> = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService(context, account)
            val files = queryAllDriveFiles(
                drive,
                "trashed = false",
                "nextPageToken, files(id, name, size, modifiedTime, properties)"
            )

            if (files.isEmpty()) {
                return@withContext DriveResult.Success(null)
            }

            var totalSize = 0L
            var maxTime = 0L
            var docCount = 0

            for (f in files) {
                totalSize += f.getSize() ?: 0L
                val time = f.modifiedTime?.value ?: 0L
                if (time > maxTime) maxTime = time
                if (f.name.startsWith("doc_") || f.name.startsWith("lockdoc_") || f.properties?.containsKey("folderName") == true) {
                    docCount++
                }
            }

            if (docCount == 0 && files.isNotEmpty()) {
                docCount = files[0].properties?.get("docCount")?.toIntOrNull() ?: 1
            }

            DriveResult.Success(CloudBackupInfo(timestamp = maxTime, byteSize = totalSize, docCount = docCount))
        } catch (e: Exception) {
            val failure = classifyException(e, context)
            Log.e(TAG, "checkExistingBackupInfo failed: ${failure.displayMessage}", e)
            failure
        }
    }

    /**
     * Lists all backed-up documents in Google Drive.
     */
    suspend fun listCloudBackups(
        context: Context,
        account: GoogleSignInAccount
    ): DriveResult<List<CloudDocBackupItem>> = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService(context, account)
            val files = queryAllDriveFiles(
                drive,
                "trashed = false",
                "nextPageToken, files(id, name, size, modifiedTime, properties)"
            )

            val items = mutableListOf<CloudDocBackupItem>()
            for (f in files) {
                if (f.name.startsWith("doc_") && f.name.endsWith(".zip")) {
                    val folderName = f.properties?.get("folderName")
                        ?: f.name.removePrefix("doc_").removeSuffix(".zip")
                    val displayName = f.properties?.get("displayName")
                        ?: folderName.substringAfter("__", folderName)
                    val parentFolder = f.properties?.get("parentFolder")?.ifBlank { null }
                    val modifiedTime = f.modifiedTime?.value ?: 0L
                    val size = f.getSize() ?: 0L
                    items.add(
                        CloudDocBackupItem(
                            fileId = f.id,
                            name = f.name,
                            folderName = folderName,
                            displayName = displayName,
                            parentFolder = parentFolder,
                            modifiedTime = modifiedTime,
                            byteSize = size,
                            isLockedBackup = false
                        )
                    )
                } else if (f.name.startsWith("lockdoc_") && f.name.endsWith(".zip")) {
                    val folderName = f.properties?.get("folderName")
                        ?: f.name.removePrefix("lockdoc_").removeSuffix(".zip")
                    val displayName = f.properties?.get("displayName") ?: "Protected Document"
                    val parentFolder = f.properties?.get("parentFolder")?.ifBlank { null }
                    val modifiedTime = f.modifiedTime?.value ?: 0L
                    val size = f.getSize() ?: 0L
                    items.add(
                        CloudDocBackupItem(
                            fileId = f.id,
                            name = f.name,
                            folderName = folderName,
                            displayName = displayName,
                            parentFolder = parentFolder,
                            modifiedTime = modifiedTime,
                            byteSize = size,
                            isLockedBackup = true
                        )
                    )
                } else if (f.name == LEGACY_BACKUP_FILE_NAME) {
                    items.add(
                        CloudDocBackupItem(
                            fileId = f.id,
                            name = f.name,
                            folderName = "legacy_backup",
                            displayName = "Full Library Backup (Legacy)",
                            parentFolder = null,
                            modifiedTime = f.modifiedTime?.value ?: 0L,
                            byteSize = f.getSize() ?: 0L,
                            isLockedBackup = false
                        )
                    )
                }
            }
            DriveResult.Success(items.sortedByDescending { it.modifiedTime })
        } catch (e: Exception) {
            val failure = classifyException(e, context)
            Log.e(TAG, "listCloudBackups failed: ${failure.displayMessage}", e)
            failure
        }
    }

    /**
     * Restores a single document with conflict handling, zip-slip defense, and Room/FTS rebuild.
     */
    suspend fun restoreSingleDoc(
        context: Context,
        account: GoogleSignInAccount,
        item: CloudDocBackupItem,
        onConflict: suspend (cloudItem: CloudDocBackupItem, localDoc: LocalDoc) -> ConflictResolution
    ): DriveResult<RestoreDocResult> = withContext(Dispatchers.IO) {
        if (item.isLockedBackup) {
            return@withContext DriveResult.Failure.Unknown("Google Drive cloud backup does not support locked files. Use Local Backup to restore locked files.")
        }

        val tempZip = File(context.cacheDir, "dl_${item.fileId}_${System.currentTimeMillis()}.zip")
        var stagingDir = File(context.cacheDir, "staging_single_${System.currentTimeMillis()}_${sanitizeName(item.folderName)}")

        try {
            val drive = getDriveService(context, account)

            // Download archive
            FileOutputStream(tempZip).use { out ->
                drive.files().get(item.fileId).executeMediaAndDownloadTo(out)
            }

            var safeFolderName = sanitizeFolderName(item.folderName)
            var safeParentFolder = item.parentFolder?.let { sanitizeName(it).ifBlank { null } }
            var safeDisplayName = item.displayName

            stagingDir = File(context.cacheDir, "staging_single_${System.currentTimeMillis()}_$safeFolderName")
            stagingDir.mkdirs()
            unzip(tempZip, stagingDir)

            // Read doc_info.json fail-safe if present
            val docInfoFile = File(stagingDir, "doc_info.json")
            if (docInfoFile.exists()) {
                try {
                } catch (e: Exception) {
                    Log.w(TAG, "Failed parsing doc_info.json: ${e.message}")
                } finally {
                    docInfoFile.delete()
                }
            }

            // Ensure restored doc is never in trashed state
            val metaFile = File(stagingDir, "meta.json")
            if (metaFile.exists()) {
                try {
                    val m = DocMeta.fromJson(metaFile.readText()).copy(trashedDate = null)
                    metaFile.writeText(m.toJson())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed cleaning trashedDate in meta.json: ${e.message}")
                }
            }

            val effectiveItem = item.copy(
                folderName = safeFolderName,
                displayName = safeDisplayName,
                parentFolder = safeParentFolder
            )

            val existingLocal = findActiveLocalDoc(context, effectiveItem.folderName, effectiveItem.parentFolder)
            var targetDir: File
            var isCopy = false

            if (existingLocal != null) {
                val resolution = onConflict(effectiveItem, existingLocal)
                when (resolution) {
                    ConflictResolution.SKIP -> {
                        return@withContext DriveResult.Success(RestoreDocResult.Skipped)
                    }
                    ConflictResolution.NEWEST_WINS -> {
                        // User chose to overwrite / replace existing local document with cloud version
                        targetDir = existingLocal.directory
                    }
                    ConflictResolution.KEEP_BOTH -> {
                        val newTs = System.currentTimeMillis()
                        val newDisplayName = "${effectiveItem.displayName} (restored)"
                        val safePart = sanitizeName(newDisplayName).ifBlank { "restored" }
                        val newFolderName = "${newTs}__$safePart"
                        targetDir = if (!effectiveItem.parentFolder.isNullOrBlank()) {
                            File(getUserFoldersDir(context), "${effectiveItem.parentFolder}/$newFolderName")
                        } else {
                            File(getPrivateDocsDir(context), newFolderName)
                        }
                        isCopy = true
                    }
                }
            } else {
                targetDir = if (!effectiveItem.parentFolder.isNullOrBlank()) {
                    val parentDir = File(getUserFoldersDir(context), effectiveItem.parentFolder!!)
                    if (!parentDir.exists()) parentDir.mkdirs()
                    File(parentDir, effectiveItem.folderName)
                } else {
                    val privateDocs = getPrivateDocsDir(context)
                    if (!privateDocs.exists()) privateDocs.mkdirs()
                    File(privateDocs, effectiveItem.folderName)
                }
            }

            // Remove any old trashed copy with same folder name so it doesn't collide
            cleanTrashedDocIfExists(context, safeFolderName)

            // Safe swap: rename existing target to backup, move staging into place, roll back on failure
            val targetParentDir = targetDir.parentFile ?: getPrivateDocsDir(context)
            targetParentDir.mkdirs()

            val backupExistingDir = if (targetDir.exists()) {
                val tempBackup = File(targetParentDir, ".backup_old_${System.currentTimeMillis()}_${targetDir.name}")
                if (targetDir.renameTo(tempBackup)) {
                    tempBackup
                } else {
                    null
                }
            } else null

            val success = if (stagingDir.renameTo(targetDir)) {
                true
            } else {
                val copied = stagingDir.copyRecursively(targetDir, overwrite = true)
                stagingDir.deleteRecursively()
                copied
            }

            if (success) {
                backupExistingDir?.deleteRecursively()
            } else {
                if (backupExistingDir != null && backupExistingDir.exists()) {
                    targetDir.deleteRecursively()
                    backupExistingDir.renameTo(targetDir)
                }
                return@withContext DriveResult.Failure.Unknown("Restore failed; your existing document was kept.")
            }

            addDocIdToLocalCache(context, targetDir.name)

            // Rebuild Room and FTS for restored documents
            DocumentRepository.rebuildRoomFromMetaJson(context)
            thumbnailMemoryCache.evictAll()

            DriveResult.Success(RestoreDocResult.Restored(targetDir.name, isCopy))
        } catch (e: Exception) {
            val failure = classifyException(e, context)
            Log.e(TAG, "restoreSingleDoc failed: ${failure.displayMessage}", e)
            failure
        } finally {
            if (tempZip.exists()) {
                tempZip.delete()
            }
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
        }
    }

    /**
     * Restores all documents with conflict handling, zip-slip validation, apply-to-all,
     * finally-block cleanup, and Room/FTS rebuilding.
     */
    suspend fun restoreData(
        context: Context,
        account: GoogleSignInAccount,
        onConflict: suspend (cloudItem: CloudDocBackupItem, localDoc: LocalDoc) -> Pair<ConflictResolution, Boolean /* applyToAll */>
    ): DriveResult<RestoreSummaryResult> = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService(context, account)
            val files = queryAllDriveFiles(
                drive,
                "trashed = false",
                "nextPageToken, files(id, name, size, modifiedTime, properties)"
            )

            if (files.isEmpty()) {
                return@withContext DriveResult.Success(RestoreSummaryResult(0, 0, 0, 0))
            }

            var restoredCount = 0
            var skippedCount = 0
            var failedCount = 0
            var lockedSkippedCount = 0
            var totalBadZipEntries = 0
            val restoredIds = mutableSetOf<String>()
            var rememberedResolution: ConflictResolution? = null

            for (driveFile in files) {
                if (driveFile.name.startsWith("lockdoc_") && driveFile.name.endsWith(".zip")) {
                    lockedSkippedCount++
                    continue
                }
                if (driveFile.name.startsWith("doc_") && driveFile.name.endsWith(".zip")) {
                    val rawFolderName = driveFile.properties?.get("folderName")
                        ?: driveFile.name.removePrefix("doc_").removeSuffix(".zip")
                    var folderName = sanitizeFolderName(rawFolderName)
                    var displayName = driveFile.properties?.get("displayName")
                        ?: folderName.substringAfter("__", folderName)
                    var parentFolder = driveFile.properties?.get("parentFolder")?.let { sanitizeName(it).ifBlank { null } }
                    val modifiedTime = driveFile.modifiedTime?.value ?: 0L
                    val size = driveFile.getSize() ?: 0L

                    val tempZip = File(context.cacheDir, "dl_${driveFile.id}_${System.currentTimeMillis()}.zip")
                    val stagingDir = File(context.cacheDir, "staging_${System.currentTimeMillis()}_$folderName")

                    try {
                        FileOutputStream(tempZip).use { out ->
                            drive.files().get(driveFile.id).executeMediaAndDownloadTo(out)
                        }
                        stagingDir.mkdirs()
                        val unzipResult = unzip(tempZip, stagingDir)
                        totalBadZipEntries += unzipResult.skippedBadEntriesCount

                        // Read doc_info.json fail-safe if present
                        val docInfoFile = File(stagingDir, "doc_info.json")
                        if (docInfoFile.exists()) {
                            try {
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed reading doc_info.json in restoreData: ${e.message}")
                            } finally {
                                docInfoFile.delete()
                            }
                        }

                        // Ensure restored doc is never in trashed state
                        val metaFile = File(stagingDir, "meta.json")
                        if (metaFile.exists()) {
                            try {
                                val m = DocMeta.fromJson(metaFile.readText()).copy(trashedDate = null)
                                metaFile.writeText(m.toJson())
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed cleaning meta.json in restoreData: ${e.message}")
                            }
                        }

                        val item = CloudDocBackupItem(
                            fileId = driveFile.id,
                            name = driveFile.name,
                            folderName = folderName,
                            displayName = displayName,
                            parentFolder = parentFolder,
                            modifiedTime = modifiedTime,
                            byteSize = size
                        )

                        val existingLocal = findActiveLocalDoc(context, folderName, parentFolder)
                        var targetDir: File

                        if (existingLocal != null) {
                            val resolution = if (rememberedResolution != null) {
                                rememberedResolution
                            } else {
                                val (choice, applyToAll) = onConflict(item, existingLocal)
                                if (applyToAll) {
                                    rememberedResolution = choice
                                }
                                choice
                            }

                            when (resolution) {
                                ConflictResolution.SKIP -> {
                                    skippedCount++
                                    continue
                                }
                                ConflictResolution.NEWEST_WINS -> {
                                    val localModTime = existingLocal.imageFiles.maxOfOrNull { it.lastModified() }
                                        ?: existingLocal.directory.lastModified()
                                    if (modifiedTime <= localModTime) {
                                        skippedCount++
                                        continue
                                    }
                                    targetDir = existingLocal.directory
                                }
                                ConflictResolution.KEEP_BOTH -> {
                                    val newTs = System.currentTimeMillis()
                                    val newDisplayName = "$displayName (restored)"
                                    val safePart = sanitizeName(newDisplayName).ifBlank { "restored" }
                        val newFolderName = "${newTs}__$safePart"
                                    targetDir = if (!parentFolder.isNullOrBlank()) {
                                        val pDir = File(getUserFoldersDir(context), parentFolder)
                                        if (!pDir.exists()) pDir.mkdirs()
                                        File(pDir, newFolderName)
                                    } else {
                                        val pDocs = getPrivateDocsDir(context)
                                        if (!pDocs.exists()) pDocs.mkdirs()
                                        File(pDocs, newFolderName)
                                    }
                                }
                            }
                        } else {
                            targetDir = if (!parentFolder.isNullOrBlank()) {
                                val pDir = File(getUserFoldersDir(context), parentFolder)
                                if (!pDir.exists()) pDir.mkdirs()
                                File(pDir, folderName)
                            } else {
                                val pDocs = getPrivateDocsDir(context)
                                if (!pDocs.exists()) pDocs.mkdirs()
                                File(pDocs, folderName)
                            }
                        }

                        // Remove any old trashed copy with same folder name
                        cleanTrashedDocIfExists(context, folderName)

                        // Safe swap: rename existing target to backup, move staging into place, roll back on failure
                        val targetParentDir = targetDir.parentFile ?: getPrivateDocsDir(context)
                        targetParentDir.mkdirs()

                        val backupExistingDir = if (targetDir.exists()) {
                            val tempBackup = File(targetParentDir, ".backup_old_${System.currentTimeMillis()}_${targetDir.name}")
                            if (targetDir.renameTo(tempBackup)) {
                                tempBackup
                            } else {
                                null
                            }
                        } else null

                        val success = if (stagingDir.renameTo(targetDir)) {
                            true
                        } else {
                            val copied = stagingDir.copyRecursively(targetDir, overwrite = true)
                            stagingDir.deleteRecursively()
                            copied
                        }

                        if (success) {
                            backupExistingDir?.deleteRecursively()
                            restoredIds.add(targetDir.name)
                            restoredCount++
                        } else {
                            if (backupExistingDir != null && backupExistingDir.exists()) {
                                targetDir.deleteRecursively()
                                backupExistingDir.renameTo(targetDir)
                            }
                            failedCount++
                            continue
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed downloading/unzipping '${driveFile.name}': ${e.message}", e)
                        failedCount++
                    } finally {
                        if (tempZip.exists()) {
                            tempZip.delete()
                        }
                        if (stagingDir.exists()) {
                            stagingDir.deleteRecursively()
                        }
                    }
                } else if (driveFile.name == LEGACY_BACKUP_FILE_NAME) {
                    val zipFile = File(context.cacheDir, "downloaded_${System.currentTimeMillis()}_$LEGACY_BACKUP_FILE_NAME")
                    val legacyStagingDir = File(context.cacheDir, "staging_legacy_${System.currentTimeMillis()}")
                    try {
                        FileOutputStream(zipFile).use { out ->
                            drive.files().get(driveFile.id).executeMediaAndDownloadTo(out)
                        }
                        legacyStagingDir.mkdirs()
                        val unzipResult = unzip(zipFile, legacyStagingDir)
                        totalBadZipEntries += unzipResult.skippedBadEntriesCount
                        legacyStagingDir.copyRecursively(context.filesDir, overwrite = true)
                        legacyStagingDir.deleteRecursively()
                        restoredCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed legacy restore: ${e.message}", e)
                        failedCount++
                    } finally {
                        if (zipFile.exists()) {
                            zipFile.delete()
                        }
                        if (legacyStagingDir.exists()) {
                            legacyStagingDir.deleteRecursively()
                        }
                    }
                }
            }

            val currentCache = getLocalCachedDocIds(context).toMutableSet()
            currentCache.addAll(restoredIds)
            setLocalCachedDocIds(context, currentCache)

            // Rebuild Room and FTS for all restored documents
            DocumentRepository.rebuildRoomFromMetaJson(context)
            thumbnailMemoryCache.evictAll()

            DriveResult.Success(
                RestoreSummaryResult(
                    restoredCount = restoredCount,
                    skippedCount = skippedCount,
                    failedCount = failedCount,
                    badZipEntriesCount = totalBadZipEntries,
                    lockedSkippedCount = lockedSkippedCount
                )
            )
        } catch (e: Exception) {
            val failure = classifyException(e, context)
            Log.e(TAG, "restoreData failed: ${failure.displayMessage}", e)
            failure
        }
    }
    // ---------------------------------------------------------
    // ZIP & UNZIP UTILITIES WITH ZIP-SLIP PROTECTION
    // ---------------------------------------------------------

    private fun zipDirectory(dir: File, basePath: String, zos: ZipOutputStream) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.name.endsWith(".lock_tmp") || file.name.endsWith(".unlock_tmp")) continue
            val entryPath = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
            if (file.isDirectory) {
                zipDirectory(file, entryPath, zos)
            } else {
                val buffer = ByteArray(4096)
                FileInputStream(file).use { fis ->
                    zos.putNextEntry(ZipEntry(entryPath))
                    var length: Int
                    while (fis.read(buffer).also { length = it } > 0) {
                        zos.write(buffer, 0, length)
                    }
                    zos.closeEntry()
                }
            }
        }
    }

    const val MAX_UNCOMPRESSED_BYTES = 500L * 1024 * 1024 // 500 MB
    const val MAX_ZIP_ENTRIES = 5000

    fun unzip(zipFile: File, targetDirectory: File, ignoreNames: Set<String> = emptySet()): UnzipResult {
        var extractedCount = 0
        var badEntriesCount = 0
        var totalBytesExtracted = 0L
        val canonicalTargetDirPath = targetDirectory.canonicalPath
        val targetPrefix = if (canonicalTargetDirPath.endsWith(File.separator)) {
            canonicalTargetDirPath
        } else {
            canonicalTargetDirPath + File.separator
        }

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (ignoreNames.contains(entry.name)) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                extractedCount++
                if (extractedCount > MAX_ZIP_ENTRIES) {
                    throw SecurityException("Zip bomb defense: exceeds maximum entry limit of $MAX_ZIP_ENTRIES")
                }

                val normalizedName = entry.name.replace('\\', '/')
                val targetFile = File(targetDirectory, normalizedName)
                val canonicalTargetPath = targetFile.canonicalPath

                // Zip-slip security validation
                if (!canonicalTargetPath.startsWith(targetPrefix) && canonicalTargetPath != canonicalTargetDirPath) {
                    Log.e(
                        TAG,
                        "SECURITY ALERT (Zip Slip): Entry '${entry.name}' resolves to '$canonicalTargetPath' outside destination '$canonicalTargetDirPath'. Skipping entry."
                    )
                    badEntriesCount++
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    val buffer = ByteArray(8192)
                    FileOutputStream(targetFile).use { fos ->
                        var length: Int
                        while (zis.read(buffer).also { length = it } > 0) {
                            totalBytesExtracted += length
                            if (totalBytesExtracted > MAX_UNCOMPRESSED_BYTES) {
                                throw SecurityException("Zip bomb defense: uncompressed data exceeds 500 MB limit")
                            }
                            fos.write(buffer, 0, length)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (badEntriesCount > 0) {
            Log.w(TAG, "Unzip complete with $badEntriesCount unsafe entries skipped.")
        }
        return UnzipResult(extractedCount, badEntriesCount)
    }
}
