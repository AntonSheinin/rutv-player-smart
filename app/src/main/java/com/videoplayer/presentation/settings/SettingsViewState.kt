package com.videoplayer.presentation.settings

import com.videoplayer.data.model.PlayerConfig
import com.videoplayer.data.model.PlaylistSource

/**
 * UI State for SettingsActivity
 */
data class SettingsViewState(
    val playlistSource: PlaylistSource = PlaylistSource.None,
    val epgUrl: String = "",
    val epgDaysAhead: Int = 7,
    val playerConfig: PlayerConfig = PlayerConfig(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
) {
    val playlistInfo: String
        get() = when (playlistSource) {
            is PlaylistSource.File -> "Loaded from file (stored locally)"
            is PlaylistSource.Url -> "Loaded from URL"
            is PlaylistSource.None -> "No playlist loaded"
        }

    val playlistUrl: String?
        get() = when (playlistSource) {
            is PlaylistSource.Url -> playlistSource.url
            else -> null
        }

    val fileName: String
        get() = when (playlistSource) {
            is PlaylistSource.File -> "File"
            else -> "None"
        }

    val urlName: String
        get() = when (playlistSource) {
            is PlaylistSource.Url -> playlistSource.url.substringAfterLast('/').take(20)
            else -> "None"
        }
}
