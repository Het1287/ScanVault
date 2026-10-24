package com.example.ownscan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// -------------------------------------------------------------
// EXPIRY STATUS MODEL & HELPERS (JAVA.TIME)
// -------------------------------------------------------------

sealed class ExpiryStatus {
    object None : ExpiryStatus()
    data class Expired(val daysAgo: Long) : ExpiryStatus()
    data class ExpiringSoon(val daysLeft: Long) : ExpiryStatus()
    data class Valid(val daysLeft: Long) : ExpiryStatus()
}

object ExpiryHelper {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())

    fun formatDate(timestamp: Long?, zoneId: ZoneId = ZoneId.systemDefault()): String {
        if (timestamp == null || timestamp <= 0L) return "No expiry set"
        return try {
            val localDate = if (timestamp % (24 * 60 * 60 * 1000L) == 0L) {
                Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            } else {
                Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
            }
            localDate.format(dateFormatter)
        } catch (e: Exception) {
            "No expiry set"
        }
    }

    /**
     * Calculates the exact calendar day difference between today and the expiry date
     * using java.time.LocalDate and the provided/system timezone.
     * Material 3 DatePicker returns UTC midnight millis; this handles UTC dates cleanly without day drift.
     */
    fun getDiffDays(expiryDate: Long?, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
        if (expiryDate == null || expiryDate <= 0L) return null
        return try {
            val today = LocalDate.now(zoneId)
            val expiryLocalDate = if (expiryDate % (24 * 60 * 60 * 1000L) == 0L) {
                Instant.ofEpochMilli(expiryDate).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            } else {
                Instant.ofEpochMilli(expiryDate).atZone(zoneId).toLocalDate()
            }
            ChronoUnit.DAYS.between(today, expiryLocalDate)
        } catch (e: Exception) {
            null
        }
    }

    fun getExpiryStatus(expiryDate: Long?, zoneId: ZoneId = ZoneId.systemDefault()): ExpiryStatus {
        val diffDays = getDiffDays(expiryDate, zoneId) ?: return ExpiryStatus.None
        return when {
            diffDays < 0 -> ExpiryStatus.Expired(abs(diffDays))
            diffDays <= 30 -> ExpiryStatus.ExpiringSoon(diffDays)
            else -> ExpiryStatus.Valid(diffDays)
        }
    }

    /**
     * Consistent definition of "Expiring Soon": documents that expire between today and 30 days ahead.
     * Documents already expired (diffDays < 0) are excluded from expiring soon.
     */
    fun isExpiringSoon(expiryDate: Long?, zoneId: ZoneId = ZoneId.systemDefault()): Boolean {
        val diffDays = getDiffDays(expiryDate, zoneId) ?: return false
        return diffDays in 0..30
    }
}

// -------------------------------------------------------------
// EXPIRY WORKER (DAILY REMINDERS AT 30, 7, 1 DAY & EXPIRED)
// -------------------------------------------------------------

class ExpiryReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "ScanVaultDocExpiryWorker"
        const val CHANNEL_ID = "doc_expiry_channel"
        const val CHANNEL_NAME = "Document Expiry Reminders"
        private const val PREFS_NOTIFIED_MILESTONES = "scanvault_expiry_notified_milestones"

        // Recognized reminder milestone thresholds in days
        val MILESTONES = listOf(30L, 7L, 1L, 0L)

        fun schedule(context: Context) {
            // Meaningless setRequiresBatteryNotLow(false) removed
            val constraints = Constraints.Builder().build()

            val workRequest = PeriodicWorkRequestBuilder<ExpiryReminderWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        fun getNotifiedMilestones(context: Context, key: String): Set<String> {
            val prefs = context.getSharedPreferences(PREFS_NOTIFIED_MILESTONES, Context.MODE_PRIVATE)
            return prefs.getStringSet(key, emptySet()) ?: emptySet()
        }

        fun markMilestoneNotified(context: Context, key: String, milestone: Long) {
            val prefs = context.getSharedPreferences(PREFS_NOTIFIED_MILESTONES, Context.MODE_PRIVATE)
            val current = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.add(milestone.toString())
            prefs.edit().putStringSet(key, current).apply()
        }
    }

    override suspend fun doWork(): Result {
        return try {
            // Android 13+ (API 33+) POST_NOTIFICATIONS runtime permission check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPermission) {
                    return Result.success()
                }
            }

            val db = AppDatabase.getInstance(context)
            val activeDocs = db.documentDao().getAllActiveDocumentsList()
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            createNotificationChannel(notificationManager)

            for (doc in activeDocs) {
                val exp = doc.expiryDate ?: continue
                if (exp <= 0L) continue

                val diffDays = ExpiryHelper.getDiffDays(exp) ?: continue
                val docMilestoneKey = "${doc.folderName}_$exp"
                val notifiedSet = getNotifiedMilestones(context, docMilestoneKey)

                // Check milestones (30, 7, 1, 0)
                // If a run was skipped, notify when diffDays <= milestone and milestone hasn't been notified yet.
                // Find the most urgent/closest milestone reached that has not been notified:
                val applicableMilestone = MILESTONES.filter { milestone ->
                    diffDays <= milestone && !notifiedSet.contains(milestone.toString())
                }.minOrNull() ?: continue

                // Construct notification copy
                val (title, message) = if (doc.isLocked) {
                    when (applicableMilestone) {
                        30L, 7L, 1L -> Pair("Document Expiring Soon", "A locked document is expiring soon.")
                        0L -> Pair("Document Expired", "A locked document has expired.")
                        else -> Pair("Document Expiry Alert", "A locked document needs attention.")
                    }
                } else {
                    when (applicableMilestone) {
                        30L -> Pair("Document Expiring Soon", "\"${doc.displayName}\" will expire in ${diffDays} days (${ExpiryHelper.formatDate(exp)}).")
                        7L -> Pair("Document Expiring in 1 Week", "\"${doc.displayName}\" will expire in ${diffDays} days (${ExpiryHelper.formatDate(exp)}).")
                        1L -> Pair("Document Expiring Tomorrow", "⚠️ Urgent: \"${doc.displayName}\" expires tomorrow (${ExpiryHelper.formatDate(exp)})!")
                        0L -> Pair("Document Expired", "⚠️ Alert: \"${doc.displayName}\" has expired (${ExpiryHelper.formatDate(exp)})!")
                        else -> Pair("Document Expiry Notice", "\"${doc.displayName}\" has reached an expiry milestone.")
                    }
                }

                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    if (doc.isLocked) {
                        putExtra("IS_LOCKED_EXPIRY", true)
                    } else {
                        putExtra("OPEN_DOC_FOLDER", doc.folderName)
                    }
                }

                // Distinct notification ID per (doc, milestone)
                val notificationId = (doc.folderName.hashCode() * 31) + applicableMilestone.toInt()

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    notificationId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                )

                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

                notificationManager.notify(notificationId, notification)

                // Persist that this milestone (and any higher un-notified milestones) is notified
                MILESTONES.filter { it >= applicableMilestone }.forEach { m ->
                    markMilestoneNotified(context, docMilestoneKey, m)
                }
            }

            // Prune stale milestone entries that no longer match any active document's current expiry
            try {
                val prefs = context.getSharedPreferences(PREFS_NOTIFIED_MILESTONES, Context.MODE_PRIVATE)
                val validKeys = activeDocs.mapNotNull { doc ->
                    val exp = doc.expiryDate
                    if (exp != null && exp > 0L) "${doc.folderName}_$exp" else null
                }.toSet()

                val allKeys = prefs.all.keys
                val editor = prefs.edit()
                var modified = false
                for (k in allKeys) {
                    if (!validKeys.contains(k)) {
                        editor.remove(k)
                        modified = true
                    }
                }
                if (modified) editor.apply()
            } catch (e: Exception) {
                android.util.Log.e("ExpiryReminderWorker", "Failed pruning stale milestones: ${e.message}")
            }

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("ExpiryReminderWorker", "Worker failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when scanned documents (e.g. IDs, warranty, passports) are approaching expiration."
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}

// -------------------------------------------------------------
// EXPIRY DATE PICKER DIALOG (MATERIAL 3)
// -------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpiryDatePickerDialog(
    colors: AppColors,
    initialDate: Long?,
    onDismiss: () -> Unit,
    onDateSelected: (Long?) -> Unit
) {
    var selectedDateMillis by remember { mutableStateOf(initialDate) }
    var showM3DatePicker by remember { mutableStateOf(false) }

    if (showM3DatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDateMillis ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showM3DatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedDateMillis = datePickerState.selectedDateMillis
                        showM3DatePicker = false
                    }
                ) {
                    Text("OK", color = colors.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showM3DatePicker = false }) {
                    Text("Cancel", color = colors.textMuted)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📅", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Document Expiry Date", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Track renewal deadlines for IDs, passports, warranties, licenses, or agreements. ScanVault notifies you at 30, 7, and 1 day before expiration.",
                    color = colors.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 17.sp
                )

                // Current Selection Box
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.cardBackground,
                    border = BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showM3DatePicker = true }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Selected Expiry Date", color = colors.textMuted, fontSize = 11.sp)
                            Text(
                                text = if (selectedDateMillis != null && selectedDateMillis!! > 0) {
                                    ExpiryHelper.formatDate(selectedDateMillis)
                                } else {
                                    "No Date Set (Tap to choose)"
                                },
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }
                        Text("🗓️", fontSize = 20.sp)
                    }
                }

                if (selectedDateMillis != null && selectedDateMillis!! > 0) {
                    val status = ExpiryHelper.getExpiryStatus(selectedDateMillis)
                    val statusText = when (status) {
                        is ExpiryStatus.Expired -> "⚠️ Expired ${status.daysAgo} day(s) ago"
                        is ExpiryStatus.ExpiringSoon -> "⏳ Expiring soon in ${status.daysLeft} day(s)"
                        is ExpiryStatus.Valid -> "✅ Valid (${status.daysLeft} days remaining)"
                        ExpiryStatus.None -> ""
                    }
                    val statusColor = when (status) {
                        is ExpiryStatus.Expired -> colors.accentRed
                        is ExpiryStatus.ExpiringSoon -> colors.accentAmber
                        is ExpiryStatus.Valid -> colors.accentGreen
                        ExpiryStatus.None -> colors.textMuted
                    }

                    Text(statusText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDateSelected(selectedDateMillis) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
            ) {
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initialDate != null && initialDate > 0) {
                    TextButton(onClick = { onDateSelected(null) }) {
                        Text("Clear", color = colors.accentRed, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = colors.textMuted)
                }
            }
        }
    )
}
