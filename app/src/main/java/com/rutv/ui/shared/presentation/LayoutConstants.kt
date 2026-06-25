package com.rutv.ui.shared.presentation

import androidx.compose.ui.unit.dp
import com.rutv.util.PlayerConstants

/**
 * Centralized UI layout constants
 * These values are used across the app for consistent spacing, sizing, and layout
 */
object LayoutConstants {
    val CompactPlayerBreakpoint = 840.dp
    val MinTouchTarget = 48.dp
    const val DefaultChannelEpgRatioPercent = PlayerConstants.DEFAULT_CHANNEL_EPG_RATIO_PERCENT
    const val MinChannelListRatioPercent = PlayerConstants.MIN_CHANNEL_LIST_RATIO_PERCENT
    const val MinEpgListRatioPercent = PlayerConstants.MIN_EPG_LIST_RATIO_PERCENT
    const val ChannelEpgRatioStepPercent = PlayerConstants.CHANNEL_EPG_RATIO_STEP_PERCENT
    val ChannelEpgPanelGap = 16.dp
    const val DefaultListPanelEdgeInsetDp = PlayerConstants.DEFAULT_LIST_PANEL_EDGE_INSET_DP
    const val MinListPanelEdgeInsetDp = PlayerConstants.MIN_LIST_PANEL_EDGE_INSET_DP
    const val MaxListPanelEdgeInsetDp = PlayerConstants.MAX_LIST_PANEL_EDGE_INSET_DP
    const val ListPanelEdgeInsetStepDp = PlayerConstants.LIST_PANEL_EDGE_INSET_STEP_DP
    const val DefaultListPanelVerticalInsetDp = PlayerConstants.DEFAULT_LIST_PANEL_VERTICAL_INSET_DP
    const val MinListPanelVerticalInsetDp = PlayerConstants.MIN_LIST_PANEL_VERTICAL_INSET_DP
    const val MaxListPanelVerticalInsetDp = PlayerConstants.MAX_LIST_PANEL_VERTICAL_INSET_DP
    const val ListPanelVerticalInsetStepDp = PlayerConstants.LIST_PANEL_VERTICAL_INSET_STEP_DP
    const val DefaultChannelPreviewSizePreset = PlayerConstants.DEFAULT_CHANNEL_PREVIEW_SIZE_PRESET
    val ChannelPreviewWidthPresets = PlayerConstants.CHANNEL_PREVIEW_WIDTH_PRESETS_DP.map { it.dp }

    // Panel dimensions
    val PlaylistPanelWidth = 400.dp
    val EpgPanelWidth = 560.dp
    val ProgramDetailsPanelWidth = 500.dp
    val ProgramDetailsPanelMaxHeight = 0.8f

    // Spacing
    val DefaultPadding = 16.dp
    val SmallPadding = 8.dp
    val LargePadding = 24.dp
    val CardHorizontalPadding = 16.dp

    // Button dimensions
    val ControlButtonSize = 56.dp

    // EPG items
    val EpgItemHeight = 48.dp
    val EpgTabletItemHeight = 56.dp
    val EpgTabletWidthBreakpoint = 600.dp

    // Overlay
    val NotificationTopPadding = 32.dp
    val NotificationCornerRadius = 12.dp
    val NotificationHorizontalPadding = 16.dp
    val NotificationVerticalPadding = 8.dp

    // Header/Toolbar
    val ToolbarHeight = 56.dp
    val HeaderHorizontalPadding = 16.dp
}
