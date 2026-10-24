package com.example.ownscan

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class TrashPurgeWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000L
            val threshold = System.currentTimeMillis() - thirtyDaysMillis
            val dao = AppDatabase.getInstance(appContext).documentDao()
            val trashedDocs = dao.getTrashedDocumentsList()

            var purgedCount = 0
            for (entity in trashedDocs) {
                val trashedDate = entity.trashedDate ?: 0L
                if (trashedDate > 0 && trashedDate < threshold) {
                    // Shared repository deletion deletes disk files (any folder), Room row, FTS index,
                    // decrypted session, and Drive fingerprints/caches in one unified call.
                    DocumentRepository.permanentlyDeleteDoc(appContext, entity.folderName)
                    purgedCount++
                }
            }

            Log.d("TrashPurgeWorker", "Purged $purgedCount documents older than 30 days.")
            Result.success()
        } catch (e: Exception) {
            Log.e("TrashPurgeWorker", "Error purging trash: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "ScanVaultTrashPurgeWork"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
