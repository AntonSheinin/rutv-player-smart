package com.rutv.ui.mobile.screens

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import android.annotation.SuppressLint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlin.math.max
import kotlin.math.abs
import timber.log.Timber

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
    var lastControlsInteractionAt by remember { mutableStateOf(System.currentTimeMillis()) }
    val controllerVisibilityCallback by rememberUpdatedState<(Boolean) -> Unit> { visible ->
        showControls = visible
    }

    // Store focus requesters for custom controls (for ExoPlayer navigation)
    var leftColumnFocusRequesters by remember { mutableStateOf<List<FocusRequester>?>(null) }
    var rightColumnFocusRequesters by remember { mutableStateOf<List<FocusRequester>?>(null) }
    var playlistFocusReady by remember { mutableStateOf(false) }
    var lastFocusedPlaylistIndex by remember { mutableIntStateOf(uiState.currentChannelIndex.coerceAtLeast(0)) }
    var lastControlsSignature by remember { mutableStateOf<ControlsSignature?>(null) }
    var pendingCustomControlFocus by remember { mutableStateOf<CustomControlFocusTarget?>(null) }
    var epgFocusRequestToken by remember { mutableIntStateOf(0) }
    var epgFocusRequestTargetIndex by remember { mutableStateOf<Int?>(null) }
    var suppressFallbackEpgFocus by remember { mutableStateOf(false) }
    val requestEpgFocus: (Int?) -> Unit = { targetIndex ->
        epgFocusRequestTargetIndex = targetIndex
        epgFocusRequestToken++
    }
    val requestPlaylistFocusSafe: () -> Unit = {
        if (playlistFocusReady) {
            focusCoordinator.requestPlaylistFocus()
        } else {
            debugLogger("requestPlaylistFocus() deferred - requester missing")
        }
    }

    // Callbacks to move focus to custom controls (used by ExoPlayer controls)
    var navigateToFavoritesCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var navigateToRotateCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Toggle controls function - exposed to MainActivity for OK button
    val registerControlsInteraction: () -> Unit = {
        lastControlsInteractionAt = System.currentTimeMillis()
    }

    val toggleControls: () -> Unit = {
        val newValue = !showControls
        showControls = newValue
        if (newValue) registerControlsInteraction()
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
                currentPlayerView.focusOnControl(
                    "exo_prev",
                    "exo_rew",
                    "exo_rew_with_amount",
                    "exo_play_pause",
                    "exo_play",
                    "exo_pause"
                )
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

    LaunchedEffect(showControls, lastControlsInteractionAt, playerViewRef) {
        if (!showControls) return@LaunchedEffect
        val playerView = playerViewRef ?: return@LaunchedEffect
        val timeoutMs = 3000L
        val startAt = lastControlsInteractionAt
        delay(timeoutMs)
        if (showControls && startAt == lastControlsInteractionAt) {
            showControls = false
            playerView.hideController()
        }
    }

    LaunchedEffect(uiState.showEpgPanel, uiState.showPlaylist, playlistFocusReady) {
        if (uiState.showEpgPanel) {
            requestEpgFocus(null)
        } else if (uiState.showPlaylist && playlistFocusReady) {
            requestPlaylistFocusSafe()
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
            .onPreviewKeyEvent { event ->
                // Fullscreen playback DPAD handling
                DeviceHelper.updateLastInputMethod(event.nativeKeyEvent)
                val isRemote = DeviceHelper.isRemoteInputActive()
                if (!isRemote || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                if (showControls) {
                    registerControlsInteraction()
                }

                val panelsOpen = uiState.showPlaylist ||
                    uiState.showEpgPanel ||
                    uiState.selectedProgramDetails != null ||
                    uiState.archivePrompt != null

                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (!showControls && !panelsOpen) {
                            showControls = true
                            registerControlsInteraction()
                            playerViewRef?.post { playerViewRef?.showController() }
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionLeft -> {
                        if (!showControls && !panelsOpen) {
                            lastFocusedPlaylistIndex = uiState.currentChannelIndex.coerceAtLeast(0)
                            if (!uiState.showPlaylist) {
                                actions.onTogglePlaylist()
                            }
                            registerControlsInteraction()
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionRight -> {
                        if (!showControls && !panelsOpen) {
                            lastFocusedPlaylistIndex = uiState.currentChannelIndex.coerceAtLeast(0)
                            if (!uiState.showPlaylist) {
                                actions.onTogglePlaylist()
                            }
                            uiState.currentChannel?.tvgId?.let { actions.onShowEpgForChannel(it) }
                            registerControlsInteraction()
                            true
                        } else {
                            false
                        }
                    }
                    Key.Back -> {
                        when {
                            uiState.selectedProgramDetails != null -> {
                                actions.onCloseProgramDetails()
                                true
                            }
                            uiState.showEpgPanel -> {
                                actions.onCloseEpgPanel()
                                true
                            }
                            uiState.showPlaylist -> {
                                actions.onClosePlaylist()
                                true
                            }
                            showControls -> {
                                showControls = false
                                playerViewRef?.post { playerViewRef?.hideController() }
                                true
                            }
                            else -> false
                        }
                    }
                    else -> false
                }
            }
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
                            onNavigateRightToRotate = navigateToRotateCallback,
                            onControlsInteraction = { registerControlsInteraction() }
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
            val focusCustomControl: (CustomControlFocusTarget) -> Unit = { target ->
                val requester = when (target) {
                    CustomControlFocusTarget.Favorites -> leftColumnFocusRequesters?.getOrNull(1)
                    CustomControlFocusTarget.Rotate -> rightColumnFocusRequesters?.getOrNull(1)
                }
                if (requester != null) {
                    Timber.d("CustomControlFocus | focusing immediate: %s", target)
                    requester.requestFocus()
                    pendingCustomControlFocus = null
                } else {
                    Timber.d("CustomControlFocus | requesters missing, pending: %s", target)
                    pendingCustomControlFocus = target
                }
            }
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
                onNavigateRightFromFavorites = { registerControlsInteraction(); focusLeftmostExoControl() },
                onNavigateLeftFromRotate = { registerControlsInteraction(); focusRightmostExoControl() },
                onRegisterFocusRequesters = { left, right ->
                    leftColumnFocusRequesters = left
                    rightColumnFocusRequesters = right
                    // Update callbacks for ExoPlayer navigation
                    navigateToFavoritesCallback = {
                        registerControlsInteraction()
                        Timber.d("CustomControlFocus | navigateToFavoritesCallback triggered")
                        focusCustomControl(CustomControlFocusTarget.Favorites)
                    }
                    navigateToRotateCallback = {
                        registerControlsInteraction()
                        Timber.d("CustomControlFocus | navigateToRotateCallback triggered")
                        focusCustomControl(CustomControlFocusTarget.Rotate) // Rotate is index 1 in right column
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        LaunchedEffect(pendingCustomControlFocus, leftColumnFocusRequesters, rightColumnFocusRequesters) {
            pendingCustomControlFocus?.let { target ->
                val requester = when (target) {
                    CustomControlFocusTarget.Favorites -> leftColumnFocusRequesters?.getOrNull(1)
                    CustomControlFocusTarget.Rotate -> rightColumnFocusRequesters?.getOrNull(1)
                }
                if (requester != null) {
                    Timber.d("CustomControlFocus | pending resolved, focusing: %s", target)
                    requester.requestFocus()
                    pendingCustomControlFocus = null
                } else {
                    Timber.d("CustomControlFocus | still waiting for requesters for: %s", target)
                }
            }
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

        val allChannels = uiState.filteredChannels
        val displayedChannels = uiState.visibleChannels

        // Focus management for panel transitions
        val focusPlaylistFromEpg: () -> Unit = {
            debugLogger("EPG->Playlist: Transferring focus")
            requestPlaylistFocusSafe()

            val targetIndex = when {
                lastFocusedPlaylistIndex >= 0 -> lastFocusedPlaylistIndex
                uiState.currentChannelIndex >= 0 -> uiState.currentChannelIndex
                else -> -1
            }
            val resolvedIndex = when {
                targetIndex >= 0 && targetIndex < allChannels.size -> targetIndex
                allChannels.isNotEmpty() -> 0
                else -> -1
            }
            if (resolvedIndex >= 0) {
                debugLogger("EPG->Playlist: Focusing channel $resolvedIndex")
                focusCoordinator.focusPlaylist(resolvedIndex, false)
            }
        }

        if (uiState.showPlaylist) {
            PlaylistPanel(
                allChannels = allChannels,
                visibleChannels = displayedChannels,
                playlistTitleResId = uiState.playlistTitleResId,
                currentChannelIndex = uiState.currentChannelIndex,
                initialScrollIndex = uiState.lastPlaylistScrollIndex,
                epgOpenIndex = if (uiState.showEpgPanel) {
                    // Find the index of the channel whose EPG is open
                    allChannels.indexOfFirst { it.tvgId == uiState.epgChannelTvgId }
                } else {
                    -1
                },
                currentProgramsMap = uiState.currentProgramsMap,
                onChannelClick = actions.onPlayChannel,
                onFavoriteClick = actions.onToggleFavorite,
                onShowPrograms = actions.onShowEpgForChannel,
                onClose = actions.onClosePlaylist,
                onUpdateScrollIndex = actions.onUpdatePlaylistScrollIndex,
                onRequestMoreChannels = actions.onRequestMoreChannels,
                onProvideFocusController = { controller -> focusCoordinator.registerPlaylistController(controller) },
                onProvideFocusRequester = { requester ->
                    playlistFocusReady = requester != null
                    focusCoordinator.registerPlaylistRequester(requester)
                },
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
                epgDaysPast = uiState.epgDaysPast,
                epgDaysAhead = uiState.epgDaysAhead,
                epgLoadedFromUtc = uiState.epgLoadedFromUtc,
                epgLoadedToUtc = uiState.epgLoadedToUtc,
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
                onRequestFocus = { target -> requestEpgFocus(target) },
                onEnsureDateRange = actions.onEnsureEpgDateRange,
                onSetFallbackFocusSuppressed = { suppressFallbackEpgFocus = it },
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
private fun PlaylistPanel(
    allChannels: List<Channel>,
    visibleChannels: List<Channel>,
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
    onRequestMoreChannels: (Int) -> Unit,
    onProvideFocusController: (((Int, Boolean) -> Boolean)?) -> Unit = {},
    onProvideFocusRequester: ((FocusRequester?) -> Unit)? = null,
    onChannelFocused: ((Int) -> Unit)? = null,
    onRequestEpgFocus: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val channels = allChannels
    val displayedList = visibleChannels

    val resolvedInitialIndex = when {
        channels.isEmpty() -> -1
        currentChannelIndex in channels.indices -> currentChannelIndex
        initialScrollIndex in channels.indices -> initialScrollIndex
        else -> 0
    }
    val initialListIndex = if (displayedList.isEmpty()) 0 else resolvedInitialIndex.coerceIn(0, displayedList.lastIndex)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = max(initialListIndex, 0),
        initialFirstVisibleItemScrollOffset = 0
    )
    val coroutineScope = rememberCoroutineScope()
    var playlistHasFocus by remember { mutableStateOf(false) }
    var okDownTimestampMs by remember { mutableLongStateOf(0L) }
    var okLongPressHandled by remember { mutableStateOf(false) }
    var okLongPressJob by remember { mutableStateOf<Job?>(null) }
    var pendingInitialCenterIndex by remember(channels, displayedList.size, currentChannelIndex) {
        mutableStateOf(
            resolvedInitialIndex.takeIf { displayedList.isNotEmpty() && it in displayedList.indices }
        )
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

    // Focus requesters for header buttons
    val closeButtonFocus = remember { FocusRequester() }

    // Track which channel opened EPG for focus restoration
    var channelThatOpenedEpg by remember { mutableStateOf<Int?>(null) }
    var pendingScrollJob by remember { mutableStateOf<Job?>(null) }


    val focusChannel: (Int, Boolean) -> Boolean = { targetIndex, play ->
        if (targetIndex !in channels.indices) {
            false
        } else if (targetIndex !in displayedList.indices) {
            onRequestMoreChannels(targetIndex + PLAYLIST_PREFETCH_MARGIN)
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
                    listState.scrollToItem(targetIndex, scrollOffset = scrollOffset)
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

    LaunchedEffect(displayedList.size, focusedChannelIndex, channels.size) {
        if (focusedChannelIndex in channels.indices && focusedChannelIndex !in displayedList.indices) {
            onRequestMoreChannels(focusedChannelIndex + PLAYLIST_PREFETCH_MARGIN)
        }
    }

    LaunchedEffect(pendingInitialCenterIndex, displayedList.size) {
        val targetIndex = pendingInitialCenterIndex ?: return@LaunchedEffect
        if (channels.isEmpty()) {
            pendingInitialCenterIndex = null
            return@LaunchedEffect
        }
        if (targetIndex !in channels.indices) {
            pendingInitialCenterIndex = null
            return@LaunchedEffect
        }
        if (targetIndex !in displayedList.indices) {
            onRequestMoreChannels(targetIndex + PLAYLIST_PREFETCH_MARGIN)
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
    LaunchedEffect(epgOpenIndex, isRemoteMode, displayedList.size) {
        if (epgOpenIndex >= 0 && epgOpenIndex < channels.size) {
            channelThatOpenedEpg = epgOpenIndex
            if (epgOpenIndex !in displayedList.indices) {
                onRequestMoreChannels(epgOpenIndex + PLAYLIST_PREFETCH_MARGIN)
            }
        } else if (epgOpenIndex < 0 && channelThatOpenedEpg != null) {
            val channelIndex = channelThatOpenedEpg!!
            if (channelIndex >= 0 && channelIndex < channels.size && isRemoteMode) {
                if (channelIndex !in displayedList.indices) {
                    onRequestMoreChannels(channelIndex + PLAYLIST_PREFETCH_MARGIN)
                    pendingInitialCenterIndex = channelIndex
                } else {
                    delay(50)
                    focusChannel(channelIndex, false)
                    lazyColumnFocusRequester.requestFocus()
                    playlistHasFocus = true
                }
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
                val longPressThresholdMs = 450L

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
                            when (event.type) {
                                KeyEventType.KeyDown -> {
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
                                            if (okDownTimestampMs == 0L) {
                                                okDownTimestampMs = event.nativeKeyEvent?.downTime ?: System.currentTimeMillis()
                                                okLongPressHandled = false
                                                okLongPressJob?.cancel()
                                                okLongPressJob = coroutineScope.launch {
                                                    delay(longPressThresholdMs.toLong())
                                                    channels.getOrNull(focusedChannelIndex)?.let { ch ->
                                                        onFavoriteClick(ch.url)
                                                        okLongPressHandled = true
                                                    }
                                                }
                                            }
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
                                }
                                KeyEventType.KeyUp -> {
                                    when (event.key) {
                                        Key.DirectionCenter, Key.Enter -> {
                                            okLongPressJob?.cancel()
                                            val upTime = event.nativeKeyEvent?.eventTime ?: System.currentTimeMillis()
                                            val downTime = okDownTimestampMs.takeIf { it > 0 } ?: upTime
                                            val pressDuration = upTime - downTime
                                            val channel = channels.getOrNull(focusedChannelIndex)
                                            if (channel != null && !okLongPressHandled) {
                                                if (pressDuration >= longPressThresholdMs) {
                                                    onFavoriteClick(channel.url)
                                                } else {
                                                    focusChannel(focusedChannelIndex, true)
                                                }
                                            }
                                            okDownTimestampMs = 0L
                                            okLongPressHandled = false
                                            okLongPressJob = null
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                else -> false
                            }
                        },
                    contentPadding = PaddingValues(start = 0.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)
                ) {
                    itemsIndexed(
                        items = displayedList,
                        key = { index, channel -> channel.url.ifBlank { "channel_$index" } },
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

                LaunchedEffect(listState, displayedList.size, channels.size) {
                    if (channels.isEmpty()) return@LaunchedEffect
                    var lastRequestedForSize = -1
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                        .collect { lastVisible ->
                            val renderedCount = displayedList.size
                            if (renderedCount < channels.size &&
                                lastVisible >= renderedCount - PLAYLIST_PREFETCH_MARGIN &&
                                renderedCount > 0 &&
                                lastRequestedForSize != renderedCount
                            ) {
                                lastRequestedForSize = renderedCount
                                onRequestMoreChannels(renderedCount + PLAYLIST_PREFETCH_MARGIN)
                            } else if (renderedCount > lastRequestedForSize) {
                                lastRequestedForSize = -1
                            }
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
    epgDaysPast: Int,
    epgDaysAhead: Int,
    epgLoadedFromUtc: Long,
    epgLoadedToUtc: Long,
    onLoadMorePast: () -> Unit,
    onLoadMoreFuture: () -> Unit,
    onClose: () -> Unit,
    onNavigateLeftToChannels: (() -> Unit)? = null,
    onOpenPlaylist: (() -> Unit)? = null,
    onEnsureDateRange: (Long, Long) -> Unit,
    onSetFallbackFocusSuppressed: (Boolean) -> Unit,
    onRequestFocus: (Int?) -> Unit = {},
    focusRequestToken: Int = 0,
    focusRequestTargetIndex: Int? = null,
    onFocusRequestHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val currentTime = System.currentTimeMillis()
    val isRemoteMode = DeviceHelper.isRemoteInputActive()
    var epgListHasFocus by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var datePickerSelectionIndex by remember { mutableIntStateOf(0) }
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
    val (epgItems, programItemIndices) = remember(programs) {
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
            val baseKey = programStableKey(program, index)
            val absoluteIndex = itemsList.size
            itemsList.add(EpgUiItem(absoluteIndex, "program_$baseKey", program))
            indexMap[index] = absoluteIndex
        }
        itemsList to indexMap
    }

    val dateEntries = remember(
        epgDaysPast,
        epgDaysAhead,
        channel?.catchupDays,
        epgLoadedFromUtc,
        epgLoadedToUtc,
        currentTime
    ) {
        val zoneId = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(currentTime).atZone(zoneId).toLocalDate()
        val totalPast = epgDaysPast.coerceAtLeast(0)
        val totalAhead = epgDaysAhead.coerceAtLeast(0)
        val channelCatchupDays = channel?.catchupDays?.coerceAtLeast(0) ?: 0
        val entries = mutableListOf<EpgDateEntry>()
        for (offset in -totalPast..totalAhead) {
            val date = today.plusDays(offset.toLong())
            val dayStart = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zoneId).minusNanos(1).toInstant().toEpochMilli()
            val isPast = offset < 0
            val isToday = offset == 0
            val hasArchive = isPast && channel?.supportsCatchup() == true && abs(offset) <= channelCatchupDays
            val isLoaded = epgLoadedFromUtc != 0L && epgLoadedToUtc != 0L &&
                dayStart >= epgLoadedFromUtc && dayEnd <= epgLoadedToUtc
            entries.add(
                EpgDateEntry(
                    label = TimeFormatter.formatEpgDate(Date(dayStart)),
                    startMillis = dayStart,
                    endMillis = dayEnd,
                    isToday = isToday,
                    isPast = isPast,
                    hasArchive = hasArchive,
                    isLoaded = isLoaded
                )
            )
        }
        entries
    }
    val todayEntryIndex = dateEntries.indexOfFirst { it.isToday }.takeIf { it >= 0 } ?: 0

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
    var focusedProgramKey by remember(channel?.tvgId) {
        mutableStateOf(programs.getOrNull(resolvedInitialProgramIndex)?.let { programStableKey(it, resolvedInitialProgramIndex) })
    }
    var pendingProgramCenterIndex by remember(channel?.tvgId) {
        mutableStateOf(resolvedInitialItemIndex)
    }
    var pendingFocusAfterLoad by remember(channel?.tvgId) {
        mutableStateOf<Int?>(null)
    }
    var pendingCenterKeyAction by remember(channel?.tvgId) {
        mutableStateOf<CenterKeyAction?>(null)
    }
    var centerKeyConsumedAsLongPress by remember(channel?.tvgId) {
        mutableStateOf(false)
    }
    var pendingFocusDateRange by remember(channel?.tvgId) {
        mutableStateOf<LongRange?>(null)
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
        focusedProgramKey = programs.getOrNull(targetIndex)?.let { programStableKey(it, targetIndex) }
        val shouldScroll = !listState.isItemFullyVisible(itemIndex)
        coroutineScope.launch {
            if (shouldScroll) {
                listState.scrollToItem(itemIndex, scrollOffset = -200)
            }
        }
        return true
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

    LaunchedEffect(programs, channel?.tvgId, currentProgramIndex, programItemIndices) {
        if (programs.isEmpty()) {
            focusedProgramIndex = -1
            focusedProgramKey = null
            pendingProgramCenterIndex = null
            return@LaunchedEffect
        }

        fun applyFocus(index: Int, recenter: Boolean) {
            if (index !in programs.indices) return
            focusedProgramIndex = index
            focusedProgramKey = programStableKey(programs[index], index)
            if (recenter) {
                pendingProgramCenterIndex = programItemIndices.getOrNull(index)
            }
        }

        val storedKey = focusedProgramKey
        val currentIndex = focusedProgramIndex

        if (currentIndex !in programs.indices) {
            val fallbackIndex = when {
                storedKey != null -> {
                    programs.withIndex()
                        .firstOrNull { programStableKey(it.value, it.index) == storedKey }
                        ?.index
                }
                currentProgramIndex in programs.indices -> currentProgramIndex
                else -> 0
            } ?: 0
            applyFocus(fallbackIndex, recenter = false)
            return@LaunchedEffect
        }

        val currentKey = programStableKey(programs[currentIndex], currentIndex)
        if (storedKey == null) {
            focusedProgramKey = currentKey
            return@LaunchedEffect
        }

        if (currentKey != storedKey) {
            val restoredIndex = programs.withIndex()
                .firstOrNull { programStableKey(it.value, it.index) == storedKey }
                ?.index

            if (restoredIndex != null) {
                applyFocus(restoredIndex, recenter = true)
            } else {
                val fallbackIndex = when {
                    currentProgramIndex in programs.indices -> currentProgramIndex
                    else -> 0
                }
                applyFocus(fallbackIndex, recenter = true)
            }
        }
    }

    LaunchedEffect(programs.size, pendingFocusAfterLoad) {
        val target = pendingFocusAfterLoad
        if (target != null) {
            if (target in programs.indices) {
                val itemIndex = programItemIndices.getOrNull(target)
                if (itemIndex != null) {
                    focusedProgramIndex = target
                    focusedProgramKey = programs.getOrNull(target)?.let { programStableKey(it, target) }
                    pendingProgramCenterIndex = itemIndex
                }
                pendingFocusAfterLoad = null
            } else if (target < 0 || programs.isEmpty()) {
                pendingFocusAfterLoad = null
            }
        }
    }

    LaunchedEffect(programs, pendingFocusDateRange, epgLoadedFromUtc, epgLoadedToUtc) {
        val range = pendingFocusDateRange ?: return@LaunchedEffect
        val targetIndex = programs.indexOfFirst { program ->
            program.startTimeMillis in range
        }
        val coverageSatisfied = epgLoadedFromUtc != 0L && epgLoadedToUtc != 0L &&
            range.first >= epgLoadedFromUtc && range.last <= epgLoadedToUtc
        if (targetIndex >= 0) {
            programItemIndices.getOrNull(targetIndex)?.let { pendingProgramCenterIndex = it }
            focusProgram(targetIndex)
            onRequestFocus(targetIndex)
            pendingFocusDateRange = null
            onSetFallbackFocusSuppressed(false)
        } else if (coverageSatisfied) {
            pendingFocusDateRange = null
            onSetFallbackFocusSuppressed(false)
        }
    }

    LaunchedEffect(pendingProgramCenterIndex, programs, programItemIndices) {
        val targetItemIndex = pendingProgramCenterIndex ?: return@LaunchedEffect
        if (programs.isEmpty()) {
            pendingProgramCenterIndex = null
            onFocusRequestHandled()
            return@LaunchedEffect
        }
        val programIndex = programItemIndices.indexOf(targetItemIndex).takeIf { it >= 0 }
        if (programIndex == null) {
            pendingProgramCenterIndex = null
            onFocusRequestHandled()
            return@LaunchedEffect
        }
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }
            .filter { it }
            .first()
        listState.centerOn(targetItemIndex)
        programIndex?.let {
            focusedProgramIndex = it
            focusedProgramKey = programs.getOrNull(it)?.let { program ->
                programStableKey(program, it)
            }
        }
        epgListHasFocus = true
        pendingProgramCenterIndex = null
        onFocusRequestHandled()
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
                            val isCenterKey = event.key == Key.DirectionCenter || event.key == Key.Enter
                            when (event.type) {
                                KeyEventType.KeyDown -> {
                                    when (event.key) {
                                        Key.DirectionUp -> {
                                            if (focusedProgramIndex > 0) {
                                                focusProgram(focusedProgramIndex - 1)
                                            } else {
                                                pendingFocusAfterLoad = (focusedProgramIndex - 1).takeIf { it >= 0 }
                                                onLoadMorePast()
                                            }
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            val nextIndex = focusedProgramIndex + 1
                                            if (nextIndex < programs.size) {
                                                focusProgram(nextIndex)
                                            } else {
                                                pendingFocusAfterLoad = nextIndex
                                                onLoadMoreFuture()
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
                                                val isLongPress = (event.nativeKeyEvent?.repeatCount ?: 0) > 0
                                                if (isLongPress) {
                                                    if (!centerKeyConsumedAsLongPress) {
                                                        onProgramClick(program)
                                                        centerKeyConsumedAsLongPress = true
                                                    }
                                                    pendingCenterKeyAction = null
                                                } else {
                                                    pendingCenterKeyAction = CenterKeyAction(program, canPlayArchive)
                                                    centerKeyConsumedAsLongPress = false
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
                                            if (dateEntries.isNotEmpty()) {
                                                datePickerSelectionIndex = todayEntryIndex
                                                showDatePicker = true
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                        else -> false
                                    }
                                }
                                KeyEventType.KeyUp -> {
                                    if (isCenterKey) {
                                        val pendingAction = pendingCenterKeyAction
                                        if (pendingAction != null && !centerKeyConsumedAsLongPress) {
                                            if (pendingAction.canPlayArchive) {
                                                onPlayArchive(pendingAction.program)
                                            } else {
                                                onProgramClick(pendingAction.program)
                                            }
                                        }
                                        pendingCenterKeyAction = null
                                        centerKeyConsumedAsLongPress = false
                                    }
                                    true
                                }
                                else -> true
                            }
                        },
                    contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 20.dp, bottom = 4.dp) // Extra padding for 4dp focus border
                ) {
                    items(
                        items = epgItems,
                        key = { item -> item.key }
                    ) { entry ->
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

    if (showDatePicker) {
        EpgDatePickerDialog(
            entries = dateEntries,
            initialSelection = datePickerSelectionIndex,
            onSelect = { entry ->
                val entryIndex = dateEntries.indexOf(entry).takeIf { it >= 0 } ?: 0
                datePickerSelectionIndex = entryIndex
                val dayRange = entry.startMillis..entry.endMillis
                val targetIndex = programs.indexOfFirst { it.startTimeMillis in dayRange }
                if (targetIndex >= 0) {
                    focusProgram(targetIndex)
                    onRequestFocus(targetIndex)
                    onSetFallbackFocusSuppressed(false)
                } else {
                    pendingFocusDateRange = dayRange
                    onSetFallbackFocusSuppressed(true)
                }
                onEnsureDateRange(entry.startMillis, entry.endMillis)
                showDatePicker = false
            },
            onClose = { showDatePicker = false }
        )
    }
}


private fun programStableKey(program: EpgProgram, indexForHash: Int): String {
    if (program.id.isNotBlank()) {
        return program.id
    }
    val descriptionPart = if (program.description.isNotEmpty()) {
        "_${program.description.hashCode()}"
    } else {
        ""
    }
    return "${program.startTimeMillis}_${program.stopTimeMillis}_${program.title}_$indexForHash$descriptionPart"
}

private const val PLAYLIST_PREFETCH_MARGIN = 8

private data class CenterKeyAction(
    val program: EpgProgram,
    val canPlayArchive: Boolean
)

private data class EpgUiItem(
    val absoluteIndex: Int,
    val key: String,
    val payload: Any
)

private data class EpgDateEntry(
    val label: String,
    val startMillis: Long,
    val endMillis: Long,
    val isToday: Boolean,
    val isPast: Boolean,
    val hasArchive: Boolean,
    val isLoaded: Boolean
)

@Composable
private fun EpgDatePickerDialog(
    entries: List<EpgDateEntry>,
    initialSelection: Int,
    onSelect: (EpgDateEntry) -> Unit,
    onClose: () -> Unit
) {
    if (entries.isEmpty()) {
        onClose()
        return
    }
    val boundedInitial = initialSelection.coerceIn(0, entries.lastIndex)
    var selectedIndex by remember(entries) { mutableIntStateOf(boundedInitial) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = boundedInitial)
    val listFocusRequester = remember { FocusRequester() }
    val closeButtonFocusRequester = remember { FocusRequester() }
    var closeButtonFocused by remember { mutableStateOf(false) }

    LaunchedEffect(entries) {
        listFocusRequester.requestFocus()
    }

    LaunchedEffect(selectedIndex, entries.size) {
        listState.animateScrollToItem(selectedIndex.coerceIn(0, entries.lastIndex))
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Card(
            modifier = Modifier
                .width(LayoutConstants.PlaylistPanelWidth)
                .wrapContentHeight(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f)
            ),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f))
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LayoutConstants.ToolbarHeight)
                        .padding(horizontal = LayoutConstants.HeaderHorizontalPadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.dialog_title_epg_date_picker),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.ruTvColors.gold
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .focusable()
                            .focusRequester(closeButtonFocusRequester)
                            .focusProperties { down = listFocusRequester }
                            .onFocusChanged { closeButtonFocused = it.isFocused }
                            .then(focusIndicatorModifier(isFocused = closeButtonFocused))
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter, Key.Back -> {
                                        onClose()
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        selectedIndex = 0
                                        listFocusRequester.requestFocus()
                                        true
                                    }
                                    else -> false
                                }
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

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .padding(16.dp)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .focusRequester(listFocusRequester)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        selectedIndex = (selectedIndex + 1).coerceAtMost(entries.lastIndex)
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        if (selectedIndex == 0) {
                                            closeButtonFocusRequester.requestFocus()
                                            true
                                        } else {
                                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                            true
                                        }
                                    }
                                    Key.DirectionCenter, Key.Enter -> {
                                        onSelect(entries[selectedIndex])
                                        true
                                    }
                                    Key.Back -> {
                                        onClose()
                                        true
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        itemsIndexed(entries) { index, entry ->
                            val isSelected = index == selectedIndex
                            val rowAlpha = if (entry.isPast) 0.6f else 1f
                            val textColor = when {
                                isSelected -> MaterialTheme.ruTvColors.gold
                                entry.isToday -> MaterialTheme.ruTvColors.gold
                                entry.isPast -> MaterialTheme.ruTvColors.textSecondary
                                else -> MaterialTheme.ruTvColors.textPrimary
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.ruTvColors.selectedBackground
                                        else Color.Transparent
                                    )
                                    .alpha(rowAlpha)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = entry.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = textColor
                                )
                                if (entry.hasArchive) {
                                    Icon(
                                        imageVector = Icons.Filled.History,
                                        contentDescription = stringResource(R.string.cd_epg_archive_indicator),
                                        tint = MaterialTheme.ruTvColors.gold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MEDIA3_UI_PACKAGE = "androidx.media3.ui"
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

private enum class CustomControlFocusTarget {
    Favorites,
    Rotate
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
    // Keep controller visible until we explicitly hide it (we manage timeout ourselves)
    controllerShowTimeoutMs = Int.MAX_VALUE
    controllerHideOnTouch = false
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
    onNavigateRightToRotate: (() -> Unit)?,
    onControlsInteraction: (() -> Unit)?
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
        onNavigateRightToRotate = onNavigateRightToRotate,
        onControlsInteraction = onControlsInteraction
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
    onNavigateRightToRotate: (() -> Unit)? = null,
    onControlsInteraction: (() -> Unit)? = null
) {
    setShowPreviousButton(true)
    setShowNextButton(true)
    setShowRewindButton(true)
    setShowFastForwardButton(true)

    // Intercept DPAD at PlayerView level to keep arrow keys within controls and enable long-press escape
    setOnKeyListener { _, keyCode, event ->
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
        onControlsInteraction?.invoke()
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val isLeft = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                if (event.repeatCount > 0 || event.isLongPress) {
                    Timber.d("CustomControlFocus | PlayerView long-press %s -> custom controls", if (isLeft) "LEFT" else "RIGHT")
                    if (isLeft) onNavigateLeftToFavorites?.invoke() else onNavigateRightToRotate?.invoke()
                    return@setOnKeyListener true
                }
                val focused = findFocus()
                val direction = if (isLeft) View.FOCUS_LEFT else View.FOCUS_RIGHT
                val next = focused?.focusSearch(direction)
                if (next != null && next != focused) {
                    next.requestFocus()
                    return@setOnKeyListener true
                }
                false
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                val timeBar = findControlView("exo_timebar") ?: findControlView("exo_progress")
                if (timeBar?.isShown == true && timeBar.isFocusable) {
                    timeBar.requestFocus()
                    return@setOnKeyListener true
                }
                false
            }
            else -> false
        }
    }

    findControlView("exo_prev")?.apply {
        visibility = View.VISIBLE
        enableControl()
        setOnClickListener { onRestartPlayback() }
        isFocusable = true
        isFocusableInTouchMode = false
        setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                DeviceHelper.updateLastInputMethod(event)
                onControlsInteraction?.invoke()
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                    (event.repeatCount > 0 || event.isLongPress)) {
                    onNavigateLeftToFavorites?.invoke()
                    return@setOnKeyListener true
                }
            }
            false
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
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                DeviceHelper.updateLastInputMethod(event)
                onControlsInteraction?.invoke()
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
                    (event.repeatCount > 0 || event.isLongPress)) {
                    post { onNavigateRightToRotate?.invoke() }
                    return@setOnKeyListener true
                }
            }
            false
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
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    DeviceHelper.updateLastInputMethod(event)
                    onControlsInteraction?.invoke()
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                        (event.repeatCount > 0 || event.isLongPress)) {
                        Timber.d("CustomControlFocus | rewind control long-press LEFT -> Favorites")
                        post { onNavigateLeftToFavorites?.invoke() }
                        return@setOnKeyListener true
                    }
                }
                false
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
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    DeviceHelper.updateLastInputMethod(event)
                    onControlsInteraction?.invoke()
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
                        (event.repeatCount > 0 || event.isLongPress)) {
                        Timber.d("CustomControlFocus | ffwd control long-press RIGHT -> Rotate")
                        post { onNavigateRightToRotate?.invoke() }
                        return@setOnKeyListener true
                    }
                }
                false
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
    }

    // Position time text views - they should already be in the layout on left/right
    // Just ensure they're vertically aligned with the progress bar
    val positionView = findControlView("exo_position")
    val durationView = findControlView("exo_duration")

    positionView?.translationY = 0f
    durationView?.translationY = 0f
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





