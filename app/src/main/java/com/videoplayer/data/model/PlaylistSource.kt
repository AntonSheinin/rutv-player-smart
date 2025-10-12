package com.videoplayer.data.model

/**
 * Represents the source of a playlist
 */
sealed class PlaylistSource {
    data class File(val content: String) : PlaylistSource()
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
    val bufferSeconds: Int = 15,
    val showDebugLog: Boolean = true
)

/**
 * App settings
 */
data class AppSettings(
    val playlistSource: PlaylistSource = PlaylistSource.None,
    val epgUrl: String = "",
    val playerConfig: PlayerConfig = PlayerConfig(),
    val lastPlayedIndex: Int = 0
)
