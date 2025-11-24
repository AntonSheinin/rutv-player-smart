package com.rutv.ui.mobile.screens

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.rutv.ui.mobile.screens.rememberRemoteFocusCoordinator
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
    val customControlFocusCoordinator = rememberCustomControlFocusCoordinator()
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
    var setFavoritesFocusHint by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var setRotateFocusHint by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

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
                        setFavoritesFocusHint?.invoke(true)
                        customControlFocusCoordinator.requestFocus(
                            CustomControlFocusTarget.Favorites,
                            leftColumnFocusRequesters,
                            rightColumnFocusRequesters
                        )
                    }
                    navigateToRotateCallback = {
                        registerControlsInteraction()
                        Timber.d("CustomControlFocus | navigateToRotateCallback triggered")
                        setRotateFocusHint?.invoke(true)
                        customControlFocusCoordinator.requestFocus(
                            CustomControlFocusTarget.Rotate,
                            leftColumnFocusRequesters,
                            rightColumnFocusRequesters
                        )
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
        DeviceHelper.updateLastInputMethod(event)
        onControlsInteraction?.invoke()
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.repeatCount > 0 || event.isLongPress) {
                    onNavigateLeftToFavorites?.invoke()
                    return@setOnKeyListener true
                }
                false
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.repeatCount > 0 || event.isLongPress) {
                    onNavigateRightToRotate?.invoke()
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
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && hasFocus()) {
                    if (event.repeatCount > 0 || event.isLongPress) {
                        onNavigateLeftToFavorites?.invoke()
                        return@setOnKeyListener true
                    }
                    if (moveWithinExo(this, toLeft = true)) return@setOnKeyListener true
                    return@setOnKeyListener true // consume to prevent timebar
                } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && hasFocus()) {
                    if (moveWithinExo(this, toLeft = false)) return@setOnKeyListener true
                    return@setOnKeyListener true
                } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && hasFocus()) {
                    val timeBar = findControlView("exo_timebar") ?: findControlView("exo_progress")
                    if (timeBar?.isShown == true && timeBar.isFocusable) {
                        timeBar.requestFocus()
                        return@setOnKeyListener true
                    }
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
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && hasFocus()) {
                    if (event.repeatCount > 0 || event.isLongPress) {
                        post { onNavigateRightToRotate?.invoke() }
                        return@setOnKeyListener true
                    }
                    if (moveWithinExo(this, toLeft = false)) return@setOnKeyListener true
                    return@setOnKeyListener true
                } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && hasFocus()) {
                    if (moveWithinExo(this, toLeft = true)) return@setOnKeyListener true
                    return@setOnKeyListener true
                } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && hasFocus()) {
                    val timeBar = findControlView("exo_timebar") ?: findControlView("exo_progress")
                    if (timeBar?.isShown == true && timeBar.isFocusable) {
                        timeBar.requestFocus()
                        return@setOnKeyListener true
                    }
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
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && hasFocus()) {
                        if (event.repeatCount > 0 || event.isLongPress) {
                            Timber.d("CustomControlFocus | rewind control long-press LEFT -> Favorites")
                            post { onNavigateLeftToFavorites?.invoke() }
                            return@setOnKeyListener true
                        }
                        if (moveWithinExo(this, toLeft = true)) return@setOnKeyListener true
                        return@setOnKeyListener true
                    } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && hasFocus()) {
                        if (moveWithinExo(this, toLeft = false)) return@setOnKeyListener true
                        return@setOnKeyListener true
                    } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && hasFocus()) {
                        val timeBar = findControlView("exo_timebar") ?: findControlView("exo_progress")
                        if (timeBar?.isShown == true && timeBar.isFocusable) {
                            timeBar.requestFocus()
                            return@setOnKeyListener true
                        }
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
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && hasFocus()) {
                        if (event.repeatCount > 0 || event.isLongPress) {
                            Timber.d("CustomControlFocus | ffwd control long-press RIGHT -> Rotate")
                            post { onNavigateRightToRotate?.invoke() }
                            return@setOnKeyListener true
                        }
                        if (moveWithinExo(this, toLeft = false)) return@setOnKeyListener true
                        return@setOnKeyListener true
                    } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && hasFocus()) {
                        if (moveWithinExo(this, toLeft = true)) return@setOnKeyListener true
                        return@setOnKeyListener true
                    } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && hasFocus()) {
                        val timeBar = findControlView("exo_timebar") ?: findControlView("exo_progress")
                        if (timeBar?.isShown == true && timeBar.isFocusable) {
                            timeBar.requestFocus()
                            return@setOnKeyListener true
                        }
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
        bar.isFocusable = true
        bar.isFocusableInTouchMode = false
        bar.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                DeviceHelper.updateLastInputMethod(event)
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
                        if (isLeft) onNavigateLeftToFavorites?.invoke() else onNavigateRightToRotate?.invoke()
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







