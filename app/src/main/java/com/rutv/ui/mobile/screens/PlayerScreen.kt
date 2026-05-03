package com.rutv.ui.mobile.screens

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rutv.util.DeviceHelper
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
import com.rutv.ui.mobile.screens.rememberPlayerFocusManager
import com.rutv.ui.mobile.screens.PlayerFocusDestination
import com.rutv.ui.shared.components.ArchivePromptDialog
import com.rutv.ui.shared.components.EpgNotificationToast
import com.rutv.ui.shared.components.CustomControlButtons
import com.rutv.ui.shared.components.RemotePressRegistry
import com.rutv.ui.shared.components.requestFocusSafely
import com.rutv.ui.theme.ruTvColors
import com.rutv.ui.shared.presentation.LayoutConstants
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.max
import timber.log.Timber
import com.rutv.presentation.player.PlaybackIssue
import com.rutv.presentation.player.ChannelPreviewPlaybackState
import com.rutv.presentation.player.PlayerState
import com.rutv.presentation.player.PreviewPlayerFactory
import java.lang.ref.WeakReference

/**
 * Main Player Screen with Compose UI
 */
@UnstableApi
@Composable
fun PlayerScreen(
    uiState: PlayerUiState,
    player: ExoPlayer?,
    previewPlayerFactory: PreviewPlayerFactory,
    actions: PlayerUiActions,
    onRegisterToggleControls: ((() -> Unit)) -> Unit,
    onControlsVisibilityChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Startup fast path: while the player is not attached yet, keep composition minimal.
    // This avoids heavy focus/control/panel setup during the first frames on slower STBs.
    if (player == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.ruTvColors.darkBackground)
        ) {
            EpgNotificationToast(
                message = uiState.epgNotificationMessage,
                onDismiss = actions.onClearEpgNotification,
                modifier = Modifier
            )
        }
        return
    }

    val focusManager = rememberPlayerFocusManager(initial = PlayerFocusDestination.NONE)
    val coroutineScope = rememberCoroutineScope()
    var showControls by remember { mutableStateOf(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var allowPlayerView by remember { mutableStateOf(false) }
    val controlsAutoHideJobRef = remember { object { var job: Job? = null } }
    val controllerVisibilityCallback by rememberUpdatedState<(Boolean) -> Unit> { visible ->
        if (showControls != visible) {
            showControls = visible
        }
    }

    // Store focus requesters for custom controls (for ExoPlayer navigation)
    var leftColumnFocusRequesters by remember { mutableStateOf<List<FocusRequester>?>(null) }
    var rightColumnFocusRequesters by remember { mutableStateOf<List<FocusRequester>?>(null) }
    var lastFocusedPlaylistIndex by remember { mutableIntStateOf(uiState.currentChannelFilteredIndex.coerceAtLeast(0)) }
    var lastControlsSignature by remember { mutableStateOf<ControlsSignature?>(null) }
    val customControlFocusCoordinator = rememberCustomControlFocusCoordinator()

    // Helper function to focus ExoPlayer controls (consolidated logic)
    val focusExoPlayerControls: (Boolean) -> Unit = remember(playerViewRef) {
        { focusLeftmost: Boolean ->
            playerViewRef?.post {
                if (focusLeftmost) {
                    playerViewRef?.focusOnControl(
                        "exo_prev",
                        "exo_rew",
                        "exo_rew_with_amount",
                        "exo_play_pause",
                        "exo_play",
                        "exo_pause"
                    )
                } else {
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
        }
    }

    // Callbacks to move focus to custom controls (used by ExoPlayer controls)
    var navigateToFavoritesCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var navigateToRotateCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var setFavoritesFocusHint by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var setRotateFocusHint by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    // Track if we're navigating within player controls (ExoPlayer <-> Custom buttons)
    var isNavigatingWithinPlayerControls by remember { mutableStateOf(false) }
    val requestCustomControlFocus: (CustomControlFocusTarget) -> Unit = { target ->
        isNavigatingWithinPlayerControls = true
        when (target) {
            CustomControlFocusTarget.Favorites -> setFavoritesFocusHint?.invoke(true)
            CustomControlFocusTarget.Rotate -> setRotateFocusHint?.invoke(true)
        }
        val request = {
            customControlFocusCoordinator.requestFocus(
                target,
                leftColumnFocusRequesters,
                rightColumnFocusRequesters
            )
            isNavigatingWithinPlayerControls = false
        }
        playerViewRef?.post { request() } ?: request()
    }
    val latestRequestCustomControlFocus by rememberUpdatedState(newValue = requestCustomControlFocus)
    val invokeNavigateToFavorites: () -> Unit = remember {
        {
            navigateToFavoritesCallback?.invoke()
                ?: latestRequestCustomControlFocus(CustomControlFocusTarget.Favorites)
        }
    }
    val invokeNavigateToRotate: () -> Unit = remember {
        {
            navigateToRotateCallback?.invoke()
                ?: latestRequestCustomControlFocus(CustomControlFocusTarget.Rotate)
        }
    }

    // Focus Requesters for Channel Info Overlay buttons
    val overlayReturnToLiveFocus = remember { FocusRequester() }
    val overlayProgramInfoFocus = remember { FocusRequester() }

    // Pending overlay focus request — driven from Android View key listeners,
    // consumed by a LaunchedEffect that can reliably move Compose focus.
    var pendingOverlayFocusTarget by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(pendingOverlayFocusTarget) {
        val target = pendingOverlayFocusTarget ?: return@LaunchedEffect
        // Clear Android View focus first so Compose can claim it
        playerViewRef?.clearFocus()
        // Retry across frames — the overlay node may not be attached yet on slower STBs
        repeat(5) {
            if (target.requestFocusSafely()) {
                pendingOverlayFocusTarget = null
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
        pendingOverlayFocusTarget = null
    }

    val forceFavoritesHighlight: () -> Unit = {
        setFavoritesFocusHint?.invoke(true)
    }
    val forceRotateHighlight: () -> Unit = {
        setRotateFocusHint?.invoke(true)
    }

    // Auto-hide controls without driving recomposition on every DPAD event.
    val registerControlsInteraction: () -> Unit = registerControlsInteraction@{
        val playerView = playerViewRef ?: return@registerControlsInteraction
        controlsAutoHideJobRef.job?.cancel()
        controlsAutoHideJobRef.job = coroutineScope.launch {
            delay(3000L)
            if (showControls) {
                showControls = false
                playerView.hideController()
            }
        }
    }
    val focusPrimaryPlayerControl: () -> Unit = remember(playerViewRef) {
        {
            playerViewRef?.post {
                playerViewRef?.focusOnControl(
                    "exo_play_pause",
                    "exo_play",
                    "exo_pause",
                    "exo_rew",
                    "exo_rew_with_amount",
                    "exo_ffwd",
                    "exo_ffwd_with_amount",
                    "exo_prev",
                    "exo_next"
                )
            }
        }
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

    // Defer PlayerView inflation to *after* the first frame to avoid huge cold-start jank
    // (PlayerView inflation + controller setup can take hundreds of ms on some STBs).
    val localView = LocalView.current
    LaunchedEffect(player) {
        // Post to the UI thread message queue after Compose has had a chance to draw.
        // This doesn't require any additional dependencies and works well on Android TV boxes.
        allowPlayerView = false
        localView.post {
            allowPlayerView = true
        }
    }

    // Handle player controls visibility and focus
    // Note: ExoPlayer controls are Android Views, so we handle focus directly via View.requestFocus()
    // rather than using a Compose FocusRequester
    LaunchedEffect(showControls, playerViewRef, focusManager.currentDestination) {
        val currentPlayerView = playerViewRef ?: return@LaunchedEffect
        currentPlayerView.post {
            if (showControls) {
                if (!currentPlayerView.isControllerFullyVisible) {
                    currentPlayerView.showController()
                }
                // Request focus on ExoPlayer controls when PLAYER_CONTROLS is active
                // Only if we're not in the middle of navigating within player controls
                if (focusManager.currentDestination == PlayerFocusDestination.PLAYER_CONTROLS && !isNavigatingWithinPlayerControls) {
                    focusPrimaryPlayerControl()
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
        if (showControls) {
            registerControlsInteraction()
        } else {
            controlsAutoHideJobRef.job?.cancel()
            controlsAutoHideJobRef.job = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controlsAutoHideJobRef.job?.cancel()
            controlsAutoHideJobRef.job = null
        }
    }

    // Sync UI state with focus manager - single source of truth
    LaunchedEffect(uiState.selectedProgramDetails, uiState.showEpgPanel, uiState.showPlaylist, showControls) {
        val target = when {
            uiState.selectedProgramDetails != null -> PlayerFocusDestination.PROGRAM_DETAILS
            uiState.showEpgPanel -> PlayerFocusDestination.EPG_PANEL
            uiState.showPlaylist -> PlayerFocusDestination.PLAYLIST_PANEL
            showControls -> PlayerFocusDestination.PLAYER_CONTROLS
            else -> PlayerFocusDestination.NONE
        }
        if (focusManager.currentDestination != target) {
            focusManager.requestEnter(target)
        }
    }

    // Watch for pending focus requests (handles race conditions when requester not yet registered)
    focusManager.WatchForPendingRequests()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewController = remember(previewPlayerFactory, context, uiState.playerConfig) {
        previewPlayerFactory.create(context, uiState.playerConfig)
    }
    val previewPlaybackState by previewController.state.collectAsState()
    var previewTarget by remember { mutableStateOf<ChannelPreviewTarget?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var previewLifecycleActive by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(previewController) {
        onDispose {
            previewController.release()
        }
    }

    DisposableEffect(previewController, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> previewLifecycleActive = true
                Lifecycle.Event.ON_STOP -> {
                    previewLifecycleActive = false
                    previewController.stopForLifecycle()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(
        previewTarget,
        uiState.channelPreviewEnabled,
        uiState.showPlaylist,
        uiState.showEpgPanel,
        uiState.selectedProgramDetails,
        uiState.currentChannel?.url,
        previewLifecycleActive
    ) {
        val target = previewTarget
        val shouldStop = target == null ||
            !uiState.channelPreviewEnabled ||
            !previewLifecycleActive ||
            !uiState.showPlaylist ||
            uiState.showEpgPanel ||
            uiState.selectedProgramDetails != null ||
            !target.playlistHasFocus ||
            target.url.isBlank() ||
            target.url == uiState.currentChannel?.url

        if (shouldStop) {
            previewController.stop()
            return@LaunchedEffect
        }

        delay(CHANNEL_PREVIEW_DEBOUNCE_MS)
        if (!previewLifecycleActive) return@LaunchedEffect
        previewController.preview(target.url)
    }

    LaunchedEffect(uiState.showPlaylist) {
        if (uiState.showPlaylist && uiState.channelPreviewEnabled) {
            previewController.resetSession()
        } else {
            previewTarget = null
            previewController.stop()
        }
    }

    LaunchedEffect(uiState.channelPreviewEnabled) {
        if (!uiState.channelPreviewEnabled) {
            previewTarget = null
            previewController.stop()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
            .background(MaterialTheme.ruTvColors.darkBackground)
    ) {
        // EPG Notification
        EpgNotificationToast(
            message = uiState.epgNotificationMessage,
            onDismiss = actions.onClearEpgNotification,
            modifier = Modifier
        )

        // ExoPlayer View (inflated lazily to reduce cold-start jank)
        if (allowPlayerView) {
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
                    playerView.resizeMode = uiState.currentResizeMode.intValue
                    val controlsSignature = ControlsSignature(
                        isArchivePlayback = uiState.isArchivePlayback
                    )
                    if (controlsSignature != lastControlsSignature) {
                        playerView.bindControls(
                            uiState = uiState,
                            actions = actions,
                            onNavigateLeftToFavorites = invokeNavigateToFavorites,
                            onNavigateRightToRotate = invokeNavigateToRotate,
                            onControlsInteraction = { registerControlsInteraction() },
                            onForceFavoritesHighlight = forceFavoritesHighlight,
                            onForceRotateHighlight = forceRotateHighlight,
                            onNavigateUpToOverlay = {
                                // Navigate from ExoPlayer controls UP to Channel Info Overlay
                                if (showControls) {
                                    // Set pending target — LaunchedEffect will clear Android View
                                    // focus and retry Compose focus across frames
                                    pendingOverlayFocusTarget = if (uiState.isArchivePlayback || uiState.isTimeshiftPlayback) {
                                        overlayReturnToLiveFocus
                                    } else {
                                        overlayProgramInfoFocus
                                    }
                                }
                            }
                        )
                        lastControlsSignature = controlsSignature
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            }
        }


        // Custom Control Buttons Overlay (bottom) - synced with ExoPlayer controls
        // No animation - hide/show instantly together with ExoPlayer controls
        if (showControls) {
            CustomControlButtons(
                onPlaylistClick = actions.onTogglePlaylist,
                onFavoritesClick = actions.onToggleFavorites,
                onChannelGroupsClick = actions.onShowChannelGroups,
                onAspectRatioClick = actions.onCycleAspectRatio,
                onSettingsClick = actions.onOpenSettings,
                onNavigateRightFromFavorites = {
                    // Only navigate if PLAYER_CONTROLS is the active destination
                    if (focusManager.currentDestination == PlayerFocusDestination.PLAYER_CONTROLS) {
                        registerControlsInteraction()
                        isNavigatingWithinPlayerControls = true
                        // Use post for consistent timing with ExoPlayer controls
                        playerViewRef?.post {
                            focusExoPlayerControls(true)
                        }
                        // Reset flag after the focus request is enqueued (no time-based delay)
                        playerViewRef?.post { isNavigatingWithinPlayerControls = false }
                    }
                },
                onNavigateLeftFromRotate = {
                    // Only navigate if PLAYER_CONTROLS is the active destination
                    if (focusManager.currentDestination == PlayerFocusDestination.PLAYER_CONTROLS) {
                        registerControlsInteraction()
                        isNavigatingWithinPlayerControls = true
                        // Use post for consistent timing with ExoPlayer controls
                        playerViewRef?.post {
                            focusExoPlayerControls(false)
                        }
                        // Reset flag after the focus request is enqueued (no time-based delay)
                        playerViewRef?.post { isNavigatingWithinPlayerControls = false }
                    }
                },
                focusManager = focusManager,
                onRegisterFocusRequesters = { left, right ->
                    leftColumnFocusRequesters = left
                    rightColumnFocusRequesters = right
                    // Update callbacks for ExoPlayer navigation
                    navigateToFavoritesCallback = {
                        // Navigate from ExoPlayer controls to custom buttons
                        // Don't check destination - allow navigation when controls are visible
                        registerControlsInteraction()
                        requestCustomControlFocus(CustomControlFocusTarget.Favorites)
                    }
                    navigateToRotateCallback = {
                        // Navigate from ExoPlayer controls to custom buttons
                        // Don't check destination - allow navigation when controls are visible
                        registerControlsInteraction()
                        requestCustomControlFocus(CustomControlFocusTarget.Rotate)
                    }
                },
                onRegisterForcedFocusHints = { setFav, setRot ->
                    setFavoritesFocusHint = setFav
                    setRotateFocusHint = setRot
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        customControlFocusCoordinator.Bind(leftColumnFocusRequesters, rightColumnFocusRequesters)

        // Channel Info Overlay (top center) - hide with controls
        // No animation - hide/show instantly together with ExoPlayer controls
        if (showControls) {
            uiState.currentChannel?.let { channel ->
                val displayChannelNumber = if (uiState.currentChannelFilteredIndex >= 0) {
                    uiState.currentChannelFilteredIndex + 1
                } else {
                    uiState.currentChannelIndex + 1
                }
                ChannelInfoOverlay(
                    channelNumber = displayChannelNumber,
                    channel = channel,
                    currentProgram = uiState.currentProgram,
                    isArchivePlayback = uiState.isArchivePlayback,
                    isTimeshiftPlayback = uiState.isTimeshiftPlayback,
                    archiveProgram = uiState.archiveProgram,
                    onReturnToLive = actions.onReturnToLive,
                    onShowProgramInfo = actions.onShowProgramDetails,
                    onNavigateDown = { focusPrimaryPlayerControl() },
                    returnToLiveFocusRequester = overlayReturnToLiveFocus,
                    programInfoFocusRequester = overlayProgramInfoFocus,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(LayoutConstants.DefaultPadding)
                        .fillMaxWidth(0.4f)
                )
            }
        }

        // Playback error/status overlay (always visible; user must understand provider/token states)
        val playbackError = remember(uiState.playerState) {
            uiState.playerState as? PlayerState.Error
        }
        val playbackErrorText = playbackError?.let { localizedPlaybackIssueMessage(it.issue) }
        val playbackRetrying = playbackError?.isRetrying == true
        val retryingLabel = if (playbackError != null &&
            playbackError.retryAttempt > 0 &&
            playbackError.retryMaxAttempts > 0
        ) {
            stringResource(
                R.string.label_retrying_attempts_short,
                playbackError.retryAttempt,
                playbackError.retryMaxAttempts
            )
        } else {
            stringResource(R.string.label_retrying_short)
        }
        playbackErrorText?.let { text ->
            val statusText = if (playbackRetrying) {
                "$text - $retryingLabel"
            } else {
                text
            }
            PlaybackStatusOverlay(
                text = statusText,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 88.dp)
                    .fillMaxWidth(0.45f)
            )
        }

        val allChannels = uiState.filteredChannels
        val displayedChannels = uiState.visibleChannels

        // Focus management for panel transitions
        val focusPlaylistFromEpg: () -> Unit = {
            focusManager.requestEnter(PlayerFocusDestination.PLAYLIST_PANEL)

            // Focus the specific channel index
            val targetIndex = when {
                lastFocusedPlaylistIndex >= 0 -> lastFocusedPlaylistIndex
                uiState.currentChannelFilteredIndex >= 0 -> uiState.currentChannelFilteredIndex
                else -> -1
            }
            val resolvedIndex = when {
                targetIndex >= 0 && targetIndex < allChannels.size -> targetIndex
                allChannels.isNotEmpty() -> 0
                else -> -1
            }
            if (resolvedIndex >= 0) {
                focusManager.focusItem(PlayerFocusDestination.PLAYLIST_PANEL, resolvedIndex, false)
            }
        }

        if (uiState.showPlaylist) {
            PlaylistPanel(
                allChannels = allChannels,
                visibleChannels = displayedChannels,
                playlistTitleResId = uiState.playlistTitleResId,
                selectedGroup = uiState.selectedGroup,
                currentChannelIndex = uiState.currentChannelFilteredIndex,
                currentChannelStatusText = if (playbackRetrying && playbackErrorText != null) {
                    "${playbackErrorText} - $retryingLabel"
                } else {
                    playbackErrorText
                },
                initialScrollIndex = uiState.lastPlaylistScrollIndex,
                epgOpenIndex = if (uiState.showEpgPanel) {
                    // Find the index of the channel whose EPG is open
                    allChannels.indexOfFirst { it.tvgId == uiState.epgChannelTvgId }
                } else {
                    -1
                },
                currentProgramsMap = uiState.currentProgramsMap,
                showCurrentProgramInChannelList = uiState.showCurrentProgramInChannelList,
                onChannelClick = { index ->
                    previewTarget = null
                    previewController.stop()
                    actions.onPlayChannel(index)
                },
                onFavoriteClick = actions.onToggleFavorite,
                onShowPrograms = { tvgId ->
                    previewTarget = null
                    previewController.stop()
                    actions.onShowEpgForChannel(tvgId)
                },
                onClose = {
                    previewTarget = null
                    previewController.stop()
                    actions.onClosePlaylist()
                },
                onUpdateScrollIndex = actions.onUpdatePlaylistScrollIndex,
                onRequestMoreChannels = actions.onRequestMoreChannels,
                onVisibleChannelsChanged = actions.onVisibleChannelsChanged,
                focusManager = focusManager,
                onChannelFocused = { index ->
                    if (index >= 0) {
                        lastFocusedPlaylistIndex = index
                    }
                },
                onPreviewTargetChanged = { target -> previewTarget = target },
                onRequestEpgFocus = {
                    previewTarget = null
                    previewController.stop()
                    focusManager.requestEnter(PlayerFocusDestination.EPG_PANEL)
                },
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }

        val effectivePreviewTarget = previewTarget?.takeIf {
            uiState.showPlaylist &&
                uiState.channelPreviewEnabled &&
                !uiState.showEpgPanel &&
                uiState.selectedProgramDetails == null &&
                it.playlistHasFocus
        }
        val unavailablePreviewUrl = (previewPlaybackState as? ChannelPreviewPlaybackState.Unavailable)?.url
        val showNowPlayingPreview = effectivePreviewTarget?.url == uiState.currentChannel?.url
        val showUnavailablePreview =
            effectivePreviewTarget != null &&
                unavailablePreviewUrl == effectivePreviewTarget.url
        val activePreviewUrl = when (val state = previewPlaybackState) {
            is ChannelPreviewPlaybackState.Loading -> state.url
            is ChannelPreviewPlaybackState.Playing -> state.url
            else -> null
        }
        val showVideoPreview =
            effectivePreviewTarget != null &&
                !showNowPlayingPreview &&
                !showUnavailablePreview &&
                previewPlaybackState !is ChannelPreviewPlaybackState.SessionDisabled &&
                activePreviewUrl == effectivePreviewTarget.url

        if (effectivePreviewTarget != null &&
            (showNowPlayingPreview || showUnavailablePreview || showVideoPreview)
        ) {
            ChannelPreviewOverlay(
                target = effectivePreviewTarget,
                rootSize = rootSize,
                previewController = previewController,
                showVideo = showVideoPreview,
                placeholderText = when {
                    showNowPlayingPreview -> stringResource(R.string.channel_preview_now_playing)
                    showUnavailablePreview -> stringResource(R.string.channel_preview_unavailable)
                    else -> null
                },
                modifier = Modifier.align(Alignment.TopStart)
            )
        }

        // EPG Panel
        val epgChannel = uiState.epgChannel
        if (uiState.showEpgPanel) {
            EpgPanel(
                programs = uiState.epgPrograms,
                channel = epgChannel,
                isLoading = uiState.isEpgLoading,
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
                focusManager = focusManager,
                onEnsureDateRange = actions.onEnsureEpgDateRange,
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
                        focusManager.requestEnter(PlayerFocusDestination.EPG_PANEL)
                    }
                },
                modifier = Modifier.align(Alignment.Center)
            )
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

@Composable
private fun PlaybackStatusOverlay(
    text: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
        ),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChannelPreviewOverlay(
    target: ChannelPreviewTarget,
    rootSize: IntSize,
    previewController: com.rutv.presentation.player.ChannelPreviewController,
    showVideo: Boolean,
    placeholderText: String?,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val previewWidth = 256.dp
    val previewHeight = 144.dp
    val previewWidthPx = with(density) { previewWidth.roundToPx() }
    val previewHeightPx = with(density) { previewHeight.roundToPx() }
    val x = LayoutConstants.DefaultPadding + LayoutConstants.PlaylistPanelWidth + CHANNEL_PREVIEW_LIST_GAP_DP.dp
    val y = with(density) {
        val desiredCenter = LayoutConstants.DefaultPadding.roundToPx() +
            LayoutConstants.ToolbarHeight.roundToPx() +
            CHANNEL_PREVIEW_ROW_CENTER_ADJUST_DP.dp.roundToPx() +
            target.rowTopPx +
            (target.rowHeightPx / 2)
        val maxY = (rootSize.height - previewHeightPx - LayoutConstants.DefaultPadding.roundToPx())
            .coerceAtLeast(LayoutConstants.DefaultPadding.roundToPx())
        (desiredCenter - (previewHeightPx / 2))
            .coerceIn(LayoutConstants.DefaultPadding.roundToPx(), maxY)
            .toDp()
    }
    val maxX = with(density) {
        (rootSize.width - previewWidthPx - LayoutConstants.DefaultPadding.roundToPx())
            .coerceAtLeast(LayoutConstants.DefaultPadding.roundToPx())
            .toDp()
    }
    val clampedX = minOf(x, maxX)

    Box(
        modifier = modifier
            .offset(x = clampedX, y = y)
            .size(width = previewWidth, height = previewHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.96f))
            .border(
                BorderStroke(1.dp, MaterialTheme.ruTvColors.gold.copy(alpha = 0.8f)),
                RoundedCornerShape(8.dp)
            )
    ) {
        if (showVideo) {
            AndroidView(
                factory = {
                    previewController.obtainPlayerView().apply {
                        useController = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                    }
                },
                update = { playerView ->
                    playerView.useController = false
                    playerView.isFocusable = false
                    playerView.isFocusableInTouchMode = false
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (placeholderText != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = placeholderText,
                    color = MaterialTheme.ruTvColors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}

private const val MEDIA3_UI_PACKAGE = "androidx.media3.ui"
private const val CHANNEL_PREVIEW_DEBOUNCE_MS = 500L
private const val CHANNEL_PREVIEW_LIST_GAP_DP = 4
private const val CHANNEL_PREVIEW_ROW_CENTER_ADJUST_DP = 8
private val CONTROL_LOOKUP_CACHE_TAG_KEY: Int = R.id.tag_player_control_lookup_cache
private data class ControlLookupCache(
    val candidateIdsByName: MutableMap<String, IntArray> = mutableMapOf(),
    val viewByName: MutableMap<String, WeakReference<View>> = mutableMapOf()
)

private fun PlayerView.controlLookupCache(): ControlLookupCache {
    val existing = getTag(CONTROL_LOOKUP_CACHE_TAG_KEY) as? ControlLookupCache
    if (existing != null) return existing
    return ControlLookupCache().also { setTag(CONTROL_LOOKUP_CACHE_TAG_KEY, it) }
}

@SuppressLint("DiscouragedApi")
private fun PlayerView.resolveControlCandidateIds(name: String): IntArray {
    val candidates = buildList {
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
    return candidates.distinct().toIntArray()
}

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
    val cache = controlLookupCache()
    cache.viewByName[name]?.get()?.let { return it }

    val candidateIds = cache.candidateIdsByName[name] ?: resolveControlCandidateIds(name).also {
        cache.candidateIdsByName[name] = it
    }

    candidateIds.forEach { id ->
        findViewById<View>(id)?.let { view ->
            cache.viewByName[name] = WeakReference(view)
            return view
        }
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

private data class ControlsSignature(
    val isArchivePlayback: Boolean
)

private fun PlayerView.configurePlayerView(
    uiState: PlayerUiState,
    onControllerVisibilityChanged: (Boolean) -> Unit
) {
    useController = true
    // Controller visibility is app-driven via a single key-routing owner in MainActivity.
    // Prevent PlayerView from auto-showing controls on internal heuristics.
    try {
        val method = PlayerView::class.java.getMethod(
            "setControllerAutoShow",
            Boolean::class.javaPrimitiveType
        )
        method.invoke(this, false)
    } catch (_: Exception) {
        // Method not available, ignore
    }
    // Keep controller visible until we explicitly hide it (we manage timeout ourselves)
    controllerShowTimeoutMs = Int.MAX_VALUE
    controllerHideOnTouch = false
    resizeMode = uiState.currentResizeMode.intValue
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
    onNavigateUpToOverlay: (() -> Unit)? = null,
    onControlsInteraction: (() -> Unit)?,
    onForceFavoritesHighlight: (() -> Unit)? = null,
    onForceRotateHighlight: (() -> Unit)? = null
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
        onNavigateUpToOverlay = onNavigateUpToOverlay,
        onControlsInteraction = onControlsInteraction,
        onForceFavoritesHighlight = onForceFavoritesHighlight,
        onForceRotateHighlight = onForceRotateHighlight
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
    onNavigateUpToOverlay: (() -> Unit)? = null,
    onControlsInteraction: (() -> Unit)? = null,
    onForceFavoritesHighlight: (() -> Unit)? = null,
    onForceRotateHighlight: (() -> Unit)? = null
) {
    setShowPreviousButton(true)
    setShowNextButton(true)
    setShowRewindButton(true)
    setShowFastForwardButton(true)

    val orderedControlViews = listOf(
        "exo_prev",
        "exo_rew",
        "exo_rew_with_amount",
        "exo_play_pause",
        "exo_play",
        "exo_pause",
        "exo_ffwd_with_amount",
        "exo_ffwd",
        "exo_next"
    ).mapNotNull { findControlView(it) }.distinct()

    fun moveWithinExo(from: View, toLeft: Boolean): Boolean {
        val idx = orderedControlViews.indexOf(from).takeIf { it >= 0 } ?: return false
        val targetIdx = if (toLeft) idx - 1 else idx + 1
        val target = orderedControlViews.getOrNull(targetIdx)
        return if (target != null && target.isFocusable && target.visibility == View.VISIBLE) {
            target.requestFocus()
            true
        } else false
    }

    fun moveDownToTimeBar(): Boolean {
        val timeBar = findControlView("exo_timebar") ?: findControlView("exo_progress")
        if (timeBar?.isShown == true && timeBar.isFocusable) {
            timeBar.requestFocus()
            return true
        }
        return false
    }

    fun moveUpToOverlay(): Boolean {
        onNavigateUpToOverlay?.invoke()
        return true
    }

    fun navigateSideByLongPress(keyCode: Int): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                onForceFavoritesHighlight?.invoke()
                post { onNavigateLeftToFavorites?.invoke() }
                true
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onForceRotateHighlight?.invoke()
                post { onNavigateRightToRotate?.invoke() }
                true
            }
            else -> false
        }
    }

    data class HorizontalHandlingPolicy(
        val deferShortPressUntilUp: Boolean = false
    )

    fun setupDpadKeyHandling(
        view: View,
        onShortHorizontal: (Int) -> Boolean,
        onLongHorizontal: ((Int) -> Boolean)? = null,
        onDown: (() -> Boolean)? = null,
        onUp: (() -> Boolean)? = null,
        horizontalPolicy: HorizontalHandlingPolicy = HorizontalHandlingPolicy()
    ) {
        var pendingHorizontalKey: Int? = null
        val horizontalPresses = RemotePressRegistry<Int>()

        fun isHorizontalKey(keyCode: Int): Boolean {
            return keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        }

        fun resetHorizontalState() {
            pendingHorizontalKey = null
            horizontalPresses.resetAll()
        }

        view.setOnKeyListener { _, keyCode, event ->
            if (!view.hasFocus()) {
                resetHorizontalState()
                return@setOnKeyListener false
            }
            val isHorizontal = isHorizontalKey(keyCode)

            when (event.action) {
                android.view.KeyEvent.ACTION_DOWN -> {
                    onControlsInteraction?.invoke()

                    if (isHorizontal) {
                        if (horizontalPolicy.deferShortPressUntilUp) {
                            if (event.repeatCount == 0 && pendingHorizontalKey != null && pendingHorizontalKey != keyCode) {
                                horizontalPresses.reset(pendingHorizontalKey!!)
                            }
                            pendingHorizontalKey = keyCode
                            horizontalPresses.onDown(
                                key = keyCode,
                                repeatCount = event.repeatCount,
                                isLongPress = event.isLongPress
                            ) {
                                onLongHorizontal?.invoke(keyCode) == true
                            }
                            return@setOnKeyListener true
                        }

                        if (event.repeatCount > 0 || event.isLongPress) {
                            horizontalPresses.onDown(
                                key = keyCode,
                                repeatCount = event.repeatCount,
                                isLongPress = event.isLongPress
                            ) {
                                onLongHorizontal?.invoke(keyCode) == true
                            }
                            return@setOnKeyListener true
                        }

                        horizontalPresses.onDown(
                            key = keyCode,
                            repeatCount = 0,
                            isLongPress = false
                        ) { false }
                        return@setOnKeyListener onShortHorizontal(keyCode)
                    }

                    return@setOnKeyListener when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> onDown?.invoke() ?: false
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> onUp?.invoke() ?: false
                        else -> false
                    }
                }

                android.view.KeyEvent.ACTION_UP -> {
                    if (!isHorizontal) {
                        return@setOnKeyListener false
                    }

                    if (!horizontalPolicy.deferShortPressUntilUp) {
                        horizontalPresses.reset(keyCode)
                        return@setOnKeyListener false
                    }

                    val pending = pendingHorizontalKey
                    if (pending == null || pending != keyCode) {
                        return@setOnKeyListener false
                    }

                    pendingHorizontalKey = null
                    return@setOnKeyListener horizontalPresses.onUpWithResult(keyCode) {
                        onShortHorizontal(keyCode)
                    }
                }

                else -> false
            }
        }
    }

    fun configureExoControl(
        view: View,
        enabled: Boolean = true,
        onClick: (() -> Unit)? = null,
        deferHorizontalShortPressUntilUp: Boolean = false,
        consumeUnmovedHorizontalKeys: Set<Int> = emptySet()
    ) {
        view.visibility = View.VISIBLE
        if (enabled) {
            view.enableControl()
        } else {
            view.disableControl()
        }
        if (onClick != null) {
            view.setOnClickListener { onClick() }
        } else {
            view.setOnClickListener(null)
        }
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        setupDpadKeyHandling(
            view = view,
            onShortHorizontal = { keyCode ->
                val moved = when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> moveWithinExo(view, toLeft = true)
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> moveWithinExo(view, toLeft = false)
                    else -> false
                }
                moved || keyCode in consumeUnmovedHorizontalKeys
            },
            onLongHorizontal = { keyCode -> navigateSideByLongPress(keyCode) },
            onDown = { moveDownToTimeBar() },
            onUp = { moveUpToOverlay() },
            horizontalPolicy = HorizontalHandlingPolicy(
                deferShortPressUntilUp = deferHorizontalShortPressUntilUp
            )
        )
    }

    fun configureExoControlById(
        controlId: String,
        enabled: Boolean = true,
        onClick: (() -> Unit)? = null,
        deferHorizontalShortPressUntilUp: Boolean = false,
        consumeUnmovedHorizontalKeys: Set<Int> = emptySet()
    ) {
        findControlView(controlId)?.let { view ->
            configureExoControl(
                view = view,
                enabled = enabled,
                onClick = onClick,
                deferHorizontalShortPressUntilUp = deferHorizontalShortPressUntilUp,
                consumeUnmovedHorizontalKeys = consumeUnmovedHorizontalKeys
            )
        }
    }

    configureExoControlById(
        controlId = "exo_prev",
        enabled = true,
        onClick = onRestartPlayback,
        consumeUnmovedHorizontalKeys = setOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
    )
    configureExoControlById(
        controlId = "exo_next",
        enabled = false,
        onClick = null,
        consumeUnmovedHorizontalKeys = setOf(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
    )

    listOf("exo_rew", "exo_rew_with_amount").forEach { controlId ->
        configureExoControlById(
            controlId = controlId,
            enabled = true,
            onClick = onSeekBack
        )
    }

    listOf("exo_ffwd", "exo_ffwd_with_amount").forEach { controlId ->
        val enabled = isArchivePlayback
        configureExoControlById(
            controlId = controlId,
            enabled = enabled,
            onClick = if (enabled) onSeekForward else null
        )
    }

    // Keep play/pause horizontal navigation immediate.
    configureExoControlById(
        controlId = "exo_pause",
        enabled = true,
        onClick = onPausePlayback
    )
    configureExoControlById(
        controlId = "exo_play",
        enabled = true,
        onClick = onResumePlayback
    )
    configureExoControlById(
        controlId = "exo_play_pause",
        enabled = true,
        onClick = {
            val playerInstance = player
            if (playerInstance?.isPlaying == true) {
                onPausePlayback()
            } else {
                onResumePlayback()
            }
        }
    )

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
        bar.isFocusable = true
        bar.isFocusableInTouchMode = false
        setupDpadKeyHandling(
            view = bar,
            onShortHorizontal = { false },
            onLongHorizontal = { keyCode -> navigateSideByLongPress(keyCode) },
            onUp = {
                // Move back to play/pause/exo controls
                focusOnControl(
                    "exo_play_pause",
                    "exo_play",
                    "exo_pause",
                    "exo_rew",
                    "exo_ffwd"
                )
                true
            }
        )
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

@Composable
private fun localizedPlaybackIssueMessage(issue: PlaybackIssue): String {
    val rawMessage = issue.message?.trim().orEmpty()
    val isSourceError = rawMessage.equals("source error", ignoreCase = true)

    val base = when (issue) {
        is PlaybackIssue.Suspended -> stringResource(R.string.playback_issue_suspended)
        is PlaybackIssue.TokenNotFound -> stringResource(R.string.playback_issue_token_not_found)
        is PlaybackIssue.NotFound -> stringResource(R.string.playback_issue_not_found)
        is PlaybackIssue.Forbidden -> stringResource(R.string.playback_issue_forbidden)
        is PlaybackIssue.HttpError -> {
            if (issue.code < 0 && isSourceError) {
                stringResource(R.string.playback_issue_source_error)
            } else {
                stringResource(R.string.playback_issue_http_error, issue.code)
            }
        }
        is PlaybackIssue.Network -> stringResource(R.string.playback_issue_network)
        is PlaybackIssue.Timeout -> stringResource(R.string.playback_issue_timeout)
        is PlaybackIssue.Unknown -> {
            if (isSourceError) {
                stringResource(R.string.playback_issue_source_error)
            } else {
                stringResource(R.string.playback_issue_unknown)
            }
        }
    }

    val showExtra = rawMessage.isNotBlank() &&
        !isSourceError &&
        !rawMessage.equals(base, ignoreCase = true)

    return if (showExtra) "$base: $rawMessage" else base
}
