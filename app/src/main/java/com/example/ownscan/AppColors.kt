package com.example.ownscan

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

// -------------------------------------------------------------
// DYNAMIC HIGH-CONTRAST PALETTE SYSTEM
// -------------------------------------------------------------
data class AppColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val cardBackground: Color,
    val cardSubtle: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentGreen: Color,
    val accentAmber: Color,
    val accentRed: Color,
    val divider: Color
)

val LocalAppColors = staticCompositionLocalOf<AppColors> {
    error("No AppColors provided")
}

data class AccentPreset(val name: String, val colorLong: Long)

val PresetAccents = listOf(
    AccentPreset("Blue", 0xFF0D84FFL),
    AccentPreset("Emerald", 0xFF10B981L),
    AccentPreset("Purple", 0xFFAF52DEL),
    AccentPreset("Coral", 0xFFFF6B22L),
    AccentPreset("Amber", 0xFFFFB340L),
    AccentPreset("Teal", 0xFF14B8A6L),
    AccentPreset("Rose", 0xFFF43F5EL),
    AccentPreset("Indigo", 0xFF6366F1L),
    AccentPreset("Cyan", 0xFF06B6D4L),
    AccentPreset("Violet", 0xFF8B5CF6L)
)

fun buildAppColors(isDark: Boolean, accentColor: Color): AppColors {
    return if (isDark) {
        AppColors(
            isDark = true,
            background = Color(0xFF14161A),
            surface = Color(0xFF1E2129),
            cardBackground = Color(0xFF22262E),
            cardSubtle = Color(0xFF191C23),
            cardBorder = Color(0xFF333844),
            textPrimary = Color(0xFFFFFFFF),
            textSecondary = Color(0xFFD1D5DB),
            textMuted = Color(0xFF8E95A5),
            accent = accentColor,
            accentGreen = Color(0xFF30D158),
            accentAmber = Color(0xFFFFB340),
            accentRed = Color(0xFFFF453A),
            divider = Color(0xFF2C313C)
        )
    } else {
        AppColors(
            isDark = false,
            background = Color(0xFFF3F5F9),
            surface = Color(0xFFFFFFFF),
            cardBackground = Color(0xFFFFFFFF),
            cardSubtle = Color(0xFFE9EDF4),
            cardBorder = Color(0xFFD3D8E2),
            textPrimary = Color(0xFF111827),
            textSecondary = Color(0xFF374151),
            textMuted = Color(0xFF6B7280),
            accent = accentColor,
            accentGreen = Color(0xFF16A34A),
            accentAmber = Color(0xFFD97706),
            accentRed = Color(0xFFDC2626),
            divider = Color(0xFFE5E7EB)
        )
    }
}

val thumbnailMemoryCache = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
) {
    override fun sizeOf(key: String, bitmap: Bitmap): Int {
        return bitmap.byteCount / 1024
    }
}

