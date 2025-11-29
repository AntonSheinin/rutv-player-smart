package com.rutv.ui.mobile.screens

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.annotation.SuppressLint
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
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
    val focusManager = rememberPlayerFocusManager(initial = PlayerFocusDestination.NONE, log = debugLogger)
    val coroutineScope = rememberCoroutineScope()
    var showControls by remember { mutableStateOf(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var lastControlsInteractionAt by remember { mutableStateOf(System.currentTimeMillis()) }
    val controllerVisibilityCallback by rememberUpdatedState<(Boolean) -> Unit> { visible ->
        showControls = visible
    }

    // Store focus requesters for custom controls (for ExoPlayer navigation)
    var leftColumnFocusRequesters by remember { mutableStateOf<List<FocusRequester>?>(null) }
    var rightColumnFocusRequesters by remember { mutableStateOf<List<FocusRequester>?>(null) }
    var lastFocusedPlaylistIndex by remember { mutableIntStateOf(uiState.currentChannelIndex.coerceAtLeast(0)) }
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

    val forceFavoritesHighlight: () -> Unit = {
        setFavoritesFocusHint?.invoke(true)
    }
    val forceRotateHighlight: () -> Unit = {
        setRotateFocusHint?.invoke(true)
    }

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
                    focusExoPlayerControls(true)
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.ruTvColors.darkBackground)
            .onPreviewKeyEvent { event ->
                // Fullscreen playback DPAD handling
                val isRemote = DeviceHelper.isRemoteInputActive()
                if (!isRemote || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                if (showControls) {
                    registerControlsInteraction()
                }

                val currentFocus = focusManager.currentDestination
                val panelsOpen = currentFocus != PlayerFocusDestination.NONE &&
                    currentFocus != PlayerFocusDestination.PLAYER_CONTROLS

                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (currentFocus == PlayerFocusDestination.NONE) {
                            showControls = true
                            registerControlsInteraction()
                            playerViewRef?.post { playerViewRef?.showController() }
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionLeft -> {
                        if (currentFocus == PlayerFocusDestination.NONE) {
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
                        if (currentFocus == PlayerFocusDestination.NONE) {
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
                        when (currentFocus) {
                            PlayerFocusDestination.PROGRAM_DETAILS -> {
                                actions.onCloseProgramDetails()
                                true
                            }
                            PlayerFocusDestination.EPG_PANEL -> {
                                actions.onCloseEpgPanel()
                                true
                            }
                            PlayerFocusDestination.PLAYLIST_PANEL -> {
                                actions.onClosePlaylist()
                                true
                            }
                            PlayerFocusDestination.PLAYER_CONTROLS -> {
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
                            onControlsInteraction = { registerControlsInteraction() },
                            onForceFavoritesHighlight = { setFavoritesFocusHint?.invoke(true) },
                            onForceRotateHighlight = { setRotateFocusHint?.invoke(true) }
                        )
                        lastControlsSignature = controlsSignature
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }


        // Custom Control Buttons Overlay (bottom) - synced with ExoPlayer controls
        // No animation - hide/show instantly together with ExoPlayer controls
        if (showControls) {
            CustomControlButtons(
                onPlaylistClick = actions.onTogglePlaylist,
                onFavoritesClick = actions.onToggleFavorites,
                onGoToChannelClick = actions.onGoToChannel,
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
                        // Reset flag after a short delay to allow focus to settle
                        coroutineScope.launch {
                            delay(100)
                            isNavigatingWithinPlayerControls = false
                        }
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
                        // Reset flag after a short delay to allow focus to settle
                        coroutineScope.launch {
                            delay(100)
                            isNavigatingWithinPlayerControls = false
                        }
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
                        isNavigatingWithinPlayerControls = true
                        setFavoritesFocusHint?.invoke(true)
                        // Use post for consistent timing
                        playerViewRef?.post {
                            customControlFocusCoordinator.requestFocus(
                                CustomControlFocusTarget.Favorites,
                                leftColumnFocusRequesters,
                                rightColumnFocusRequesters
                            )
                        }
                        // Reset flag after a short delay
                        coroutineScope.launch {
                            delay(100)
                            isNavigatingWithinPlayerControls = false
                        }
                    }
                    navigateToRotateCallback = {
                        // Navigate from ExoPlayer controls to custom buttons
                        // Don't check destination - allow navigation when controls are visible
                        registerControlsInteraction()
                        isNavigatingWithinPlayerControls = true
                        setRotateFocusHint?.invoke(true)
                        // Use post for consistent timing
                        playerViewRef?.post {
                            customControlFocusCoordinator.requestFocus(
                                CustomControlFocusTarget.Rotate,
                                leftColumnFocusRequesters,
                                rightColumnFocusRequesters
                            )
                        }
                        // Reset flag after a short delay
                        coroutineScope.launch {
                            delay(100)
                            isNavigatingWithinPlayerControls = false
                        }
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
                ChannelInfoOverlay(
                    channelNumber = uiState.currentChannelIndex + 1,
                    channel = channel,
                    currentProgram = uiState.currentProgram,
                    isArchivePlayback = uiState.isArchivePlayback,
                    isTimeshiftPlayback = uiState.isTimeshiftPlayback,
                    archiveProgram = uiState.archiveProgram,
                    onReturnToLive = actions.onReturnToLive,
                    onShowProgramInfo = actions.onShowProgramDetails,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(LayoutConstants.DefaultPadding)
                        .fillMaxWidth(0.6f)
                )
            }
        }

        val allChannels = uiState.filteredChannels
        val displayedChannels = uiState.visibleChannels

        // Focus management for panel transitions
        val focusPlaylistFromEpg: () -> Unit = {
            debugLogger("EPG->Playlist: Transferring focus")
            focusManager.requestEnter(PlayerFocusDestination.PLAYLIST_PANEL)

            // Focus the specific channel index
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
                focusManager.focusItem(PlayerFocusDestination.PLAYLIST_PANEL, resolvedIndex, false)
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
                focusManager = focusManager,
                onChannelFocused = { index ->
                    if (index >= 0) {
                        lastFocusedPlaylistIndex = index
                    }
                },
                onRequestEpgFocus = { focusManager.requestEnter(PlayerFocusDestination.EPG_PANEL) },
                modifier = Modifier.align(Alignment.CenterStart)
            )
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
        "exo_play_pause",
        "exo_play",
        "exo_pause",
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

    // Intercept DPAD at PlayerView level to keep arrow keys within controls and enable long-press escape
    setOnKeyListener { _, keyCode, event ->
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
        onControlsInteraction?.invoke()
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.repeatCount > 0 || event.isLongPress) {
                    onForceFavoritesHighlight?.invoke()
                    post { onNavigateLeftToFavorites?.invoke() }
                    return@setOnKeyListener true
                }
                false
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.repeatCount > 0 || event.isLongPress) {
                    onForceRotateHighlight?.invoke()
                    post { onNavigateRightToRotate?.invoke() }
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
            if (event.action == android.view.KeyEvent.ACTION_DOWN && hasFocus()) {
                onControlsInteraction?.invoke()
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.repeatCount > 0 || event.isLongPress) {
                            onForceFavoritesHighlight?.invoke()
                            post { onNavigateLeftToFavorites?.invoke() }
                            return@setOnKeyListener true
                        }
                        // Try to move within ExoPlayer controls, otherwise consume to prevent timebar
                        return@setOnKeyListener moveWithinExo(this, toLeft = true) || true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.repeatCount > 0 || event.isLongPress) {
                            onForceRotateHighlight?.invoke()
                            post { onNavigateRightToRotate?.invoke() }
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener moveWithinExo(this, toLeft = false)
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val timeBar = findControlView("exo_timebar") ?: findControlView("exo_progress")
                        if (timeBar?.isShown == true && timeBar.isFocusable) {
                            timeBar.requestFocus()
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener false
                    }
                    else -> return@setOnKeyListener false
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
            if (event.action == android.view.KeyEvent.ACTION_DOWN && hasFocus()) {
                onControlsInteraction?.invoke()
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.repeatCount > 0 || event.isLongPress) {
                            onForceRotateHighlight?.invoke()
                            post { onNavigateRightToRotate?.invoke() }
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener moveWithinExo(this, toLeft = false)
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.repeatCount > 0 || event.isLongPress) {
                            onForceFavoritesHighlight?.invoke()
                            post { onNavigateLeftToFavorites?.invoke() }
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener moveWithinExo(this, toLeft = true)
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val timeBar = findControlView("exo_timebar") ?: findControlView("exo_progress")
                        if (timeBar?.isShown == true && timeBar.isFocusable) {
                            timeBar.requestFocus()
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener false
                    }
                    else -> return@setOnKeyListener false
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
                if (event.action == android.view.KeyEvent.ACTION_DOWN && hasFocus()) {
                    onControlsInteraction?.invoke()
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (event.repeatCount > 0 || event.isLongPress) {
                                onForceFavoritesHighlight?.invoke()
                                post { onNavigateLeftToFavorites?.invoke() }
                                return@setOnKeyListener true
                            }
                            return@setOnKeyListener moveWithinExo(this, toLeft = true)
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (event.repeatCount > 0 || event.isLongPress) {
                                onForceRotateHighlight?.invoke()
                                post { onNavigateRightToRotate?.invoke() }
                                return@setOnKeyListener true
                            }
                            return@setOnKeyListener moveWithinExo(this, toLeft = false)
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            val timeBar = findControlView("exo_timebar") ?: findControlView("exo_progress")
                            if (timeBar?.isShown == true && timeBar.isFocusable) {
                                timeBar.requestFocus()
                                return@setOnKeyListener true
                            }
                            return@setOnKeyListener false
                        }
                        else -> return@setOnKeyListener false
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
            isFocusable = true
            isFocusableInTouchMode = false
            setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && hasFocus()) {
                    onControlsInteraction?.invoke()
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (event.repeatCount > 0 || event.isLongPress) {
                                onForceRotateHighlight?.invoke()
                                post { onNavigateRightToRotate?.invoke() }
                                return@setOnKeyListener true
                            }
                            return@setOnKeyListener moveWithinExo(this, toLeft = false)
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (event.repeatCount > 0 || event.isLongPress) {
                                onForceFavoritesHighlight?.invoke()
                                post { onNavigateLeftToFavorites?.invoke() }
                                return@setOnKeyListener true
                            }
                            return@setOnKeyListener moveWithinExo(this, toLeft = true)
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            val timeBar = findControlView("exo_timebar") ?: findControlView("exo_progress")
                            if (timeBar?.isShown == true && timeBar.isFocusable) {
                                timeBar.requestFocus()
                                return@setOnKeyListener true
                            }
                            return@setOnKeyListener false
                        }
                        else -> return@setOnKeyListener false
                    }
                }
                false
            }
        }
    }

    // Helper function to add long-press support to play/pause controls
    fun setupPlayPauseControl(view: View) {
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && view.hasFocus()) {
                onControlsInteraction?.invoke()
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.repeatCount > 0 || event.isLongPress) {
                            onForceFavoritesHighlight?.invoke()
                            post { onNavigateLeftToFavorites?.invoke() }
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener moveWithinExo(view, toLeft = true)
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.repeatCount > 0 || event.isLongPress) {
                            onForceRotateHighlight?.invoke()
                            post { onNavigateRightToRotate?.invoke() }
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener moveWithinExo(view, toLeft = false)
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val timeBar = findControlView("exo_timebar") ?: findControlView("exo_progress")
                        if (timeBar?.isShown == true && timeBar.isFocusable) {
                            timeBar.requestFocus()
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener false
                    }
                    else -> return@setOnKeyListener false
                }
            }
            false
        }
    }

    findControlView("exo_pause")?.apply {
        setOnClickListener { onPausePlayback() }
        isFocusable = true
        isFocusableInTouchMode = false
        setupPlayPauseControl(this)
    }
    findControlView("exo_play")?.apply {
        setOnClickListener { onResumePlayback() }
        isFocusable = true
        isFocusableInTouchMode = false
        setupPlayPauseControl(this)
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
        isFocusable = true
        isFocusableInTouchMode = false
        setupPlayPauseControl(this)
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
        bar.isFocusable = true
        bar.isFocusableInTouchMode = false
        bar.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                onControlsInteraction?.invoke()
                val isLeft = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                val isRight = keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                    // Move back to play/pause/exo controls
                    focusOnControl(
                        "exo_play_pause",
                        "exo_play",
                        "exo_pause",
                        "exo_rew",
                        "exo_ffwd"
                    )
                    return@setOnKeyListener true
                }
                if (isLeft || isRight) {
                    if (event.repeatCount > 0 || event.isLongPress) {
                        if (isLeft) {
                            onForceFavoritesHighlight?.invoke()
                            post { onNavigateLeftToFavorites?.invoke() }
                        } else {
                            onForceRotateHighlight?.invoke()
                            post { onNavigateRightToRotate?.invoke() }
                        }
                        return@setOnKeyListener true
                    }
                }
            }
            false
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
