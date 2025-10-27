package com.videoplayer.util

object Constants {
    // Buffering
    const val BUFFERING_TIMEOUT_MS = 30_000L
    const val MIN_BUFFER_MS = 4_000
    const val MAX_BUFFER_MS = 15_000
    const val BUFFER_FOR_PLAYBACK_MS = 1_000
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

    // UI
    const val DOUBLE_TAP_DELAY_MS = 300L
    const val CONTROLLER_AUTO_HIDE_TIMEOUT_MS = 2_000

    // Playlist
    const val MAX_PLAYLIST_SIZE_BYTES = 500_000
    const val CHANNEL_LOGO_SIZE_DP = 48

    // EPG
    const val EPG_CONNECT_TIMEOUT_MS = 180_000
    const val EPG_READ_TIMEOUT_MS = 180_000
    const val EPG_HEALTH_TIMEOUT_MS = 5_000

    // Network
    const val HTTP_CONNECT_TIMEOUT_MS = 15_000
    const val HTTP_READ_TIMEOUT_MS = 15_000
    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"

    // Player
    const val VIDEO_ROTATION_0 = 0f
    const val VIDEO_ROTATION_270 = 270f
    const val SEEK_INCREMENT_MS = 10_000L

    // Buffer settings
    const val MIN_BUFFER_SECONDS = 5
    const val MAX_BUFFER_SECONDS = 60
    const val DEFAULT_BUFFER_SECONDS = 15

    // Database
    const val DATABASE_NAME = "rutv_database"
}
