package com.example.ownscan

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast

enum class FeedbackType {
    FEEDBACK,
    BUG
}

object FeedbackHelper {
    private const val FEEDBACK_EMAIL = "thescanvault@gmail.com"

    fun sendFeedback(context: Context, type: FeedbackType) {
        val (versionName, versionCode) = getAppVersionInfo(context)

        val subject = when (type) {
            FeedbackType.FEEDBACK -> context.getString(R.string.feedback_subject_feedback, versionName)
            FeedbackType.BUG -> context.getString(R.string.feedback_subject_bug, versionName)
        }

        val prompt = context.getString(R.string.feedback_body_prompt)
        val body = "$prompt\n\n\n---\nApp version: $versionName ($versionCode)\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}"

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        try {
            val chooser = Intent.createChooser(intent, context.getString(R.string.feedback_chooser_title))
            if (context !is Activity) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.feedback_no_email_app), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.feedback_no_email_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun getAppVersionInfo(context: Context): Pair<String, String> {
        return try {
            val pm = context.packageManager
            val pkgName = context.packageName
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, 0)
            }
            val vName = packageInfo.versionName ?: "1.0"
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
            Pair(vName, vCode)
        } catch (e: Exception) {
            Pair("1.0", "1")
        }
    }
}

fun sendFeedback(context: Context, type: FeedbackType) {
    FeedbackHelper.sendFeedback(context, type)
}
