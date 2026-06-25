package com.rutv.util

/**
 * Player-related constants
 */
object PlayerConstants {
    // Buffering
    const val BUFFERING_TIMEOUT_MS = 30_000L
    const val MIN_BUFFER_MS = 4_000
    const val MAX_BUFFER_MS = 15_000
    const val BUFFER_FOR_PLAYBACK_MS = 1_000
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

    // Buffer settings (seconds)
    const val MIN_BUFFER_SECONDS = 5
    const val MAX_BUFFER_SECONDS = 60
    const val DEFAULT_BUFFER_SECONDS = 15

    // Player controls auto-hide delay (seconds)
    const val DEFAULT_CONTROLS_HIDE_DELAY_SECONDS = 5
    const val MIN_CONTROLS_HIDE_DELAY_SECONDS = 1
    const val MAX_CONTROLS_HIDE_DELAY_SECONDS = 30

    // Channel/EPG panel split settings (percent)
    const val DEFAULT_CHANNEL_EPG_RATIO_PERCENT = 40
    const val MIN_CHANNEL_LIST_RATIO_PERCENT = 40
    const val MIN_EPG_LIST_RATIO_PERCENT = 40
    const val CHANNEL_EPG_RATIO_STEP_PERCENT = 5
    const val DEFAULT_LIST_PANEL_EDGE_INSET_DP = 24
    const val MIN_LIST_PANEL_EDGE_INSET_DP = 0
    const val MAX_LIST_PANEL_EDGE_INSET_DP = 48
    const val LIST_PANEL_EDGE_INSET_STEP_DP = 4
    const val DEFAULT_LIST_PANEL_VERTICAL_INSET_DP = 0
    const val MIN_LIST_PANEL_VERTICAL_INSET_DP = 0
    const val MAX_LIST_PANEL_VERTICAL_INSET_DP = 64
    const val LIST_PANEL_VERTICAL_INSET_STEP_DP = 4
    val CHANNEL_PREVIEW_WIDTH_PRESETS_DP = intArrayOf(192, 224, 256, 320, 384)
    const val DEFAULT_CHANNEL_PREVIEW_SIZE_PRESET = 2

    // Player
    const val SEEK_INCREMENT_MS = 10_000L

    // Auto-retry (playback errors)
    const val DEFAULT_AUTO_RETRY_MAX_ATTEMPTS = 10
    const val MIN_AUTO_RETRY_MAX_ATTEMPTS = 1
    const val MAX_AUTO_RETRY_MAX_ATTEMPTS = 50
    const val DEFAULT_AUTO_RETRY_PERIOD_SECONDS = 1
    const val MIN_AUTO_RETRY_PERIOD_SECONDS = 1
    const val MAX_AUTO_RETRY_PERIOD_SECONDS = 30

}
