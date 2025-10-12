package com.videoplayer.presentation.main

import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.presentation.player.DebugMessage
import com.videoplayer.presentation.player.PlayerState

/**
 * UI State for MainActivity
 */
data class MainViewState(
    val channels: List<Channel> = emptyList(),
    val currentChannel: Channel? = null,
    val currentChannelIndex: Int = -1,
    val playerState: PlayerState = PlayerState.Idle,
    val showPlaylist: Boolean = false,
    val showFavoritesOnly: Boolean = false,
    val showEpgPanel: Boolean = false,
    val epgPrograms: List<EpgProgram> = emptyList(),
    val currentProgram: EpgProgram? = null,
    val debugMessages: List<DebugMessage> = emptyList(),
    val showDebugLog: Boolean = true,
    val currentResizeMode: Int = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val videoRotation: Float = 0f,
    val isLoading: Boolean = false,
    val error: String? = null,
    val epgLoadedTimestamp: Long = 0L // Timestamp when EPG was last loaded, used to trigger adapter refresh
) {
    val filteredChannels: List<Channel>
        get() = if (showFavoritesOnly) {
            channels.filter { it.isFavorite }
        } else {
            channels
        }

    val playlistTitle: String
        get() = if (showFavoritesOnly) "Favorites" else "Channels"

    val hasChannels: Boolean
        get() = channels.isNotEmpty()

    val hasError: Boolean
        get() = error != null
}
