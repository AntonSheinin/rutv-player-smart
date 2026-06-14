package com.rutv.presentation.main

import androidx.compose.runtime.Immutable
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.data.model.PlayerConfig
import com.rutv.data.model.PlaylistSource
import com.rutv.data.model.ResizeMode
import com.rutv.domain.usecase.ChannelListMode
import com.rutv.presentation.player.DebugMessage
import com.rutv.presentation.player.PlayerState
import com.rutv.presentation.player.ProgramPlaybackProgress
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * UI State for MainActivity
 * Marked as Immutable to optimize recomposition — all properties are read-only
 * and collection types use kotlinx-collections-immutable for true stability.
 */
@Immutable
data class MainViewState(
    val channels: ImmutableList<Channel> = persistentListOf(),
    val filteredChannels: ImmutableList<Channel> = persistentListOf(),
    val visibleChannelCount: Int = DEFAULT_VISIBLE_CHANNELS,
    val channelListMode: ChannelListMode = ChannelListMode.Full,
    val selectedGroup: String? = null,
    val currentChannel: Channel? = null,
    val currentChannelIndex: Int = -1,
    val currentChannelFilteredIndex: Int = -1,
    val playerState: PlayerState = PlayerState.Idle,
    val showPlaylist: Boolean = false,
    val showEpgPanel: Boolean = false,
    val isEpgLoading: Boolean = false,
    val epgChannelTvgId: String = "", // TVG ID of channel whose EPG is open
    val epgPrograms: ImmutableList<EpgProgram> = persistentListOf(),
    val epgLoadedFromUtc: Long = 0L,
    val epgLoadedToUtc: Long = 0L,
    val epgDaysPast: Int = 0,
    val epgDaysAhead: Int = 0,
    val currentProgram: EpgProgram? = null,
    val selectedProgramDetails: EpgProgram? = null, // Program selected for details view
    val currentProgramsMap: ImmutableMap<String, EpgProgram?> = persistentMapOf(),
    /**
     * UI performance toggle: when disabled we avoid populating [currentProgramsMap] and the playlist
     * panel won't show "current program" under each channel.
     */
    val showCurrentProgramInChannelList: Boolean = true,
    val channelPreviewEnabled: Boolean = true,
    val parentalPinPrompt: ParentalPinPrompt? = null,
    val showParentalPinSetupDialog: Boolean = false,
    val temporarilyUnlockedChannelUrl: String? = null,
    val isArchivePlayback: Boolean = false,
    val isTimeshiftPlayback: Boolean = false,
    val programDvrProgram: EpgProgram? = null,
    val programProgress: ProgramPlaybackProgress? = null,
    val archivePrompt: ArchivePrompt? = null,
    val debugMessages: ImmutableList<DebugMessage> = persistentListOf(),
    val showDebugLog: Boolean = false,
    val playerConfig: PlayerConfig = PlayerConfig(),
    val currentResizeMode: ResizeMode = ResizeMode.FIT,
    val isLoading: Boolean = false,
    val showStartupSplash: Boolean = true,
    val error: String? = null,
    val epgNotificationMessage: String? = null,
    val playlistSource: PlaylistSource = PlaylistSource.None,
    val lastPlaylistScrollIndex: Int = 0,
    val areControlsVisible: Boolean = false,
    val showCloseAppDialog: Boolean = false
) {
    val hasChannels: Boolean
        get() = channels.isNotEmpty()

    val hasPlaylistSource: Boolean
        get() = playlistSource !is PlaylistSource.None
}

@Immutable
data class ArchivePrompt(
    val channel: Channel,
    val currentProgram: EpgProgram,
    val nextProgram: EpgProgram?
)

@Immutable
data class ParentalPinPrompt(
    val reason: ParentalPinPromptReason,
    val channelTitle: String,
    val channelIsLocked: Boolean,
    val hasError: Boolean = false
)

enum class ParentalPinPromptReason {
    PlayChannel,
    OpenEpg,
    ToggleLock
}

const val DEFAULT_VISIBLE_CHANNELS = 60
