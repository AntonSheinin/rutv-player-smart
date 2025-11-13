package com.rutv.ui.mobile.screens

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.annotation.StringRes
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import com.rutv.util.DeviceHelper
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.shared.components.RemoteDialog
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as Media3UiR
import com.rutv.R
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.ui.mobile.screens.PlayerUiState
import com.rutv.ui.mobile.screens.PlayerUiActions
import com.rutv.ui.mobile.screens.rememberPlayerViewHolder
import com.rutv.ui.mobile.screens.rememberRemoteFocusCoordinator
import com.rutv.ui.mobile.components.ChannelListItem
import com.rutv.ui.mobile.components.EpgDateDelimiter
import com.rutv.ui.mobile.components.EpgProgramItem
import com.rutv.ui.shared.components.ArchivePromptDialog
import com.rutv.ui.shared.components.EpgNotificationToast
import com.rutv.ui.shared.components.CustomControlButtons
import com.rutv.ui.theme.ruTvColors
import com.rutv.ui.shared.presentation.TimeFormatter
import com.rutv.ui.shared.presentation.LayoutConstants
import com.rutv.util.PlayerConstants
import android.annotation.SuppressLint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlin.math.max
import java.util.*

/**
 * Main Player Screen with Compose UI
 */
@UnstableApi
@Composable
fun PlayerScreen(
    uiState: PlayerUiState,
    player: ExoPlayer?,
    actions: PlayerUiActions,
    onRegisterToggleControls: ((() -> Unit)) -> Unit,
    onControlsVisibilityChanged: ((Boolean) -> Unit)? = null,
    onLogDebug: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val debugLogger: (String) -> Unit = remember(uiState.showDebugLog, onLogDebug) {
        { message: String ->
            if (uiState.showDebugLog) {
                onLogDebug?.invoke(message)
            }
        }
    }
    val focusCoordinator = rememberRemoteFocusCoordinator(debugLogger)
    var showControls by remember { mutableStateOf(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val controllerVisibilityCallback by rememberUpdatedState<(Boolean) -> Unit> { visible ->
        showControls = visible
    }

    // Store focus requesters for custom controls (for ExoPlayer navigation)
    var leftColumnFocusRequesters by remember { mutableStateOf<List<FocusRequester>?>(null) }
    var rightColumnFocusRequesters by remember { mutableStateOf<List<FocusRequester>?>(null) }
    var lastFocusedPlaylistIndex by remember { mutableIntStateOf(uiState.currentChannelIndex.coerceAtLeast(0)) }
    var lastControlsSignature by remember { mutableStateOf<ControlsSignature?>(null) }
    var epgFocusRequestToken by remember { mutableIntStateOf(0) }
    var epgFocusRequestTargetIndex by remember { mutableStateOf<Int?>(null) }
    var suppressFallbackEpgFocus by remember { mutableStateOf(false) }
    val requestEpgFocus: (Int?) -> Unit = { targetIndex ->
        epgFocusRequestTargetIndex = targetIndex
        epgFocusRequestToken++
    }

    // Callbacks to move focus to custom controls (used by ExoPlayer controls)
    var navigateToFavoritesCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var navigateToRotateCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Toggle controls function - exposed to MainActivity for OK button
    val toggleControls: () -> Unit = {
        val newValue = !showControls
        showControls = newValue
        playerViewRef?.post {
            if (newValue) {
                playerViewRef?.showController()
            } else {
                playerViewRef?.hideController()
            }
        }
    }

    // Register toggle function with parent
    LaunchedEffect(Unit) {
        onRegisterToggleControls(toggleControls)
    }

    // Show controls initially if player is loaded (for first-time users)
    LaunchedEffect(player) {
        if (player != null && !showControls) {
            showControls = true
        }
    }

    LaunchedEffect(showControls, playerViewRef) {
        val currentPlayerView = playerViewRef ?: return@LaunchedEffect
        currentPlayerView.post {
            if (showControls) {
                if (!currentPlayerView.isControllerFullyVisible) {
                    currentPlayerView.showController()
                }
            } else {
                if (currentPlayerView.isControllerFullyVisible) {
                    currentPlayerView.hideController()
                }
            }
        }
    }

    LaunchedEffect(showControls) {
        onControlsVisibilityChanged?.invoke(showControls)
    }

    LaunchedEffect(showControls, playerViewRef) {
       if (!showControls) return@LaunchedEffect
       val playerView = playerViewRef ?: return@LaunchedEffect
        val timeoutMs = PlayerConstants.CONTROLLER_AUTO_HIDE_TIMEOUT_MS.toLong()
        delay(timeoutMs)
        if (showControls) {
            playerView.hideController()
        }
    }

    LaunchedEffect(uiState.showEpgPanel, uiState.showPlaylist) {
        if (uiState.showEpgPanel) {
            requestEpgFocus(null)
        } else if (uiState.showPlaylist) {
            focusCoordinator.requestPlaylistFocus()
        }
    }

    LaunchedEffect(uiState.showEpgPanel) {
        if (!uiState.showEpgPanel) {
            epgFocusRequestTargetIndex = null
            suppressFallbackEpgFocus = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.ruTvColors.darkBackground)
    ) {
        // EPG Notification
        EpgNotificationToast(
            message = uiState.epgNotificationMessage,
            onDismiss = actions.onClearEpgNotification,
            modifier = Modifier
        )

        // ExoPlayer View
        val playerViewHolder = rememberPlayerViewHolder()
        player?.let { exoPlayer ->
            AndroidView(
                factory = {
                    playerViewHolder.obtain().apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        playerViewRef = this
                        this.player = exoPlayer
                        configurePlayerView(uiState, controllerVisibilityCallback)
                    }
                },
                update = { playerView ->
                    playerViewRef = playerView
                    playerView.player = exoPlayer
                    playerView.resizeMode = uiState.currentResizeMode
                    val controlsSignature = ControlsSignature(
                        isArchivePlayback = uiState.isArchivePlayback,
                        programHash = (uiState.archiveProgram ?: uiState.currentProgram)?.hashCode() ?: 0,
                        navigateLeftHash = navigateToFavoritesCallback?.hashCode() ?: 0,
                        navigateRightHash = navigateToRotateCallback?.hashCode() ?: 0
                    )
                    if (controlsSignature != lastControlsSignature) {
                        playerView.bindControls(
                            uiState = uiState,
                            actions = actions,
                            onNavigateLeftToFavorites = navigateToFavoritesCallback,
                            onNavigateRightToRotate = navigateToRotateCallback
                        )
                        lastControlsSignature = controlsSignature
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }


        // Custom Control Buttons Overlay (bottom) - synced with ExoPlayer controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            val focusLeftmostExoControl = {
                playerViewRef?.post {
                    playerViewRef?.focusOnControl(
                        "exo_prev",
                        "exo_rew",
                        "exo_rew_with_amount",
                        "exo_play_pause",
                        "exo_play",
                        "exo_pause"
                    )
                }
            }
            val focusRightmostExoControl = {
                playerViewRef?.post {
                    playerViewRef?.focusOnControl(
                        "exo_ffwd",
                        "exo_ffwd_with_amount",
                        "exo_next",
                        "exo_play_pause",
                        "exo_play",
                        "exo_pause"
                    )
                }
            }
            CustomControlButtons(
                onPlaylistClick = actions.onTogglePlaylist,
                onFavoritesClick = actions.onToggleFavorites,
                onGoToChannelClick = actions.onGoToChannel,
                onAspectRatioClick = actions.onCycleAspectRatio,
                onSettingsClick = actions.onOpenSettings,
                onNavigateRightFromFavorites = { focusLeftmostExoControl() },
                onNavigateLeftFromRotate = { focusRightmostExoControl() },
                onRegisterFocusRequesters = { left, right ->
                    leftColumnFocusRequesters = left
                    rightColumnFocusRequesters = right
                    // Update callbacks for ExoPlayer navigation
                    navigateToFavoritesCallback = {
                        left.getOrNull(1)?.requestFocus()
                    }
                    navigateToRotateCallback = {
                        right.getOrNull(1)?.requestFocus() // Rotate is index 1 in right column
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Channel Info Overlay (top center) - hide with controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            uiState.currentChannel?.let { channel ->
                ChannelInfoOverlay(
                    channelNumber = uiState.currentChannelIndex + 1,
                    channel = channel,
                    currentProgram = uiState.currentProgram,
                    isArchivePlayback = uiState.isArchivePlayback,
                    isTimeshiftPlayback = uiState.isTimeshiftPlayback,
                    archiveProgram = uiState.archiveProgram,
                    onReturnToLive = actions.onReturnToLive,
                    onShowProgramInfo = actions.onShowProgramDetails,
                    modifier = Modifier.padding(LayoutConstants.DefaultPadding)
                )
            }
        }

        // Focus management for panel transitions
        val focusPlaylistFromEpg: () -> Unit = {
            debugLogger("← EPG→Playlist: Transferring focus")

            // Request focus on playlist LazyColumn
            focusCoordinator.requestPlaylistFocus()

            // Also update the focused channel index
            val targetIndex = when {
                lastFocusedPlaylistIndex >= 0 -> lastFocusedPlaylistIndex
                uiState.currentChannelIndex >= 0 -> uiState.currentChannelIndex
                else -> -1
            }
            val resolvedIndex = when {
                targetIndex >= 0 && targetIndex < uiState.filteredChannels.size -> targetIndex
                uiState.filteredChannels.isNotEmpty() -> 0
                else -> -1
            }
            if (resolvedIndex >= 0) {
                debugLogger("← EPG→Playlist: Focusing channel $resolvedIndex")
                focusCoordinator.focusPlaylist(resolvedIndex, false)
        }
        }

        if (uiState.showPlaylist) {
            PlaylistPanel(
                channels = uiState.filteredChannels,
                playlistTitleResId = uiState.playlistTitleResId,
                currentChannelIndex = uiState.currentChannelIndex,
                initialScrollIndex = uiState.lastPlaylistScrollIndex,
                epgOpenIndex = if (uiState.showEpgPanel) {
                    // Find the index of the channel whose EPG is open
                    uiState.filteredChannels.indexOfFirst { it.tvgId == uiState.epgChannelTvgId }
                } else {
                    -1
                },
                currentProgramsMap = uiState.currentProgramsMap,
                onChannelClick = actions.onPlayChannel,
                onFavoriteClick = actions.onToggleFavorite,
                onShowPrograms = actions.onShowEpgForChannel,
                onClose = actions.onClosePlaylist,
                onUpdateScrollIndex = actions.onUpdatePlaylistScrollIndex,
                onProvideFocusController = { controller -> focusCoordinator.registerPlaylistController(controller) },
                onProvideFocusRequester = { requester -> focusCoordinator.registerPlaylistRequester(requester) },
                onChannelFocused = { index ->
                    if (index >= 0) {
                        lastFocusedPlaylistIndex = index
                    }
                },
                onRequestEpgFocus = { requestEpgFocus(null) },
                modifier = Modifier.align(Alignment.CenterStart)
            )
        } else {
            focusCoordinator.clearPlaylist()
        }

        // EPG Panel
        val epgChannel = uiState.epgChannel
        if (uiState.showEpgPanel && uiState.epgPrograms.isNotEmpty()) {
            EpgPanel(
                programs = uiState.epgPrograms,
                channel = epgChannel,
                onProgramClick = actions.onShowProgramDetails,
                onPlayArchive = actions.onPlayArchiveProgram,
                isArchivePlayback = uiState.isArchivePlayback,
                isPlaylistOpen = uiState.showPlaylist,
                onLoadMorePast = actions.onLoadMoreEpgPast,
                onLoadMoreFuture = actions.onLoadMoreEpgFuture,
                onClose = actions.onCloseEpgPanel,
                onNavigateLeftToChannels = {
                    focusPlaylistFromEpg()
                },
                onOpenPlaylist = {
                    if (!uiState.showPlaylist) {
                        actions.onTogglePlaylist()
                    }
                },
                focusRequestToken = epgFocusRequestToken,
                focusRequestTargetIndex = epgFocusRequestTargetIndex,
                onFocusRequestHandled = { epgFocusRequestTargetIndex = null },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        // Program Details Panel
        uiState.selectedProgramDetails?.let { program ->
            ProgramDetailsPanel(
                program = program,
                onClose = {
                    actions.onCloseProgramDetails()
                    if (uiState.showEpgPanel) {
                        val programIndex = uiState.epgPrograms.indexOf(program)
                        val targetIndex = programIndex.takeIf { it >= 0 }
                        suppressFallbackEpgFocus = targetIndex != null
                        requestEpgFocus(targetIndex)
                    }
                },
                modifier = Modifier.align(Alignment.Center)
            )
        }
        LaunchedEffect(uiState.selectedProgramDetails) {
            if (uiState.selectedProgramDetails != null) {
                suppressFallbackEpgFocus = false
            }
        }
        LaunchedEffect(
            uiState.selectedProgramDetails,
            uiState.showEpgPanel,
            epgFocusRequestTargetIndex,
            suppressFallbackEpgFocus
        ) {
            if (uiState.selectedProgramDetails == null &&
                uiState.showEpgPanel &&
                epgFocusRequestTargetIndex == null &&
                !suppressFallbackEpgFocus
            ) {
                requestEpgFocus(null)
            }
        }

        // Debug Log Panel
        if (uiState.showDebugLog && uiState.debugMessages.isNotEmpty()) {
            DebugLogPanel(
                messages = uiState.debugMessages.takeLast(100).map { it.message },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )
        }

        uiState.archivePrompt?.let { prompt ->
            ArchivePromptDialog(
                prompt = prompt,
                onContinue = actions.onArchivePromptContinue,
                onBackToLive = actions.onArchivePromptBackToLive
            )
        }
    }
}

@UnstableApi
@Composable
private fun ReturnToLiveButton(
    onClick: () -> Unit,
    buttonHeight: Dp = 48.dp
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.ruTvColors.gold,
            contentColor = MaterialTheme.ruTvColors.darkBackground
        ),
        modifier = Modifier.height(buttonHeight)
    ) {
        Text(text = stringResource(R.string.player_return_to_live))
    }
}

@UnstableApi
@Composable
private fun ProgramInfoButton(
    program: EpgProgram,
    buttonHeight: Dp,
    onShowProgramInfo: (EpgProgram) -> Unit,
    containerColor: Color = MaterialTheme.ruTvColors.darkBackground,
    iconSizeMultiplier: Float = 0.75f
) {
    Box(
        modifier = Modifier.size(buttonHeight),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = { onShowProgramInfo(program) },
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.ruTvColors.gold,
                containerColor = containerColor
            ),
            modifier = Modifier.size(buttonHeight)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(R.string.player_program_info),
                modifier = Modifier.size(buttonHeight * iconSizeMultiplier)
            )
        }
    }
}

@UnstableApi
@Composable
private fun ChannelInfoOverlay(
    channelNumber: Int,
    channel: Channel,
    currentProgram: EpgProgram?,
    isArchivePlayback: Boolean,
    isTimeshiftPlayback: Boolean,
    archiveProgram: EpgProgram?,
    onReturnToLive: () -> Unit,
    onShowProgramInfo: (EpgProgram) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.8f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.channel_info_format, channelNumber, channel.title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.ruTvColors.textPrimary,
                textAlign = TextAlign.Center
            )
            if (isArchivePlayback) {
                archiveProgram?.let { program ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.player_archive_label, program.title.truncateForOverlay()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.ruTvColors.gold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val standardButtonHeight = 48.dp
                        ReturnToLiveButton(
                            onClick = onReturnToLive,
                            buttonHeight = standardButtonHeight
                        )
                        ProgramInfoButton(
                            program = program,
                            buttonHeight = standardButtonHeight,
                            onShowProgramInfo = onShowProgramInfo
                        )
                    }
                }
            } else {
                currentProgram?.let { program ->
                    Spacer(modifier = Modifier.height(4.dp))
                    if (isTimeshiftPlayback) {
                        Text(
                            text = program.title.truncateForOverlay(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.ruTvColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    } else {
                        Row(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = program.title.truncateForOverlay(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.ruTvColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                            val standardButtonHeight = 48.dp
                            ProgramInfoButton(
                                program = program,
                                buttonHeight = standardButtonHeight,
                                onShowProgramInfo = onShowProgramInfo,
                                containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.0f)
                            )
                        }
                    }
                }
                if (isTimeshiftPlayback) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val standardButtonHeight = 48.dp
                        ReturnToLiveButton(
                            onClick = onReturnToLive,
                            buttonHeight = standardButtonHeight
                        )
                        currentProgram?.let { program ->
                            ProgramInfoButton(
                                program = program,
                                buttonHeight = standardButtonHeight,
                                onShowProgramInfo = onShowProgramInfo
                            )
                        }
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun PlaylistPanel(
    channels: List<Channel>,
    playlistTitleResId: Int,
    currentChannelIndex: Int,
    initialScrollIndex: Int,
    epgOpenIndex: Int,
    currentProgramsMap: Map<String, EpgProgram?>,
    onChannelClick: (Int) -> Unit,
    onFavoriteClick: (String) -> Unit,
    onShowPrograms: (String) -> Unit,
    onClose: () -> Unit,
    onUpdateScrollIndex: (Int) -> Unit,
    onProvideFocusController: (((Int, Boolean) -> Boolean)?) -> Unit = {},
    onProvideFocusRequester: ((FocusRequester?) -> Unit)? = null,
    onChannelFocused: ((Int) -> Unit)? = null,
    onRequestEpgFocus: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val resolvedInitialIndex = when {
        channels.isEmpty() -> -1
        currentChannelIndex in channels.indices -> currentChannelIndex
        initialScrollIndex in channels.indices -> initialScrollIndex
        else -> 0
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = max(resolvedInitialIndex, 0),
        initialFirstVisibleItemScrollOffset = 0
    )
    val coroutineScope = rememberCoroutineScope()
    var playlistHasFocus by remember { mutableStateOf(false) }
    var pendingInitialCenterIndex by remember(channels, currentChannelIndex) {
        mutableStateOf(resolvedInitialIndex.takeIf { it in channels.indices })
    }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    val isRemoteMode = DeviceHelper.isRemoteInputActive()
    LaunchedEffect(showSearchDialog) {
        if (showSearchDialog) {
            playlistHasFocus = false
        }
    }

    var focusedChannelIndex by remember {
        mutableIntStateOf(
            resolvedInitialIndex.takeIf { it >= 0 } ?: -1
        )
    }
    LaunchedEffect(showSearchDialog) {
        if (showSearchDialog) {
            playlistHasFocus = false
        }
    }

    // Focus requesters for header buttons
    val closeButtonFocus = remember { FocusRequester() }

    // Track which channel opened EPG for focus restoration
    var channelThatOpenedEpg by remember { mutableStateOf<Int?>(null) }
    var pendingScrollJob by remember { mutableStateOf<Job?>(null) }


    val focusChannel: (Int, Boolean) -> Boolean = { targetIndex, play ->
        if (targetIndex !in channels.indices) {
            false
        } else {
            focusedChannelIndex = targetIndex
            onChannelFocused?.invoke(targetIndex)
            playlistHasFocus = true
            val shouldScroll = !listState.isItemFullyVisible(targetIndex)
            if (shouldScroll) {
                val scrollOffset = when {
                    targetIndex <= 0 -> 0
                    targetIndex >= channels.lastIndex -> 0
                    else -> -160
                }
                pendingScrollJob?.cancel()
                pendingScrollJob = coroutineScope.launch {
                    listState.animateScrollToItem(targetIndex, scrollOffset = scrollOffset)
                }.apply {
                    invokeOnCompletion { pendingScrollJob = null }
                }
            }
            if (play) {
                onChannelClick(targetIndex)
            }
            true
        }
    }
    // Focus requester for playlist LazyColumn (shared for EPG/playlist focus transfer)
    val lazyColumnFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        onProvideFocusController(focusChannel)
        onProvideFocusRequester?.invoke(lazyColumnFocusRequester)
    }

    val latestFocusedIndex by rememberUpdatedState(focusedChannelIndex)
    val latestChannels by rememberUpdatedState(channels)
    val latestCurrentChannelIndex by rememberUpdatedState(currentChannelIndex)
    DisposableEffect(Unit) {
        onDispose {
            onProvideFocusController(null)
            onProvideFocusRequester?.invoke(null)
            val finalIndex = when {
                latestChannels.isEmpty() -> -1
                latestFocusedIndex in latestChannels.indices -> latestFocusedIndex
                latestCurrentChannelIndex in latestChannels.indices -> latestCurrentChannelIndex
                initialScrollIndex in latestChannels.indices -> initialScrollIndex
                else -> 0
            }
            if (finalIndex >= 0) {
                onUpdateScrollIndex(finalIndex)
            }
        }
    }

    LaunchedEffect(channels.size) {
        if (channels.isEmpty()) {
            focusedChannelIndex = -1
            onChannelFocused?.invoke(-1)
        } else if (focusedChannelIndex !in channels.indices) {
            val fallbackIndex = when {
                currentChannelIndex in channels.indices -> currentChannelIndex
                initialScrollIndex in channels.indices -> initialScrollIndex
                else -> 0
            }
            focusedChannelIndex = fallbackIndex
            onChannelFocused?.invoke(focusedChannelIndex)
        }
    }

    LaunchedEffect(pendingInitialCenterIndex) {
        val targetIndex = pendingInitialCenterIndex ?: return@LaunchedEffect
        if (channels.isEmpty()) {
            pendingInitialCenterIndex = null
            return@LaunchedEffect
        }
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }
            .filter { it }
            .first()
        listState.centerOn(targetIndex)
        focusedChannelIndex = targetIndex
        onChannelFocused?.invoke(targetIndex)
        onUpdateScrollIndex(targetIndex)
        playlistHasFocus = true
        pendingInitialCenterIndex = null
    }

    LaunchedEffect(Unit) {
        if (focusedChannelIndex >= 0) {
            onChannelFocused?.invoke(focusedChannelIndex)
        }
    }

    // Restore focus to channel list when EPG closes
    LaunchedEffect(epgOpenIndex, isRemoteMode) {
        if (epgOpenIndex >= 0 && epgOpenIndex < channels.size) {
            channelThatOpenedEpg = epgOpenIndex
        } else if (epgOpenIndex < 0 && channelThatOpenedEpg != null) {
            // EPG closed, restore focus to the channel that opened it
            val channelIndex = channelThatOpenedEpg!!
            if (channelIndex >= 0 && channelIndex < channels.size && isRemoteMode) {
                // Small delay to ensure EPG panel is removed first
                delay(50)
                focusChannel(channelIndex, false)
                lazyColumnFocusRequester.requestFocus()
                playlistHasFocus = true
            }
            channelThatOpenedEpg = null
        }
    }

    Card(
        modifier = modifier
            .fillMaxHeight()
            .width(LayoutConstants.PlaylistPanelWidth)
            .padding(LayoutConstants.DefaultPadding),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f)
        ),
        border = BorderStroke(2.dp, MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LayoutConstants.ToolbarHeight)
                    .padding(horizontal = LayoutConstants.HeaderHorizontalPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(playlistTitleResId),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.ruTvColors.gold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(
                        onClick = { showSearchDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.cd_search_channel),
                            tint = MaterialTheme.ruTvColors.gold
                        )
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .focusable(enabled = isRemoteMode)
                        .focusRequester(closeButtonFocus)
                        .then(focusIndicatorModifier(isFocused = false))
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter -> {
                                        onClose()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close_playlist),
                        tint = MaterialTheme.ruTvColors.textPrimary
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.ruTvColors.textDisabled)

            // Channel List with scrollbar
            Box(modifier = Modifier.fillMaxSize()) {
                val isEpgPanelVisible = epgOpenIndex >= 0

                // Request focus on LazyColumn when it first appears
                LaunchedEffect(Unit) {
                    delay(150) // Wait for composition
                    lazyColumnFocusRequester.requestFocus()
                    playlistHasFocus = true
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(lazyColumnFocusRequester)
                        .focusable()
                        .onFocusChanged { playlistHasFocus = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (!isRemoteMode) {
                                return@onPreviewKeyEvent false
                            }
                            val handlesKey = when (event.key) {
                                Key.DirectionUp,
                                Key.DirectionDown,
                                Key.DirectionLeft,
                                Key.DirectionRight,
                                Key.DirectionCenter,
                                Key.Enter -> true
                                else -> false
                            }
                            if (!handlesKey) {
                                return@onPreviewKeyEvent false
                            }
                            if (event.type != KeyEventType.KeyDown) {
                                return@onPreviewKeyEvent true
                            }

                            when (event.key) {
                                Key.DirectionUp -> {
                                    if (focusedChannelIndex > 0) {
                                        focusChannel(focusedChannelIndex - 1, false)
                                    }
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (focusedChannelIndex < channels.lastIndex) {
                                        focusChannel(focusedChannelIndex + 1, false)
                                    }
                                    true
                                }
                                Key.DirectionCenter, Key.Enter -> {
                                    focusChannel(focusedChannelIndex, true)
                                    true
                                }
                                Key.DirectionRight -> {
                                    val channel = channels.getOrNull(focusedChannelIndex)
                                    if (channel?.hasEpg == true) {
                                        playlistHasFocus = false
                                        onShowPrograms(channel.tvgId)
                                        onRequestEpgFocus?.invoke()
                                        true
                                    } else {
                                        false
                                    }
                                }
                                Key.DirectionLeft -> {
                                    showSearchDialog = true
                                    true
                                }
                                else -> false
                            }
                        },
                    contentPadding = PaddingValues(start = 0.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)
                ) {
                    itemsIndexed(
                        items = channels,
                        key = { _, channel -> channel.url },
                        contentType = { _, channel ->
                            when {
                                channel.isFavorite -> "channel_favorite"
                                channel.hasEpg -> "channel_with_epg"
                                else -> "channel_basic"
                            }
                        }
                    ) { index, channel ->
                        val programInfo = remember(channel.tvgId, currentProgramsMap[channel.tvgId]) {
                            currentProgramsMap[channel.tvgId]
                        }
                        ChannelListItem(
                            channel = channel,
                            channelNumber = index + 1,
                            isPlaying = index == currentChannelIndex,
                            isEpgOpen = index == epgOpenIndex,
                            isEpgPanelVisible = isEpgPanelVisible,
                            currentProgram = programInfo,
                            isItemFocused = playlistHasFocus && index == focusedChannelIndex,
                            onChannelClick = { focusChannel(index, true) },
                            onFavoriteClick = { onFavoriteClick(channel.url) },
                            onShowPrograms = { onShowPrograms(channel.tvgId) },
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
                        )
                    }
                }

                // Scroll indicator
                val showScrollbar by remember {
                    derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
                }

                if (showScrollbar) {
                    val scrollProgress by remember { derivedStateOf { calculateScrollProgress(listState) } }
                    val thumbFraction = 0.18f
                    val trackFraction = 1f - thumbFraction
                    val topWeight = (scrollProgress * trackFraction).coerceIn(0f, trackFraction)
                    val bottomWeight = (trackFraction - topWeight).coerceAtLeast(0f)

                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(6.dp)
                            .padding(vertical = 8.dp)
                            .background(MaterialTheme.ruTvColors.textDisabled.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 1.dp, vertical = 4.dp)
                    ) {
                        if (topWeight > 0f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(topWeight)
                                    .fillMaxWidth()
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(thumbFraction)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.ruTvColors.gold)
                        )
                        if (bottomWeight > 0f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(bottomWeight)
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Search Dialog
            if (showSearchDialog) {
                val okButtonFocusRequester = remember { FocusRequester() }
                val searchFieldFocusRequester = remember { FocusRequester() }
                var pendingOkFocus by remember { mutableStateOf(false) }
                LaunchedEffect(showSearchDialog) {
                    if (showSearchDialog) {
                        pendingOkFocus = false
                        delay(50)
                        searchFieldFocusRequester.requestFocus()
                    }
                }
                LaunchedEffect(pendingOkFocus) {
                    if (pendingOkFocus && showSearchDialog) {
                        delay(10)
                        okButtonFocusRequester.requestFocus()
                        pendingOkFocus = false
                    }
                }

                RemoteDialog(
                    onDismissRequest = {
                        showSearchDialog = false
                        searchText = ""
                    },
                    containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
                    title = {
                        Text(
                            text = stringResource(R.string.dialog_title_search_channel),
                            color = MaterialTheme.ruTvColors.gold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    text = {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            label = { Text(stringResource(R.string.hint_search_channel)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFieldFocusRequester)
                                .onFocusChanged {
                                    if (showSearchDialog && !it.isFocused) {
                                        pendingOkFocus = true
                                    }
                                },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.ruTvColors.gold,
                                unfocusedBorderColor = MaterialTheme.ruTvColors.textDisabled,
                                focusedTextColor = MaterialTheme.ruTvColors.textPrimary,
                                unfocusedTextColor = MaterialTheme.ruTvColors.textPrimary,
                                focusedLabelColor = MaterialTheme.ruTvColors.gold,
                                unfocusedLabelColor = MaterialTheme.ruTvColors.textSecondary
                            )
                        )
                    },
                    confirmButton = {
                        TextButton(
                            modifier = Modifier.focusRequester(okButtonFocusRequester),
                            onClick = {
                                if (searchText.isNotBlank()) {
                                    val searchLower = searchText.lowercase()
                                    val matchingIndex = channels.indexOfFirst { channel ->
                                        channel.title.lowercase().contains(searchLower)
                                    }
                                    if (matchingIndex >= 0) {
                                        pendingInitialCenterIndex = matchingIndex
                                        focusChannel(matchingIndex, false)
                                    }
                                    showSearchDialog = false
                                    searchText = ""
                                }
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.button_ok),
                                color = MaterialTheme.ruTvColors.gold
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showSearchDialog = false
                            searchText = ""
                        }) {
                            Text(
                                text = stringResource(R.string.button_cancel),
                                color = MaterialTheme.ruTvColors.textPrimary
                            )
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.border(
                        2.dp,
                        MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f),
                        RoundedCornerShape(16.dp)
                    )
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun EpgPanel(
    programs: List<EpgProgram>,
    channel: Channel?,
    onProgramClick: (EpgProgram) -> Unit,
    onPlayArchive: (EpgProgram) -> Unit,
    isArchivePlayback: Boolean,
    isPlaylistOpen: Boolean,
    onLoadMorePast: () -> Unit,
    onLoadMoreFuture: () -> Unit,
    onClose: () -> Unit,
    onNavigateLeftToChannels: (() -> Unit)? = null,
    onOpenPlaylist: (() -> Unit)? = null,
    focusRequestToken: Int = 0,
    focusRequestTargetIndex: Int? = null,
    onFocusRequestHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val currentTime = System.currentTimeMillis()
    val isRemoteMode = DeviceHelper.isRemoteInputActive()
    var epgListHasFocus by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { epgListHasFocus = false }
    }

    // Find current program index in original list
    val currentProgramIndex = programs.indexOfFirst { program ->
        val start = program.startTimeMillis
        val end = program.stopTimeMillis
        start > 0L && end > 0L && currentTime in start..end
    }

    // Build items list with date delimiters to calculate correct scroll position
    val (items, programItemIndices) = remember(programs) {
        val itemsList = mutableListOf<EpgUiItem>()
        val indexMap = MutableList(programs.size) { -1 }
        var lastDate = ""
        programs.forEachIndexed { index, program ->
            val programDate = TimeFormatter.formatEpgDate(Date(program.startTimeMillis))
            if (programDate != lastDate) {
                val absoluteIndex = itemsList.size
                itemsList.add(EpgUiItem(absoluteIndex, "date_$programDate", programDate))
                lastDate = programDate
            }
            val baseKey = when {
                program.id.isNotBlank() -> program.id
                else -> "${program.startTimeMillis}_${program.stopTimeMillis}_${program.title}_$index"
            }
            val absoluteIndex = itemsList.size
            itemsList.add(EpgUiItem(absoluteIndex, "program_$baseKey", program))
            indexMap[index] = absoluteIndex
        }
        itemsList to indexMap
    }

    val resolvedInitialProgramIndex = when {
        programs.isEmpty() -> -1
        currentProgramIndex in programs.indices -> currentProgramIndex
        else -> 0
    }
    val resolvedInitialItemIndex = programItemIndices
        .getOrNull(resolvedInitialProgramIndex)
        ?.coerceAtLeast(0)
    val listState = remember(channel?.tvgId) {
        LazyListState(
            max(resolvedInitialItemIndex ?: 0, 0),
            0
        )
    }

    var focusedProgramIndex by remember(channel?.tvgId) {
        mutableIntStateOf(resolvedInitialProgramIndex.coerceAtLeast(0))
    }
    var pendingProgramCenterIndex by remember(channel?.tvgId) {
        mutableStateOf(resolvedInitialItemIndex)
    }

    LaunchedEffect(channel?.tvgId) {
        pendingProgramCenterIndex = resolvedInitialItemIndex
    }

    LaunchedEffect(focusRequestToken) {
        if (focusRequestToken == 0) {
            return@LaunchedEffect
        }
        val requestTarget = focusRequestTargetIndex
        val targetProgramIndex = when {
            requestTarget != null && requestTarget in programs.indices ->
                requestTarget
            focusedProgramIndex in programs.indices -> focusedProgramIndex
            resolvedInitialProgramIndex in programs.indices -> resolvedInitialProgramIndex
            else -> null
        }
        val targetItemIndex = targetProgramIndex
            ?.let { programItemIndices.getOrNull(it) }
            ?: resolvedInitialItemIndex
        if (targetItemIndex == null) {
            onFocusRequestHandled()
        }
        pendingProgramCenterIndex = targetItemIndex
    }

    LaunchedEffect(programs.size, channel?.tvgId, currentProgramIndex) {
        if (programs.isEmpty()) {
            focusedProgramIndex = -1
        } else if (focusedProgramIndex !in programs.indices) {
            val fallback = when {
                currentProgramIndex in programs.indices -> currentProgramIndex
                else -> 0
            }
            focusedProgramIndex = fallback
        }
    }

    LaunchedEffect(pendingProgramCenterIndex) {
        val targetItemIndex = pendingProgramCenterIndex ?: return@LaunchedEffect
        if (programs.isEmpty()) {
            pendingProgramCenterIndex = null
            onFocusRequestHandled()
            return@LaunchedEffect
        }
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }
            .filter { it }
            .first()
        listState.centerOn(targetItemIndex)
        val programIndex = programItemIndices.indexOf(targetItemIndex).takeIf { it >= 0 }
        programIndex?.let { focusedProgramIndex = it }
        epgListHasFocus = true
        pendingProgramCenterIndex = null
        onFocusRequestHandled()
    }

    fun focusProgram(targetIndex: Int): Boolean {
        if (targetIndex !in programs.indices) {
            return false
        }
        val itemIndex = programItemIndices.getOrNull(targetIndex)
        if (itemIndex == null) {
            return false
        }
        focusedProgramIndex = targetIndex
        val shouldScroll = listState.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }
        coroutineScope.launch {
            if (shouldScroll) {
                listState.animateScrollToItem(itemIndex, scrollOffset = -200)
            }
        }
        return true
    }

    // Lazy paging triggers near list edges
    var edgeRequestedPast by remember { mutableStateOf(false) }
    var edgeRequestedFuture by remember { mutableStateOf(false) }
    LaunchedEffect(programs.size) {
        // Reset edge request guards when list size changes
        edgeRequestedPast = false
        edgeRequestedFuture = false
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.totalItemsCount }
            .collect { (firstIndex, total) ->
                if (total <= 0) return@collect
                if (firstIndex <= 2 && !edgeRequestedPast) {
                    edgeRequestedPast = true
                    onLoadMorePast()
                }
                val lastVisible = listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size
                if (lastVisible >= total - 3 && !edgeRequestedFuture) {
                    edgeRequestedFuture = true
                    onLoadMoreFuture()
                }
            }
    }

    Card(
        modifier = modifier
            .fillMaxHeight()
            .width(LayoutConstants.EpgPanelWidth)
            .padding(LayoutConstants.DefaultPadding),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f)
        ),
        border = BorderStroke(2.dp, MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LayoutConstants.ToolbarHeight)
                    .padding(horizontal = LayoutConstants.HeaderHorizontalPadding),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val titleText = if (channel != null) {
                    "${stringResource(R.string.epg_panel_title)} • ${channel.title}"
                } else {
                    stringResource(R.string.epg_panel_title)
                }
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.ruTvColors.gold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(color = MaterialTheme.ruTvColors.textDisabled)

            // Programs List with scrollbar
            Box(modifier = Modifier.fillMaxSize()) {
                // Focus requester for LazyColumn
                val lazyColumnFocusRequester = remember { FocusRequester() }
                val pagingItems = remember(items) {
                    Pager(
                        PagingConfig(
                            pageSize = 40,
                            initialLoadSize = 40,
                            enablePlaceholders = false
                        )
                    ) {
                        StaticPagingSource(items)
                    }
                }.flow.collectAsLazyPagingItems()

                // Request focus on LazyColumn when signaled
                LaunchedEffect(focusRequestToken, isRemoteMode) {
                    if (!isRemoteMode) return@LaunchedEffect
                    delay(50)
                    lazyColumnFocusRequester.requestFocus()
                    epgListHasFocus = true
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(lazyColumnFocusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (!isRemoteMode) {
                                return@onPreviewKeyEvent false
                            }
                            val handlesKey = when (event.key) {
                                Key.DirectionUp,
                                Key.DirectionDown,
                                Key.DirectionLeft,
                                Key.DirectionRight,
                                Key.DirectionCenter,
                                Key.Enter -> true
                                else -> false
                            }
                            if (!handlesKey) {
                                return@onPreviewKeyEvent false
                            }
                            if (event.type != KeyEventType.KeyDown) {
                                return@onPreviewKeyEvent true
                            }

                            when (event.key) {
                                Key.DirectionUp -> {
                                    if (focusedProgramIndex > 0) {
                                        focusProgram(focusedProgramIndex - 1)
                                    }
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (focusedProgramIndex < programs.lastIndex) {
                                        focusProgram(focusedProgramIndex + 1)
                                    }
                                    true
                                }
                                Key.DirectionCenter, Key.Enter -> {
                                    val program = programs.getOrNull(focusedProgramIndex)
                                    if (program != null) {
                                        val isPast = program.stopTimeMillis > 0 && program.stopTimeMillis <= currentTime
                                        val catchupWindowMillis = channel
                                            ?.takeIf { it.supportsCatchup() }
                                            ?.let { java.util.concurrent.TimeUnit.DAYS.toMillis(it.catchupDays.toLong()) }
                                        val isArchiveCandidate = catchupWindowMillis != null &&
                                            isPast &&
                                            program.startTimeMillis > 0 &&
                                            currentTime - program.startTimeMillis <= catchupWindowMillis
                                        val canPlayArchive = isArchiveCandidate && !isArchivePlayback

                                        if (canPlayArchive) {
                                            onPlayArchive(program)
                                        }
                                    }
                                    true
                                }
                                Key.DirectionLeft -> {
                                    if (isPlaylistOpen) {
                                        onNavigateLeftToChannels?.invoke()
                                    } else {
                                        onOpenPlaylist?.invoke()
                                    }
                                    epgListHasFocus = false
                                    onClose()
                                    true
                                }
                                Key.DirectionRight -> {
                                    val program = programs.getOrNull(focusedProgramIndex)
                                    if (program != null) {
                                        onProgramClick(program)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            }
                        },
                    contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 20.dp, bottom = 4.dp) // Extra padding for 4dp focus border
                ) {
                    items(
                        count = pagingItems.itemCount,
                        key = { index -> pagingItems.peek(index)?.key ?: "epg_placeholder_$index" }
                    ) { index ->
                        val entry = pagingItems[index] ?: return@items
                        when (val data = entry.payload) {
                            is String -> {
                                EpgDateDelimiter(date = data)
                            }
                            is EpgProgram -> {
                                val programIndex = programs.indexOf(data)
                                if (programIndex < 0) return@items
                                val isPast = data.stopTimeMillis > 0 && data.stopTimeMillis <= currentTime
                                val catchupWindowMillis = channel
                                    ?.takeIf { it.supportsCatchup() }
                                    ?.let { java.util.concurrent.TimeUnit.DAYS.toMillis(it.catchupDays.toLong()) }
                                val isArchiveCandidate = catchupWindowMillis != null &&
                                    isPast &&
                                    data.startTimeMillis > 0 &&
                                    currentTime - data.startTimeMillis <= catchupWindowMillis
                                val canPlayArchive = isArchiveCandidate && !isArchivePlayback

                                EpgProgramItem(
                                    program = data,
                                    isCurrent = programIndex == currentProgramIndex,
                                    isPast = isPast,
                                    showArchiveIndicator = isArchiveCandidate,
                                    isItemFocused = epgListHasFocus && programIndex == focusedProgramIndex,
                                    onClick = { onProgramClick(data) },
                                    onPlayArchive = if (canPlayArchive) { { onPlayArchive(data) } } else null
                                )
                            }
                        }
                    }
                }

                // Scroll indicator
                val showScrollbar = remember {
                    derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
                }

                if (showScrollbar.value) {
                    val scrollProgress = remember { derivedStateOf { calculateScrollProgress(listState) } }
                    val thumbFraction = 0.18f
                    val trackFraction = 1f - thumbFraction
                    val topWeight = (scrollProgress.value * trackFraction).coerceIn(0f, trackFraction)
                    val bottomWeight = (trackFraction - topWeight).coerceAtLeast(0f)

                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(6.dp)
                            .padding(vertical = 8.dp)
                            .background(MaterialTheme.ruTvColors.textDisabled.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 1.dp, vertical = 4.dp)
                    ) {
                        if (topWeight > 0f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(topWeight)
                                    .fillMaxWidth()
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(thumbFraction)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.ruTvColors.gold)
                        )
                        if (bottomWeight > 0f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(bottomWeight)
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramDetailsPanel(
    program: EpgProgram,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRemoteMode = DeviceHelper.isRemoteInputActive()
    val closeButtonFocus = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    var closeButtonFocused by remember { mutableStateOf(false) }

    // Request focus on close button when panel opens in remote mode
    LaunchedEffect(isRemoteMode) {
        if (isRemoteMode) {
            closeButtonFocus.requestFocus()
        }
    }

    val startTimeFormatted = program.startTimeMillis.takeIf { it > 0L }?.let {
        TimeFormatter.formatProgramDateTime(Date(it))
    } ?: stringResource(R.string.time_placeholder)
    val density = LocalDensity.current
    val scrollStepPx = remember(density) { with(density) { 200.dp.toPx() } }

    Card(
        modifier = modifier
            .fillMaxHeight(LayoutConstants.ProgramDetailsPanelMaxHeight)
            .width(LayoutConstants.ProgramDetailsPanelWidth)
            .padding(LayoutConstants.DefaultPadding),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f)
        ),
        border = BorderStroke(2.dp, MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LayoutConstants.ToolbarHeight)
                    .padding(horizontal = LayoutConstants.HeaderHorizontalPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.program_details_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.ruTvColors.gold
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .focusable()
                        .focusRequester(closeButtonFocus)
                        .onFocusChanged { closeButtonFocused = it.isFocused }
                        .then(focusIndicatorModifier(isFocused = closeButtonFocused))
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter, Key.Back -> {
                                        onClose()
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        contentFocusRequester.requestFocus()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close_playlist),
                        tint = MaterialTheme.ruTvColors.textPrimary
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.ruTvColors.textDisabled)

            // Scrollable content with scrollbar
            Box(modifier = Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(contentFocusRequester)
                        .focusable()
                        .onKeyEvent { event ->
                            val remoteActive = DeviceHelper.isRemoteInputActive()
                            if (!remoteActive || event.type != KeyEventType.KeyDown) {
                                return@onKeyEvent false
                            }
                            val totalItems = listState.layoutInfo.totalItemsCount
                            if (totalItems <= 0) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    coroutineScope.launch {
                                        listState.scrollByIfPossible(scrollStepPx)
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    val atTop = listState.firstVisibleItemIndex == 0 &&
                                        listState.firstVisibleItemScrollOffset == 0
                                    if (atTop) {
                                        closeButtonFocus.requestFocus()
                                    } else {
                                        coroutineScope.launch {
                                            listState.scrollByIfPossible(-scrollStepPx)
                                        }
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 20.dp, bottom = 16.dp) // Add end padding for scrollbar
                ) {
                    item {
                        // Program Title
                        Text(
                            text = program.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.ruTvColors.gold
                        )
                    }

                    item {
                        // Start Time
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.ruTvColors.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = startTimeFormatted,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.ruTvColors.textSecondary
                            )
                        }
                    }

                    if (program.description.isNotEmpty()) {
                        item {
                            // Description
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.program_details_description),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.ruTvColors.textPrimary
                                )
                                Text(
                                    text = program.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.ruTvColors.textSecondary,
                                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                                    maxLines = Int.MAX_VALUE,
                                    overflow = TextOverflow.Visible,
                                    softWrap = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // Scroll indicator
                val showScrollbar = remember {
                    derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
                }

                if (showScrollbar.value) {
                    val scrollProgress = remember { derivedStateOf { calculateScrollProgress(listState) } }
                    val thumbFraction = 0.18f
                    val trackFraction = 1f - thumbFraction
                    val topWeight = (scrollProgress.value * trackFraction).coerceIn(0f, trackFraction)
                    val bottomWeight = (trackFraction - topWeight).coerceAtLeast(0f)

                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(6.dp)
                            .padding(vertical = 8.dp)
                            .background(MaterialTheme.ruTvColors.textDisabled.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 1.dp, vertical = 4.dp)
                    ) {
                        if (topWeight > 0f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(topWeight)
                                    .fillMaxWidth()
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(thumbFraction)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.ruTvColors.gold)
                        )
                        if (bottomWeight > 0f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(bottomWeight)
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun calculateScrollProgress(listState: LazyListState): Float {
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return 0f

    val averageItemSize = layoutInfo.visibleItemsInfo
        .takeIf { it.isNotEmpty() }
        ?.map { it.size }
        ?.average()
        ?.coerceAtLeast(1.0)
        ?: return 0f

    val viewportSize = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
    val totalContentHeight = (averageItemSize * totalItems).toInt()
    val maxScroll = (totalContentHeight - viewportSize).coerceAtLeast(1)
    val scrolled = (listState.firstVisibleItemIndex * averageItemSize + listState.firstVisibleItemScrollOffset).toInt()
    return (scrolled.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)
}

private data class EpgUiItem(
    val absoluteIndex: Int,
    val key: String,
    val payload: Any
)

private fun LazyListState.isItemFullyVisible(index: Int): Boolean {
    val layout = this.layoutInfo
    if (layout.visibleItemsInfo.isEmpty()) return false
    val viewportStart = layout.viewportStartOffset
    val viewportEnd = layout.viewportEndOffset
    val itemInfo = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    val itemStart = itemInfo.offset
    val itemEnd = itemStart + itemInfo.size
    return itemStart >= viewportStart && itemEnd <= viewportEnd
}

private suspend fun LazyListState.scrollByIfPossible(delta: Float): Boolean {
    if (delta == 0f) return false
    val layoutInfo = layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems <= 0) return false

    val direction = if (delta > 0) 1 else -1
    val targetIndex = (firstVisibleItemIndex + direction).coerceIn(0, totalItems - 1)

    if (targetIndex == firstVisibleItemIndex) {
        if (direction < 0 && firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) {
            return false
        }
    }

    animateScrollToItem(targetIndex)
    return true
}

private suspend fun LazyListState.centerOn(index: Int) {
    if (index < 0) return
    scrollToItem(index)
    val viewportSize = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
    val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val desiredOffset = (viewportSize / 2) - (targetInfo.size / 2)
    scrollToItem(index, -desiredOffset)
}

@Composable
private fun DebugLogPanel(
    messages: List<String>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .width(LayoutConstants.PlaylistPanelWidth)
            .height(200.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.9f)
        )
    ) {
        val listState = rememberLazyListState()

        // Auto-scroll to bottom
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.debug_log_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.ruTvColors.gold,
                modifier = Modifier.padding(LayoutConstants.SmallPadding)
            )
            HorizontalDivider(color = MaterialTheme.ruTvColors.textDisabled)

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                    .fillMaxSize()
                    .padding(LayoutConstants.SmallPadding)
            ) {
                items(messages.size) { index ->
                    Text(
                        text = messages[index],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ruTvColors.textSecondary
                    )
                }
            }
        }
    }
}

private const val MEDIA3_UI_PACKAGE = "androidx.media3.ui"
private const val MAX_PROGRAM_TITLE_CHARS = 48
private fun View.enableControl() {
    alpha = 1f
    isEnabled = true
}

private fun View.disableControl() {
    alpha = 0.4f
    isEnabled = false
}

@SuppressLint("DiscouragedApi")
private fun PlayerView.findControlView(name: String): View? {
    val candidateIds = buildList {
        resources.getIdentifier(name, "id", context.packageName)
            .takeIf { it != 0 }?.let(::add)
        resources.getIdentifier(name, "id", MEDIA3_UI_PACKAGE)
            .takeIf { it != 0 }?.let(::add)
        try {
            Media3UiR.id::class.java.getField(name).getInt(null)
        } catch (_: Exception) {
            null
        }?.let(::add)
    }
    candidateIds.forEach { id ->
        findViewById<View>(id)?.let { return it }
    }
    return null
}

private fun PlayerView.hideSettingsControls() {
    listOf(
        "exo_settings",
        "exo_settings_container",
        "exo_settings_button",
        "exo_settings_icon",
        "exo_overflow_show",
        "exo_overflow_hide"
    ).forEach { controlId ->
        findControlView(controlId)?.apply {
            visibility = View.GONE
            isEnabled = false
            setOnClickListener(null)
        }
    }
}

private fun String.truncateForOverlay(maxChars: Int = MAX_PROGRAM_TITLE_CHARS): String {
    if (length <= maxChars) return this
    if (maxChars <= 1) return "…"
    val trimmed = take(maxChars - 1).trimEnd()
    return if (trimmed.isEmpty()) "…" else "$trimmed…"
}


private data class ControlsSignature(
    val isArchivePlayback: Boolean,
    val programHash: Int,
    val navigateLeftHash: Int,
    val navigateRightHash: Int
)

private fun PlayerView.configurePlayerView(
    uiState: PlayerUiState,
    onControllerVisibilityChanged: (Boolean) -> Unit
) {
    useController = true
    controllerShowTimeoutMs = PlayerConstants.CONTROLLER_AUTO_HIDE_TIMEOUT_MS
    controllerHideOnTouch = true
    resizeMode = uiState.currentResizeMode
    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    setShowShuffleButton(false)
    setShowSubtitleButton(false)
    try {
        val method = PlayerView::class.java.getMethod(
            "setShowSettingsButton",
            Boolean::class.javaPrimitiveType
        )
        method.invoke(this, false)
    } catch (_: Exception) {
        // Method not available, ignore
    }
    setShowPreviousButton(true)
    setShowNextButton(true)
    setShowRewindButton(true)
    setShowFastForwardButton(true)
    hideSettingsControls()
    post { hideSettingsControls() }
    setControllerVisibilityListener(
        PlayerView.ControllerVisibilityListener { visibility ->
            onControllerVisibilityChanged(visibility == View.VISIBLE)
        }
    )
}

private fun PlayerView.bindControls(
    uiState: PlayerUiState,
    actions: PlayerUiActions,
    onNavigateLeftToFavorites: (() -> Unit)?,
    onNavigateRightToRotate: (() -> Unit)?
) {
    applyControlCustomizations(
        isArchivePlayback = uiState.isArchivePlayback,
        currentProgram = if (uiState.isArchivePlayback) uiState.archiveProgram else uiState.currentProgram,
        onRestartPlayback = actions.onRestartPlayback,
        onSeekBack = actions.onSeekBack,
        onSeekForward = actions.onSeekForward,
        onPausePlayback = actions.onPausePlayback,
        onResumePlayback = actions.onResumePlayback,
        onNavigateLeftToFavorites = onNavigateLeftToFavorites,
        onNavigateRightToRotate = onNavigateRightToRotate
    )
}

private fun PlayerView.applyControlCustomizations(
    isArchivePlayback: Boolean,
    currentProgram: EpgProgram?,
    onRestartPlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPausePlayback: () -> Unit,
    onResumePlayback: () -> Unit,
    onNavigateLeftToFavorites: (() -> Unit)? = null,
    onNavigateRightToRotate: (() -> Unit)? = null
) {
    setShowPreviousButton(true)
    setShowNextButton(true)
    setShowRewindButton(true)
    setShowFastForwardButton(true)

    findControlView("exo_prev")?.apply {
        visibility = View.VISIBLE
        enableControl()
        setOnClickListener { onRestartPlayback() }
        // LEFT from leftmost Exo control should move to Favorites button
        isFocusable = true
        isFocusableInTouchMode = false
        setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                hasFocus()) {
                DeviceHelper.updateLastInputMethod(event)
                onNavigateLeftToFavorites?.invoke()
                true
            } else {
                false
            }
        }
    }

    findControlView("exo_next")?.apply {
        visibility = View.VISIBLE
        disableControl()
        setOnClickListener(null)
        // Make focusable so OK can activate it (even though disabled, for navigation)
        isFocusable = true
        isFocusableInTouchMode = false
        setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
                hasFocus()
            ) {
                DeviceHelper.updateLastInputMethod(event)
                post { onNavigateRightToRotate?.invoke() }
                true
            } else {
                false
            }
        }
    }

    listOf("exo_rew", "exo_rew_with_amount").forEach { controlId ->
        findControlView(controlId)?.apply {
            visibility = View.VISIBLE
            enableControl()
            setOnClickListener { onSeekBack() }
            // Make focusable so OK can activate it
            isFocusable = true
            isFocusableInTouchMode = false
            setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                    hasFocus()
                ) {
                    DeviceHelper.updateLastInputMethod(event)
                    post { onNavigateLeftToFavorites?.invoke() }
                    true
                } else {
                    false
                }
            }
        }
    }

    listOf("exo_ffwd", "exo_ffwd_with_amount").forEach { controlId ->
        findControlView(controlId)?.apply {
            visibility = View.VISIBLE
            if (isArchivePlayback) {
                enableControl()
                setOnClickListener { onSeekForward() }
            } else {
                disableControl()
                setOnClickListener(null)
            }
            // RIGHT from rightmost Exo control should move to Rotate button
            isFocusable = true
            isFocusableInTouchMode = false
            setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // Check if this view has focus before navigating
                    if (hasFocus()) {
                        DeviceHelper.updateLastInputMethod(event)
                        post {
                            onNavigateRightToRotate?.invoke()
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        }
    }

    findControlView("exo_pause")?.apply {
        setOnClickListener { onPausePlayback() }
        // Make focusable so OK can activate it
        isFocusable = true
        isFocusableInTouchMode = false
    }
    findControlView("exo_play")?.apply {
        setOnClickListener { onResumePlayback() }
        // Make focusable so OK can activate it
        isFocusable = true
        isFocusableInTouchMode = false
    }
    findControlView("exo_play_pause")?.apply {
        setOnClickListener {
            val playerInstance = player
            if (playerInstance?.isPlaying == true) {
                onPausePlayback()
            } else {
                onResumePlayback()
            }
        }
        // Make focusable so OK can activate it
        isFocusable = true
        isFocusableInTouchMode = false
    }

    // Refactor progress bar: center it and position times on left/right sides
    val progressVerticalOffsetDp = 12f
    val horizontalMarginDp = 120f // Leave space for custom buttons on sides
    val horizontalMarginPx = (horizontalMarginDp * resources.displayMetrics.density).toInt()

    // Find the TimeBar/progress bar view
    val timeBar = findControlView("exo_timebar") ?: findControlView("exo_progress")

    // Center the progress bar by adjusting its layout margins (not translation)
    timeBar?.let { bar ->
        (bar.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.apply {
            // Set horizontal margins to center the bar and leave space for side buttons
            marginStart = horizontalMarginPx
            marginEnd = horizontalMarginPx
        }
        setVerticalOffsetDp(progressVerticalOffsetDp)
    }

    // Position time text views - they should already be in the layout on left/right
    // Just ensure they're vertically aligned with the progress bar
    val positionView = findControlView("exo_position")
    val durationView = findControlView("exo_duration")

    positionView?.let { view ->
        setVerticalOffsetDp(progressVerticalOffsetDp)
    }

    durationView?.let { view ->
        setVerticalOffsetDp(progressVerticalOffsetDp)
    }
}

/**
 * Setup navigation from ExoPlayer controls to custom control buttons
 * Store references to focus requesters for MainActivity to use
 */
private fun setupExoPlayerNavigationToCustomControls(
    playerView: PlayerView,
    leftFocusRequesters: List<FocusRequester>?,
    rightFocusRequesters: List<FocusRequester>?
) {
    // Store references in playerView tag for MainActivity to access
    // This is a bridge between View (ExoPlayer) and Compose (CustomControlButtons)
    playerView.tag = mapOf(
        "leftFocusRequesters" to leftFocusRequesters,
        "rightFocusRequesters" to rightFocusRequesters
    )
}

private fun PlayerView.focusOnControl(vararg controlNames: String) {
    showController()
    controlNames.asSequence()
        .mapNotNull { findControlView(it) }
        .firstOrNull { view ->
            view.visibility == View.VISIBLE && view.isFocusable && view.isShown
        }?.let { target ->
            target.requestFocus()
            (target.parent as? ViewGroup)?.requestChildFocus(target, target)
        }
}

private fun View.setVerticalOffsetDp(offsetDp: Float) {
    translationY = offsetDp * resources.displayMetrics.density
}
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import com.rutv.data.paging.StaticPagingSource
