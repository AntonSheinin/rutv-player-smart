package com.rutv.ui.mobile.screens

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.presentation.main.ArchivePrompt
import com.rutv.presentation.main.MainViewState
import com.rutv.presentation.player.DebugMessage
import com.rutv.presentation.player.PlayerState

/**
 * Lightweight snapshot of the fields PlayerScreen actually needs.
 * Reduces recomposition churn by keeping only the frequently changing leaf values.
 */
@Immutable
data class PlayerUiState(
    val allChannels: List<Channel>,
    val filteredChannels: List<Channel>,
    val visibleChannels: List<Channel>,
    val playlistTitleResId: Int,
    val hasChannels: Boolean,
    val currentChannel: Channel?,
    val currentChannelIndex: Int,
    val currentProgram: EpgProgram?,
    val archiveProgram: EpgProgram?,
    val selectedProgramDetails: EpgProgram?,
    val isArchivePlayback: Boolean,
    val isTimeshiftPlayback: Boolean,
    val showPlaylist: Boolean,
    val showEpgPanel: Boolean,
    val epgPrograms: List<EpgProgram>,
    val epgChannelTvgId: String,
    val epgChannel: Channel?,
    val epgDaysPast: Int,
    val epgDaysAhead: Int,
    val epgLoadedFromUtc: Long,
    val epgLoadedToUtc: Long,
    val currentProgramsMap: Map<String, EpgProgram?>,
    val showDebugLog: Boolean,
    val debugMessages: List<DebugMessage>,
    val archivePrompt: ArchivePrompt?,
    val epgNotificationMessage: String?,
    val currentResizeMode: Int,
    val playerState: PlayerState,
    val lastPlaylistScrollIndex: Int
)

@Immutable
data class PlayerUiActions(
    val onPlayChannel: (Int) -> Unit,
    val onToggleFavorite: (String) -> Unit,
    val onShowEpgForChannel: (String) -> Unit,
    val onTogglePlaylist: () -> Unit,
    val onToggleFavorites: () -> Unit,
    val onClosePlaylist: () -> Unit,
    val onCloseEpgPanel: () -> Unit,
    val onCycleAspectRatio: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onGoToChannel: () -> Unit,
    val onShowProgramDetails: (EpgProgram) -> Unit,
    val onPlayArchiveProgram: (EpgProgram) -> Unit,
    val onReturnToLive: () -> Unit,
    val onRestartPlayback: () -> Unit,
    val onSeekBack: () -> Unit,
    val onSeekForward: () -> Unit,
    val onPausePlayback: () -> Unit,
    val onResumePlayback: () -> Unit,
    val onArchivePromptContinue: () -> Unit,
    val onArchivePromptBackToLive: () -> Unit,
    val onCloseProgramDetails: () -> Unit,
    val onLoadMoreEpgPast: () -> Unit,
    val onLoadMoreEpgFuture: () -> Unit,
    val onClearEpgNotification: () -> Unit,
    val onUpdatePlaylistScrollIndex: (Int) -> Unit,
    val onRequestMoreChannels: (Int) -> Unit,
    val onEnsureEpgDateRange: (Long, Long) -> Unit
)

@Composable
fun rememberPlayerUiState(viewState: MainViewState): PlayerUiState {
    val epgChannel = remember(viewState.epgChannelTvgId, viewState.channels, viewState.currentChannel) {
        viewState.channels.firstOrNull { it.tvgId == viewState.epgChannelTvgId }
            ?: viewState.currentChannel
    }

    val visibleChannels = remember(viewState.filteredChannels, viewState.visibleChannelCount) {
        if (viewState.visibleChannelCount >= viewState.filteredChannels.size) {
            viewState.filteredChannels
        } else {
            viewState.filteredChannels.take(viewState.visibleChannelCount)
        }
    }

    return remember(
        viewState.filteredChannels,
        viewState.visibleChannelCount,
        viewState.playlistTitleResId,
        viewState.hasChannels,
        viewState.currentChannel,
        viewState.currentChannelIndex,
        viewState.currentProgram,
        viewState.archiveProgram,
        viewState.selectedProgramDetails,
        viewState.isArchivePlayback,
        viewState.isTimeshiftPlayback,
        viewState.showPlaylist,
        viewState.showEpgPanel,
        viewState.epgPrograms,
        viewState.epgChannelTvgId,
        epgChannel,
        viewState.epgDaysPast,
        viewState.epgDaysAhead,
        viewState.epgLoadedFromUtc,
        viewState.epgLoadedToUtc,
        viewState.currentProgramsMap,
        viewState.showDebugLog,
        viewState.debugMessages,
        viewState.archivePrompt,
        viewState.epgNotificationMessage,
        viewState.currentResizeMode,
        viewState.playerState,
        viewState.lastPlaylistScrollIndex
    ) {
        PlayerUiState(
            allChannels = viewState.channels,
            filteredChannels = viewState.filteredChannels,
            visibleChannels = visibleChannels,
            playlistTitleResId = viewState.playlistTitleResId,
            hasChannels = viewState.hasChannels,
            currentChannel = viewState.currentChannel,
            currentChannelIndex = viewState.currentChannelIndex,
            currentProgram = viewState.currentProgram,
            archiveProgram = viewState.archiveProgram,
            selectedProgramDetails = viewState.selectedProgramDetails,
            isArchivePlayback = viewState.isArchivePlayback,
            isTimeshiftPlayback = viewState.isTimeshiftPlayback,
            showPlaylist = viewState.showPlaylist,
            showEpgPanel = viewState.showEpgPanel,
            epgPrograms = viewState.epgPrograms,
            epgChannelTvgId = viewState.epgChannelTvgId,
            epgChannel = epgChannel,
            epgDaysPast = viewState.epgDaysPast,
            epgDaysAhead = viewState.epgDaysAhead,
            epgLoadedFromUtc = viewState.epgLoadedFromUtc,
            epgLoadedToUtc = viewState.epgLoadedToUtc,
            currentProgramsMap = viewState.currentProgramsMap,
            showDebugLog = viewState.showDebugLog,
            debugMessages = viewState.debugMessages,
            archivePrompt = viewState.archivePrompt,
            epgNotificationMessage = viewState.epgNotificationMessage,
            currentResizeMode = viewState.currentResizeMode,
            playerState = viewState.playerState,
            lastPlaylistScrollIndex = viewState.lastPlaylistScrollIndex
        )
    }
}
