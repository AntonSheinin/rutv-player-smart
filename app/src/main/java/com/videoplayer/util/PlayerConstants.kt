package com.videoplayer.util

import androidx.media3.ui.AspectRatioFrameLayout

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

    // UI
    const val DOUBLE_TAP_DELAY_MS = 300L
    const val CONTROLLER_AUTO_HIDE_TIMEOUT_MS = 2_000

    // Player - Simple landscape/portrait toggle
    const val VIDEO_ROTATION_0 = 0f        // Landscape (normal)
    const val VIDEO_ROTATION_90 = 90f      // Portrait (rotated)
    const val SEEK_INCREMENT_MS = 10_000L

    // Aspect Ratio
    val DEFAULT_RESIZE_MODE = AspectRatioFrameLayout.RESIZE_MODE_FIT
}
