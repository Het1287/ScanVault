package com.example.ownscan

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

class MainActivity : FragmentActivity() {
    private val activeIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            activeIntent.value = intent
            LockedDocCryptoManager.wipeDecryptedSession(applicationContext)
        }
        val earlyPrefs = getSharedPreferences("scanvault_settings", Context.MODE_PRIVATE)
        if (earlyPrefs.getBoolean("flag_secure", false)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContent {
            val prefs = remember { getSharedPreferences("scanvault_settings", Context.MODE_PRIVATE) }
            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
            var selectedAccentHex by remember { mutableStateOf(prefs.getLong("accent_color", 0xFF0D84FFL)) }
            var isFlagSecureEnabled by remember { mutableStateOf(prefs.getBoolean("flag_secure", false)) }
            var isDynamicColorEnabled by remember { mutableStateOf(prefs.getBoolean("dynamic_color", false)) }

            // Phase 10h: Screen Security (FLAG_SECURE)
            LaunchedEffect(isFlagSecureEnabled) {
                if (isFlagSecureEnabled) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            val hasSignedInAccount = remember { DriveBackupManager.getSignedInAccount(this@MainActivity) != null }
            val hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false)
            var isFirstLaunch by remember {
                mutableStateOf(!hasSignedInAccount && (!hasSeenOnboarding || prefs.getBoolean("is_first_launch", true)))
            }

            val context = androidx.compose.ui.platform.LocalContext.current
            val dynamicScheme = if (isDynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else null

            val effectiveAccent = remember(selectedAccentHex, dynamicScheme) {
                dynamicScheme?.primary ?: Color(selectedAccentHex)
            }
            val appColors = remember(isDarkTheme, effectiveAccent, dynamicScheme) {
                val base = buildAppColors(isDarkTheme, effectiveAccent)
                if (dynamicScheme != null) {
                    base.copy(
                        background = dynamicScheme.background,
                        surface = dynamicScheme.surface,
                        cardBackground = dynamicScheme.surfaceVariant,
                        cardSubtle = dynamicScheme.surfaceVariant.copy(alpha = 0.6f),
                        accent = dynamicScheme.primary
                    )
                } else {
                    base
                }
            }

            CompositionLocalProvider(LocalAppColors provides appColors) {
                MaterialTheme(
                    colorScheme = if (dynamicScheme != null) {
                        dynamicScheme
                    } else if (isDarkTheme) {
                        darkColorScheme(
                            background = appColors.background,
                            surface = appColors.surface,
                            primary = appColors.accent
                        )
                    } else {
                        lightColorScheme(
                            background = appColors.background,
                            surface = appColors.surface,
                            primary = appColors.accent
                        )
                    }
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                        AppLockManager.recordUserInteraction()
                                    }
                                }
                            },
                        color = appColors.background
                    ) {
                        var isAppUnlocked by remember { mutableStateOf(!AppLockManager.isAppLockEnabled(this@MainActivity)) }

                        if (isFirstLaunch) {
                            OnboardingAuthScreen(
                                activity = this,
                                onFinish = {
                                    prefs.edit()
                                        .putBoolean("is_first_launch", false)
                                        .putBoolean("has_seen_onboarding", true)
                                        .apply()
                                    isFirstLaunch = false
                                }
                            )
                        } else if (!isAppUnlocked) {
                            AppLockGateScreen(
                                activity = this@MainActivity,
                                colors = appColors,
                                onUnlocked = {
                                    AppLockManager.onAppUnlocked()
                                    isAppUnlocked = true
                                }
                            )
                        } else {
                            MainApp(
                                activity = this,
                                incomingIntent = activeIntent.value,
                                onIntentHandled = { activeIntent.value = null },
                                isDarkTheme = isDarkTheme,
                                selectedAccentHex = selectedAccentHex,
                                isFlagSecureEnabled = isFlagSecureEnabled,
                                onToggleFlagSecure = { enabled ->
                                    isFlagSecureEnabled = enabled
                                    prefs.edit().putBoolean("flag_secure", enabled).apply()
                                },
                                isDynamicColorEnabled = isDynamicColorEnabled,
                                onToggleDynamicColor = { enabled ->
                                    isDynamicColorEnabled = enabled
                                    prefs.edit().putBoolean("dynamic_color", enabled).apply()
                                },
                                onToggleTheme = {
                                    isDarkTheme = it
                                    prefs.edit().putBoolean("dark_theme", it).apply()
                                },
                                onSelectAccent = { hex ->
                                    selectedAccentHex = hex
                                    prefs.edit().putLong("accent_color", hex).apply()
                                },
                                onRelockApp = {
                                    isAppUnlocked = false
                                   }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeIntent.value = intent
    }
}
