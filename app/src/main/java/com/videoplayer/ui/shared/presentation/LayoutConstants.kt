package com.videoplayer.ui.shared.presentation

import androidx.compose.ui.unit.dp

/**
 * Centralized UI layout constants
 * These values are used across the app for consistent spacing, sizing, and layout
 */
object LayoutConstants {
    // Panel dimensions
    val PlaylistPanelWidth = 400.dp
    val EpgPanelWidth = 500.dp
    val EpgPanelMaxHeight = 0.8f // fraction of screen height
    val ProgramDetailsPanelWidth = 500.dp
    val ProgramDetailsPanelMaxHeight = 0.8f

    // Spacing
    val DefaultPadding = 16.dp
    val SmallPadding = 8.dp
    val LargePadding = 24.dp
    val CardPadding = 12.dp
    val CardHorizontalPadding = 16.dp
    val CardVerticalPadding = 12.dp

    // List item dimensions
    val ChannelLogoSize = 48.dp
    val ListItemHeight = 72.dp
    val ListItemPadding = 12.dp
    val ListItemSpacing = 8.dp

    // EPG item dimensions
    val EpgItemMinHeight = 60.dp
    val EpgItemPadding = 12.dp

    // Button dimensions
    val ButtonHeight = 48.dp
    val IconButtonSize = 40.dp
    val ControlButtonSize = 56.dp

    // Card styling
    val CardBorderWidth = 2.dp
    val CardCornerRadius = 16.dp
    val CardElevation = 24.dp

    // Progress bar customization
    val ProgressBarVerticalOffset = 12f // dp
    val ProgressBarHorizontalMargin = 120f // dp

    // Overlay
    val OverlayPadding = 16.dp
    val NotificationTopPadding = 32.dp
    val NotificationCornerRadius = 12.dp
    val NotificationHorizontalPadding = 16.dp
    val NotificationVerticalPadding = 8.dp

    // Header/Toolbar
    val ToolbarHeight = 56.dp
    val HeaderHorizontalPadding = 16.dp
}
