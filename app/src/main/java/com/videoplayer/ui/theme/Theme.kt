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

    // For backward compatibility - these can be called from @Composable context
    val gold: androidx.compose.ui.graphics.Color
        @Composable get() = getColors().gold

    val goldAlpha50: androidx.compose.ui.graphics.Color
        @Composable get() = getColors().goldAlpha50

    val darkBackground: androidx.compose.ui.graphics.Color
        @Composable get() = getColors().darkBackground

    val cardBackground: androidx.compose.ui.graphics.Color
        @Composable get() = getColors().cardBackground

    val selectedBackground: androidx.compose.ui.graphics.Color
        @Composable get() = getColors().selectedBackground

    val epgOpenBackground: androidx.compose.ui.graphics.Color
        @Composable get() = getColors().epgOpenBackground

    val textPrimary: androidx.compose.ui.graphics.Color
        @Composable get() = getColors().textPrimary

    val textSecondary: androidx.compose.ui.graphics.Color
        @Composable get() = getColors().textSecondary

    val textHint: androidx.compose.ui.graphics.Color
        @Composable get() = getColors().textHint

    val textDisabled: androidx.compose.ui.graphics.Color
        @Composable get() = getColors().textDisabled

    val statusPlaying: androidx.compose.ui.graphics.Color
        @Composable get() = getColors().statusPlaying
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
