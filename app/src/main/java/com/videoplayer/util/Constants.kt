package com.videoplayer.util

/**
 * General application constants
 * Feature-specific constants moved to EpgConstants and PlayerConstants
 */
object Constants {
    // Playlist
    const val MAX_PLAYLIST_SIZE_BYTES = 500_000
    const val CHANNEL_LOGO_SIZE_DP = 48

    // Network
    const val HTTP_CONNECT_TIMEOUT_MS = 15_000
    const val HTTP_READ_TIMEOUT_MS = 15_000
    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"

    // Database
    const val DATABASE_NAME = "rutv_database"
}
