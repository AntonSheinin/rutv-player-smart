package com.rutv.ui.mobile.screens

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.data.model.PlayerConfig
import com.rutv.data.model.ResizeMode
import com.rutv.domain.usecase.ChannelListMode
import com.rutv.presentation.main.ArchivePrompt
import com.rutv.presentation.main.MainViewState
import com.rutv.presentation.main.ParentalPinPrompt
import com.rutv.presentation.player.DebugMessage
import com.rutv.presentation.player.PlayerState
import com.rutv.presentation.player.ProgramPlaybackProgress
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList

/**
 * Lightweight snapshot of the fields PlayerScreen actually needs.
 * Reduces recomposition churn by keeping only the frequently changing leaf values.
 */
@Immutable
data class PlayerUiState(
    val allChannels: ImmutableList<Channel>,
    val filteredChannels: ImmutableList<Channel>,
    val visibleChannels: ImmutableList<Channel>,
    val channelListMode: ChannelListMode,
    val selectedGroup: String?,
    val hasChannels: Boolean,
    val currentChannel: Channel?,
    val currentChannelIndex: Int,
    val currentChannelFilteredIndex: Int,
    val currentProgram: EpgProgram?,
    val programDvrProgram: EpgProgram?,
    val selectedProgramDetails: EpgProgram?,
    val isArchivePlayback: Boolean,
    val isTimeshiftPlayback: Boolean,
    val programProgress: ProgramPlaybackProgress?,
    val showPlaylist: Boolean,
    val showEpgPanel: Boolean,
    val isEpgLoading: Boolean,
    val epgPrograms: ImmutableList<EpgProgram>,
    val epgChannelTvgId: String,
    val epgChannel: Channel?,
    val epgDaysPast: Int,
    val epgDaysAhead: Int,
    val epgLoadedFromUtc: Long,
    val epgLoadedToUtc: Long,
    val currentProgramsMap: ImmutableMap<String, EpgProgram?>,
    val showCurrentProgramInChannelList: Boolean,
    val channelPreviewEnabled: Boolean,
    val parentalPinPrompt: ParentalPinPrompt?,
    val temporarilyUnlockedChannelUrl: String?,
    val showDebugLog: Boolean,
    val playerConfig: PlayerConfig,
    val debugMessages: ImmutableList<DebugMessage>,
    val archivePrompt: ArchivePrompt?,
    val epgNotificationMessage: String?,
    val currentResizeMode: ResizeMode,
    val showStartupSplash: Boolean,
    val playerState: PlayerState,
    val lastPlaylistScrollIndex: Int
)

@Immutable
data class PlayerUiActions(
    val onPlayChannel: (Int) -> Unit,
    val onToggleFavorite: (String) -> Unit,
    val onToggleChannelLock: (Int) -> Unit,
    val onShowEpgForChannel: (String) -> Unit,
    val onSubmitParentalPin: (String) -> Unit,
    val onDismissParentalPinPrompt: () -> Unit,
    val onOpenChosenChannelList: () -> Unit,
    val onOpenFullChannelList: () -> Unit,
    val onOpenFavoritesChannelList: () -> Unit,
    val onClosePlaylist: () -> Unit,
    val onCloseEpgPanel: () -> Unit,
    val onCycleAspectRatio: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onShowChannelGroups: () -> Unit,
    val onShowProgramDetails: (EpgProgram) -> Unit,
    val onPlayArchiveProgram: (EpgProgram) -> Unit,
    val onReturnToLive: () -> Unit,
    val onRestartPlayback: () -> Unit,
    val onSeekBack: () -> Unit,
    val onSeekForward: () -> Unit,
    val onSeekBackOneMinute: () -> Unit,
    val onSeekForwardOneMinute: () -> Unit,
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
    val onEnsureEpgDateRange: (Long, Long) -> Unit,
    val onVisibleChannelsChanged: (List<String>) -> Unit
)

@Composable
fun rememberPlayerUiState(viewState: MainViewState): PlayerUiState {
    return remember(viewState) {
        val epgChannel = viewState.channels.firstOrNull { it.tvgId == viewState.epgChannelTvgId }
            ?: viewState.currentChannel

        val visibleChannels = if (viewState.visibleChannelCount >= viewState.filteredChannels.size) {
            viewState.filteredChannels
        } else {
            viewState.filteredChannels.take(viewState.visibleChannelCount).toImmutableList()
        }

        PlayerUiState(
            allChannels = viewState.channels,
            filteredChannels = viewState.filteredChannels,
            visibleChannels = visibleChannels,
            channelListMode = viewState.channelListMode,
            selectedGroup = viewState.selectedGroup,
            hasChannels = viewState.hasChannels,
            currentChannel = viewState.currentChannel,
            currentChannelIndex = viewState.currentChannelIndex,
            currentChannelFilteredIndex = viewState.currentChannelFilteredIndex,
            currentProgram = viewState.currentProgram,
            programDvrProgram = viewState.programDvrProgram,
            selectedProgramDetails = viewState.selectedProgramDetails,
            isArchivePlayback = viewState.isArchivePlayback,
            isTimeshiftPlayback = viewState.isTimeshiftPlayback,
            programProgress = viewState.programProgress,
            showPlaylist = viewState.showPlaylist,
            showEpgPanel = viewState.showEpgPanel,
            isEpgLoading = viewState.isEpgLoading,
            epgPrograms = viewState.epgPrograms,
            epgChannelTvgId = viewState.epgChannelTvgId,
            epgChannel = epgChannel,
            epgDaysPast = viewState.epgDaysPast,
            epgDaysAhead = viewState.epgDaysAhead,
            epgLoadedFromUtc = viewState.epgLoadedFromUtc,
            epgLoadedToUtc = viewState.epgLoadedToUtc,
            currentProgramsMap = viewState.currentProgramsMap,
            showCurrentProgramInChannelList = viewState.showCurrentProgramInChannelList,
            channelPreviewEnabled = viewState.channelPreviewEnabled,
            parentalPinPrompt = viewState.parentalPinPrompt,
            temporarilyUnlockedChannelUrl = viewState.temporarilyUnlockedChannelUrl,
            showDebugLog = viewState.showDebugLog,
            playerConfig = viewState.playerConfig,
            debugMessages = viewState.debugMessages,
            archivePrompt = viewState.archivePrompt,
            epgNotificationMessage = viewState.epgNotificationMessage,
            currentResizeMode = viewState.currentResizeMode,
            showStartupSplash = viewState.showStartupSplash,
            playerState = viewState.playerState,
            lastPlaylistScrollIndex = viewState.lastPlaylistScrollIndex
        )
    }
}
