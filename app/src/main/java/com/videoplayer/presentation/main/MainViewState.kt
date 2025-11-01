package com.videoplayer.presentation.main

import androidx.compose.runtime.Immutable
import androidx.media3.common.util.UnstableApi
import com.videoplayer.R
import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.presentation.player.DebugMessage
import com.videoplayer.presentation.player.PlayerState

/**
 * UI State for MainActivity
 * Marked as Immutable to optimize recomposition - all properties are read-only
 */
@UnstableApi
@Immutable
data class MainViewState(
    val channels: List<Channel> = emptyList(),
    val currentChannel: Channel? = null,
    val currentChannelIndex: Int = -1,
    val playerState: PlayerState = PlayerState.Idle,
    val showPlaylist: Boolean = false,
    val showFavoritesOnly: Boolean = false,
    val showEpgPanel: Boolean = false,
    val epgChannelTvgId: String = "", // TVG ID of channel whose EPG is open
    val epgPrograms: List<EpgProgram> = emptyList(),
    val epgLoadedFromUtc: Long = 0L,
    val epgLoadedToUtc: Long = 0L,
    val currentProgram: EpgProgram? = null,
    val selectedProgramDetails: EpgProgram? = null, // Program selected for details view
    val currentProgramsMap: Map<String, EpgProgram?> = emptyMap(),
    val isArchivePlayback: Boolean = false,
    val isTimeshiftPlayback: Boolean = false,
    val archiveProgram: EpgProgram? = null,
    val archivePrompt: ArchivePrompt? = null,
    val debugMessages: List<DebugMessage> = emptyList(),
    val showDebugLog: Boolean = false,
    val currentResizeMode: Int = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val isLoading: Boolean = false,
    val error: String? = null,
    val epgNotificationMessage: String? = null,
    val epgLoadedTimestamp: Long = 0L // Timestamp when EPG was last loaded, used to trigger adapter refresh
) {
    val filteredChannels: List<Channel>
        get() = if (showFavoritesOnly) {
            channels.filter { it.isFavorite }
        } else {
            channels
        }

    val playlistTitleResId: Int
        get() = if (showFavoritesOnly) R.string.playlist_title_favorites else R.string.playlist_title_channels

    val hasChannels: Boolean
        get() = channels.isNotEmpty()
}

@UnstableApi
@Immutable
data class ArchivePrompt(
    val channel: Channel,
    val currentProgram: EpgProgram,
    val nextProgram: EpgProgram?
)
