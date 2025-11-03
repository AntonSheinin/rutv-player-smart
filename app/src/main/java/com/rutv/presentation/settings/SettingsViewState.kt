package com.rutv.presentation.settings

import com.rutv.R
import com.rutv.data.model.PlayerConfig
import com.rutv.data.model.PlaylistSource

/**
 * UI State for SettingsActivity
 */
data class SettingsViewState(
    val playlistSource: PlaylistSource = PlaylistSource.None,
    val epgUrl: String = "",
    val epgDaysAhead: Int = 7,
    val epgDaysPast: Int = 14,
    val epgPageDays: Int = 1,
    val playerConfig: PlayerConfig = PlayerConfig(),
    val selectedLanguage: String = "en",
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
) {
    val playlistInfoResId: Int
        get() = when (playlistSource) {
            is PlaylistSource.File -> R.string.settings_playlist_info_file
            is PlaylistSource.Url -> R.string.settings_playlist_info_url
            is PlaylistSource.None -> R.string.settings_playlist_info_none
        }

    val playlistUrl: String?
        get() = when (playlistSource) {
            is PlaylistSource.Url -> playlistSource.url
            else -> null
        }

    val fileName: String?
        get() = when (val source = playlistSource) {
            is PlaylistSource.File -> source.displayName?.takeIf { it.isNotBlank() }
            else -> null
        }

    val urlName: String
        get() = when (val source = playlistSource) {
            is PlaylistSource.Url -> {
                val lastSegment = source.url.substringAfterLast('/').takeIf { it.isNotBlank() }
                lastSegment ?: source.url
            }
            else -> ""
        }
}
