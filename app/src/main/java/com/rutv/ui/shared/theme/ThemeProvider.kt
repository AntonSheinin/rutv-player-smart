package com.rutv.ui.shared.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable

/**
 * Interface for theme providers
 * Allows different theme implementations (Dark, Light, etc.)
 */
interface ThemeProvider {
    val colorScheme: ColorScheme
    val typography: Typography
    val customColors: CustomColors

    @Composable
    fun applyTheme(content: @Composable () -> Unit)
}

/**
 * Custom colors not covered by Material3 color scheme
 */
data class CustomColors(
    val gold: androidx.compose.ui.graphics.Color,
    val goldAlpha50: androidx.compose.ui.graphics.Color,
    val darkBackground: androidx.compose.ui.graphics.Color,
    val cardBackground: androidx.compose.ui.graphics.Color,
    val selectedBackground: androidx.compose.ui.graphics.Color,
    val epgOpenBackground: androidx.compose.ui.graphics.Color,
    val textPrimary: androidx.compose.ui.graphics.Color,
    val textSecondary: androidx.compose.ui.graphics.Color,
    val textHint: androidx.compose.ui.graphics.Color,
    val textDisabled: androidx.compose.ui.graphics.Color,
    val statusPlaying: androidx.compose.ui.graphics.Color
)
