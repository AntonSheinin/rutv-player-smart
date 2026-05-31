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
