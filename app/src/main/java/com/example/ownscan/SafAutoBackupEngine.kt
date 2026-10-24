package com.example.ownscan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SafBackupStatus(
    val isEnabled: Boolean,
    val folderUri: String?,
    val folderName: String?,
    val frequency: String, // "DAILY" or "WEEKLY"
    val lastBackupTimestamp: Long,
    val lastBackupStatus: String?,
    val hasPassword: Boolean
)

object SafAutoBackupEngine {

    private const val PREFS_NAME = "scanvault_saf_backup_prefs"
    private const val KEY_ENABLED = "saf_backup_enabled"
    private const val KEY_TREE_URI = "saf_backup_tree_uri"
    private const val KEY_FREQUENCY = "saf_backup_frequency"
    private const val KEY_LAST_TIME = "saf_backup_last_time"
    private const val KEY_LAST_STATUS = "saf_backup_last_status"
    private const val KEY_PASSWORD = "saf_backup_password"
    const val WORK_NAME = "scanvault_saf_auto_backup_worker"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBackupStatus(context: Context): SafBackupStatus {
        val prefs = getPrefs(context)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val treeUriStr = prefs.getString(KEY_TREE_URI, null)
        val freq = prefs.getString(KEY_FREQUENCY, "DAILY") ?: "DAILY"
        val lastTime = prefs.getLong(KEY_LAST_TIME, 0L)
        val lastStatus = prefs.getString(KEY_LAST_STATUS, null)
        val hasPwd = !prefs.getString(KEY_PASSWORD, null).isNullOrBlank()

        var folderName: String? = null
        if (!treeUriStr.isNullOrBlank()) {
            try {
                val uri = Uri.parse(treeUriStr)
                val docFile = DocumentFile.fromTreeUri(context, uri)
                folderName = docFile?.name ?: uri.lastPathSegment
            } catch (_: Exception) {
                folderName = "Selected Folder"
            }
        }

        return SafBackupStatus(
            isEnabled = enabled,
            folderUri = treeUriStr,
            folderName = folderName,
            frequency = freq,
            lastBackupTimestamp = lastTime,
            lastBackupStatus = lastStatus,
            hasPassword = hasPwd
        )
    }

    fun setBackupConfig(
        context: Context,
        enabled: Boolean,
        treeUri: Uri? = null,
        frequency: String? = null,
        password: String? = null
    ) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        editor.putBoolean(KEY_ENABLED, enabled)

        if (treeUri != null) {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            } catch (e: Exception) {
                android.util.Log.e("SafBackup", "Failed taking persistable URI permission: ${e.message}")
            }
            editor.putString(KEY_TREE_URI, treeUri.toString())
        }

        if (frequency != null) {
            editor.putString(KEY_FREQUENCY, frequency)
        }

        if (password != null) {
            editor.putString(KEY_PASSWORD, password)
        }

        editor.apply()

        if (enabled) {
            val freq = frequency ?: prefs.getString(KEY_FREQUENCY, "DAILY") ?: "DAILY"
            scheduleWork(context, freq)
        } else {
            cancelWork(context)
        }
    }

    fun recordBackupResult(context: Context, success: Boolean, message: String) {
        getPrefs(context).edit()
            .putLong(KEY_LAST_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_STATUS, if (success) "Success: $message" else "Failed: $message")
            .apply()
    }

    fun scheduleWork(context: Context, frequency: String) {
        val repeatInterval = if (frequency.equals("WEEKLY", ignoreCase = true)) 7L else 1L
        val timeUnit = if (frequency.equals("WEEKLY", ignoreCase = true)) TimeUnit.DAYS else TimeUnit.DAYS

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<SafAutoBackupWorker>(repeatInterval, timeUnit)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelWork(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Executes backup immediately to the configured SAF folder.
     */
    suspend fun performBackup(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val prefs = getPrefs(context)
        val treeUriStr = prefs.getString(KEY_TREE_URI, null)
        val password = prefs.getString(KEY_PASSWORD, null)

        if (treeUriStr.isNullOrBlank()) {
            return@withContext Pair(false, "No SAF target folder selected")
        }
        if (password.isNullOrBlank() || password.length < 8) {
            return@withContext Pair(false, "Backup password must be at least 8 characters")
        }

        try {
            val treeUri = Uri.parse(treeUriStr)
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Pair(false, "Cannot access selected SAF folder")

            if (!treeDoc.canWrite()) {
                return@withContext Pair(false, "No write permission in SAF folder")
            }

            // Load user documents
            val allDocs = loadAllInAppDocuments(context)
            if (allDocs.isEmpty()) {
                return@withContext Pair(true, "No documents to back up")
            }
            val docFolders = allDocs.map { it.directory }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "ScanVault_AutoBackup_$timeStamp.zip.enc"
            val targetFileDoc = treeDoc.createFile("application/octet-stream", fileName)
                ?: return@withContext Pair(false, "Failed creating backup file in SAF folder")

            val success = LocalEncryptedBackupManager.createEncryptedBackup(
                context = context,
                docFolders = docFolders,
                password = password,
                destinationUri = targetFileDoc.uri
            )

            if (success) {
                // Prune older auto-backups, keep last 5
                try {
                    val backups = treeDoc.listFiles()
                        .filter { it.name?.endsWith(".zip.enc") == true && (it.name?.startsWith("ScanVault_AutoBackup_") == true || it.name?.contains("AutoBackup_") == true) }
                        .sortedByDescending { it.lastModified() }

                    if (backups.size > 5) {
                        backups.drop(5).forEach { oldBackup ->
                            oldBackup.delete()
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SafBackup", "Pruning old backups failed: ${e.message}")
                }

                val size = targetFileDoc.length()
                val sizeStr = if (size > 1024 * 1024) String.format(Locale.US, "%.1f MB", size / (1024f * 1024f))
                else "${size / 1024} KB"

                recordBackupResult(context, true, "$sizeStr backed up")
                Pair(true, "Backup complete ($sizeStr)")
            } else {
                targetFileDoc.delete()
                recordBackupResult(context, false, "Encryption failed")
                Pair(false, "Encryption failed")
            }
        } catch (e: Exception) {
            android.util.Log.e("SafBackup", "Backup error: ${e.message}", e)
            recordBackupResult(context, false, e.message ?: "Unknown error")
            Pair(false, e.message ?: "Backup failed")
        }
    }
}

class SafAutoBackupWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val (success, _) = SafAutoBackupEngine.performBackup(appContext)
        return if (success) Result.success() else Result.retry()
    }
}
