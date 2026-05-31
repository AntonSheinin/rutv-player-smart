package com.rutv.data.model

import com.rutv.util.PlayerConstants

/**
 * Represents the source of a playlist
 */
sealed class PlaylistSource {
    data class File(val content: String, val displayName: String? = null) : PlaylistSource()
    data class Url(val url: String) : PlaylistSource()
    object None : PlaylistSource()

    companion object {
        const val TYPE_FILE = "file"
        const val TYPE_URL = "url"
    }
}

/**
 * Player configuration
 */
data class PlayerConfig(
    val useFfmpegAudio: Boolean = false,
    val useFfmpegVideo: Boolean = false,
    val bufferSeconds: Int = PlayerConstants.DEFAULT_BUFFER_SECONDS,
    val controlsHideDelaySeconds: Int = PlayerConstants.DEFAULT_CONTROLS_HIDE_DELAY_SECONDS,
    val showDebugLog: Boolean = false
)
