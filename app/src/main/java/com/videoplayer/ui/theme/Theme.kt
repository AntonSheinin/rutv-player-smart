package com.videoplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.videoplayer.ui.shared.theme.DarkThemeProvider
import com.videoplayer.ui.shared.theme.ThemeProvider

/**
 * Extension properties for custom colors not covered by Material3
 * Delegates to current theme provider
 */
object RuTvColors {
    @Composable
    fun getColors(): com.videoplayer.ui.shared.theme.CustomColors {
        return DarkThemeProvider.customColors
    }

    // For backward compatibility
    @Composable
    val gold: androidx.compose.ui.graphics.Color
        get() = getColors().gold

    @Composable
    val goldAlpha50: androidx.compose.ui.graphics.Color
        get() = getColors().goldAlpha50

    @Composable
    val darkBackground: androidx.compose.ui.graphics.Color
        get() = getColors().darkBackground

    @Composable
    val cardBackground: androidx.compose.ui.graphics.Color
        get() = getColors().cardBackground

    @Composable
    val selectedBackground: androidx.compose.ui.graphics.Color
        get() = getColors().selectedBackground

    @Composable
    val epgOpenBackground: androidx.compose.ui.graphics.Color
        get() = getColors().epgOpenBackground

    @Composable
    val textPrimary: androidx.compose.ui.graphics.Color
        get() = getColors().textPrimary

    @Composable
    val textSecondary: androidx.compose.ui.graphics.Color
        get() = getColors().textSecondary

    @Composable
    val textHint: androidx.compose.ui.graphics.Color
        get() = getColors().textHint

    @Composable
    val textDisabled: androidx.compose.ui.graphics.Color
        get() = getColors().textDisabled

    @Composable
    val statusPlaying: androidx.compose.ui.graphics.Color
        get() = getColors().statusPlaying
}

/**
 * Main theme composable
 * Uses ThemeProvider pattern for extensibility
 */
@Composable
fun RuTvTheme(
    themeProvider: ThemeProvider = DarkThemeProvider,
    content: @Composable () -> Unit
) {
    themeProvider.applyTheme(content)
}

/**
 * Extension to access custom colors via MaterialTheme
 * For backward compatibility
 */
@Suppress("UnusedReceiverParameter")
val MaterialTheme.ruTvColors: RuTvColors
    @Composable
    get() = RuTvColors
