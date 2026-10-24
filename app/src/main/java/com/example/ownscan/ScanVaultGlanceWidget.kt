package com.example.ownscan

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

class ScanVaultGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(16.dp)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Title Header
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "ScanVault",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Spacer(modifier = GlanceModifier.height(8.dp))

                    // Action Buttons Row
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Scan Action (Primary accent)
                        val scanIntent = Intent(context, MainActivity::class.java).apply {
                            action = "com.example.ownscan.ACTION_QUICK_SCAN"
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        WidgetActionButton(
                            icon = "📷",
                            label = "Scan",
                            intent = scanIntent,
                            backgroundProvider = GlanceTheme.colors.primary,
                            textColorProvider = GlanceTheme.colors.onPrimary,
                            modifier = GlanceModifier.defaultWeight()
                        )

                        Spacer(modifier = GlanceModifier.width(6.dp))

                        // Search Action
                        val searchIntent = Intent(context, MainActivity::class.java).apply {
                            action = "com.example.ownscan.ACTION_SEARCH"
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        WidgetActionButton(
                            icon = "🔍",
                            label = "Search",
                            intent = searchIntent,
                            backgroundProvider = GlanceTheme.colors.secondaryContainer,
                            textColorProvider = GlanceTheme.colors.onSecondaryContainer,
                            modifier = GlanceModifier.defaultWeight()
                        )

                        Spacer(modifier = GlanceModifier.width(6.dp))

                        // Recents Action
                        val recentsIntent = Intent(context, MainActivity::class.java).apply {
                            action = "com.example.ownscan.ACTION_VIEW_RECENTS"
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        WidgetActionButton(
                            icon = "📂",
                            label = "Recents",
                            intent = recentsIntent,
                            backgroundProvider = GlanceTheme.colors.secondaryContainer,
                            textColorProvider = GlanceTheme.colors.onSecondaryContainer,
                            modifier = GlanceModifier.defaultWeight()
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetActionButton(
    icon: String,
    label: String,
    intent: Intent,
    backgroundProvider: ColorProvider,
    textColorProvider: ColorProvider,
    modifier: GlanceModifier = GlanceModifier
) {
    Column(
        modifier = modifier
            .background(backgroundProvider)
            .cornerRadius(12.dp)
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .clickable(actionStartActivity(intent)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            style = TextStyle(fontSize = 18.sp)
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = label,
            style = TextStyle(
                color = textColorProvider,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

class ScanVaultGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScanVaultGlanceWidget()
}
