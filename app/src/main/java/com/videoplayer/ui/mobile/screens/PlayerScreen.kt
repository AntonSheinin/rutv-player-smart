package com.videoplayer.ui.mobile.screens

import android.view.LayoutInflater
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.input.key.type
import com.videoplayer.util.DeviceHelper
import com.videoplayer.ui.shared.components.focusIndicatorModifier
import com.videoplayer.ui.shared.components.RemoteDialog
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as Media3UiR
import com.videoplayer.R
import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.presentation.main.MainViewState
import com.videoplayer.ui.mobile.components.ChannelListItem
import com.videoplayer.ui.mobile.components.EpgDateDelimiter
import com.videoplayer.ui.mobile.components.EpgProgramItem
import com.videoplayer.ui.shared.components.ArchivePromptDialog
import com.videoplayer.ui.shared.components.EpgNotificationToast
import com.videoplayer.ui.shared.components.CustomControlButtons
import com.videoplayer.ui.theme.ruTvColors
import com.videoplayer.ui.shared.presentation.TimeFormatter
import com.videoplayer.ui.shared.presentation.LayoutConstants
import com.videoplayer.util.PlayerConstants
import android.annotation.SuppressLint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.min
import java.util.*

/**
 * Main Player Screen with Compose UI
 */
@UnstableApi
@Composable
fun PlayerScreen(
    viewState: MainViewState,
    player: ExoPlayer?,
    onPlayChannel: (Int) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onShowEpgForChannel: (String) -> Unit,
    onTogglePlaylist: () -> Unit,
    onToggleFavorites: () -> Unit,
    onClosePlaylist: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onOpenSettings: () -> Unit,
    onGoToChannel: () -> Unit,
    onShowProgramDetails: (EpgProgram) -> Unit,
    onPlayArchiveProgram: (EpgProgram) -> Unit,
    onReturnToLive: () -> Unit,
    onRestartPlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPausePlayback: () -> Unit,
    onResumePlayback: () -> Unit,
    onArchivePromptContinue: () -> Unit,
    onArchivePromptBackToLive: () -> Unit,
    onCloseProgramDetails: () -> Unit,
    onLoadMoreEpgPast: () -> Unit,
    onLoadMoreEpgFuture: () -> Unit,
    epgNotificationMessage: String?,
    onClearEpgNotification: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showControls by remember { mutableStateOf(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

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

    LaunchedEffect(showControls, playerViewRef) {
       if (!showControls) return@LaunchedEffect
       val playerView = playerViewRef ?: return@LaunchedEffect
        val timeoutMs = PlayerConstants.CONTROLLER_AUTO_HIDE_TIMEOUT_MS.toLong()
        delay(timeoutMs)
        if (showControls) {
            playerView.hideController()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.ruTvColors.darkBackground)
    ) {
        // EPG Notification
        EpgNotificationToast(
            message = epgNotificationMessage,
            onDismiss = onClearEpgNotification,
            modifier = Modifier
        )

        // ExoPlayer View
        player?.let {
            @Suppress("DiscouragedApi")
            AndroidView(
                factory = { context ->
                    (LayoutInflater.from(context)
                        .inflate(R.layout.player_view_texture, null, false) as PlayerView).also { playerView ->
                        playerViewRef = playerView
                        playerView.layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        playerView.player = it
                        playerView.useController = true
                        playerView.controllerShowTimeoutMs = PlayerConstants.CONTROLLER_AUTO_HIDE_TIMEOUT_MS // Auto-hide controls
                        playerView.controllerHideOnTouch = true
                        playerView.resizeMode = viewState.currentResizeMode
                        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        // Hide shuffle, subtitle, and settings buttons (keep prev/next)
                        playerView.setShowShuffleButton(false)
                        playerView.setShowSubtitleButton(false)
                        // Try to hide settings button if method exists
                        try {
                            playerView::class.java.getMethod("setShowSettingsButton", Boolean::class.javaPrimitiveType)
                                .invoke(playerView, false)
                        } catch (_: Exception) {
                            // Method doesn't exist in this version, ignore
                        }
                        playerView.setShowPreviousButton(true)
                        playerView.setShowNextButton(true)
                        playerView.setShowRewindButton(true)
                        playerView.setShowFastForwardButton(true)
                        playerView.hideSettingsControls()
                        playerView.post { playerView.hideSettingsControls() }

                        // Listen for controller visibility changes
                        playerView.setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                // Update custom controls when ExoPlayer controls change visibility
                                showControls = (visibility == View.VISIBLE)
                            }
                        )
                    }
                },
                update = { playerView ->
                    playerViewRef = playerView
                    playerView.player = it
                    playerView.resizeMode = viewState.currentResizeMode
                    playerView.applyControlCustomizations(
                        isArchivePlayback = viewState.isArchivePlayback,
                        currentProgram = if (viewState.isArchivePlayback) viewState.archiveProgram else viewState.currentProgram,
                        onRestartPlayback = onRestartPlayback,
                        onSeekBack = onSeekBack,
                        onSeekForward = onSeekForward,
                        onPausePlayback = onPausePlayback,
                        onResumePlayback = onResumePlayback
                    )
                    playerView.hideSettingsControls()
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
            CustomControlButtons(
                onPlaylistClick = onTogglePlaylist,
                onFavoritesClick = onToggleFavorites,
                onGoToChannelClick = onGoToChannel,
                onAspectRatioClick = onCycleAspectRatio,
                onSettingsClick = onOpenSettings,
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
            viewState.currentChannel?.let { channel ->
                ChannelInfoOverlay(
                    channelNumber = viewState.currentChannelIndex + 1,
                    channel = channel,
                    currentProgram = viewState.currentProgram,
                    isArchivePlayback = viewState.isArchivePlayback,
                    isTimeshiftPlayback = viewState.isTimeshiftPlayback,
                    archiveProgram = viewState.archiveProgram,
                    onReturnToLive = onReturnToLive,
                    onShowProgramInfo = onShowProgramDetails,
                    modifier = Modifier.padding(LayoutConstants.DefaultPadding)
                )
            }
        }

        // Playlist Panel
        if (viewState.showPlaylist) {
            PlaylistPanel(
                channels = viewState.filteredChannels,
                playlistTitleResId = viewState.playlistTitleResId,
                currentChannelIndex = viewState.currentChannelIndex,
                epgOpenIndex = if (viewState.showEpgPanel) {
                    // Find the index of the channel whose EPG is open
                    viewState.filteredChannels.indexOfFirst { it.tvgId == viewState.epgChannelTvgId }
                } else {
                    -1
                },
                currentProgramsMap = viewState.currentProgramsMap,
                onChannelClick = onPlayChannel,
                onFavoriteClick = onToggleFavorite,
                onShowPrograms = onShowEpgForChannel,
                onClose = onClosePlaylist,
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }

        // EPG Panel
        val epgChannel = remember(viewState.epgChannelTvgId, viewState.channels, viewState.currentChannel) {
            viewState.channels.firstOrNull { it.tvgId == viewState.epgChannelTvgId }
                ?: viewState.currentChannel
        }
        if (viewState.showEpgPanel && viewState.epgPrograms.isNotEmpty()) {
            EpgPanel(
                programs = viewState.epgPrograms,
                channel = epgChannel,
                onProgramClick = onShowProgramDetails,
                onPlayArchive = onPlayArchiveProgram,
            isArchivePlayback = viewState.isArchivePlayback,
            onLoadMorePast = onLoadMoreEpgPast,
            onLoadMoreFuture = onLoadMoreEpgFuture,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        // Program Details Panel
        viewState.selectedProgramDetails?.let { program ->
            ProgramDetailsPanel(
                program = program,
                onClose = onCloseProgramDetails,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Debug Log Panel
        if (viewState.showDebugLog && viewState.debugMessages.isNotEmpty()) {
            DebugLogPanel(
                messages = viewState.debugMessages.takeLast(100).map { it.message },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )
        }

        viewState.archivePrompt?.let { prompt ->
            ArchivePromptDialog(
                prompt = prompt,
                onContinue = onArchivePromptContinue,
                onBackToLive = onArchivePromptBackToLive
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
    epgOpenIndex: Int,
    currentProgramsMap: Map<String, EpgProgram?>,
    onChannelClick: (Int) -> Unit,
    onFavoriteClick: (String) -> Unit,
    onShowPrograms: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    val isRemoteMode = DeviceHelper.isRemoteInputActive()

    // Create focus requesters for each channel item
    val focusRequesters = remember(channels.size) {
        List(channels.size) { FocusRequester() }
    }

    // Focus requesters for header buttons
    val searchButtonFocus = remember { FocusRequester() }
    val closeButtonFocus = remember { FocusRequester() }

    // Auto-scroll to current channel when panel opens (center it in viewport)
    LaunchedEffect(currentChannelIndex, channels.size) {
        if (currentChannelIndex >= 0 && currentChannelIndex < channels.size) {
            // Jump instantly, then apply a small animated offset for a snappier feel
            listState.scrollToItem(currentChannelIndex)
            listState.animateScrollToItem(currentChannelIndex, scrollOffset = -160)

            // Request focus on current channel in remote mode
            if (isRemoteMode) {
                focusRequesters[currentChannelIndex].requestFocus()
            }
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
                        modifier = Modifier
                            .size(40.dp)
                            .focusable(enabled = isRemoteMode)
                            .focusRequester(searchButtonFocus)
                            .then(focusIndicatorModifier(isFocused = false))
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionCenter, Key.Enter -> {
                                            showSearchDialog = true
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 0.dp, top = 4.dp, end = 12.dp, bottom = 4.dp) // Add end padding for scrollbar
                ) {
                    itemsIndexed(
                        items = channels,
                        key = { _, channel -> channel.url }
                    ) { index, channel ->
                        var isItemFocused by remember { mutableStateOf(false) }

                        // Auto-scroll when item gets focus
                        LaunchedEffect(isItemFocused) {
                            if (isItemFocused && isRemoteMode) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(index, scrollOffset = -160)
                                }
                            }
                        }

                        ChannelListItem(
                            channel = channel,
                            channelNumber = index + 1,
                            isPlaying = index == currentChannelIndex,
                            isEpgOpen = index == epgOpenIndex,
                            currentProgram = currentProgramsMap[channel.tvgId],
                            onChannelClick = { onChannelClick(index) },
                            onFavoriteClick = { onFavoriteClick(channel.url) },
                            onShowPrograms = { onShowPrograms(channel.tvgId) },
                            focusRequester = focusRequesters[index],
                            modifier = Modifier
                                .padding(vertical = 4.dp, horizontal = 8.dp)
                                .onFocusChanged { isItemFocused = it.isFocused }
                        )
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

            // Search Dialog
            if (showSearchDialog) {
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
                            modifier = Modifier.fillMaxWidth(),
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
                        TextButton(onClick = {
                            if (searchText.isNotBlank()) {
                                val searchLower = searchText.lowercase()
                                val matchingIndex = channels.indexOfFirst { channel ->
                                    channel.title.lowercase().contains(searchLower)
                                }
                                if (matchingIndex >= 0) {
                                    coroutineScope.launch {
                                        // Use scrollToItem for instant jump, then quick animate for smooth positioning
                                        listState.scrollToItem(matchingIndex)
                                        listState.animateScrollToItem(matchingIndex, scrollOffset = -160)
                                    }
                                }
                                showSearchDialog = false
                                searchText = ""
                            }
                        }) {
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
    onLoadMorePast: () -> Unit,
    onLoadMoreFuture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val currentTime = System.currentTimeMillis()
    val isRemoteMode = DeviceHelper.isRemoteInputActive()

    // Create focus requesters for each program item
    val focusRequesters = remember(programs.size) {
        List(programs.size) { FocusRequester() }
    }

    // Find current program index in original list
    val currentProgramIndex = programs.indexOfFirst { program ->
        val start = program.startTimeMillis
        val end = program.stopTimeMillis
        start > 0L && end > 0L && currentTime in start..end
    }

    // Build items list with date delimiters to calculate correct scroll position
    val items = remember(programs) {
        val itemsList = mutableListOf<Pair<String, Any>>() // Pair of key to item (delimiter or program)
        var lastDate = ""
        programs.forEachIndexed { index, program ->
            val programDate = TimeFormatter.formatEpgDate(Date(program.startTimeMillis))
            if (programDate != lastDate) {
                itemsList.add("date_$programDate" to programDate)
                lastDate = programDate
            }
            val baseKey = when {
                program.id.isNotBlank() -> program.id
                else -> "${program.startTimeMillis}_${program.stopTimeMillis}_${program.title}_$index"
            }
            itemsList.add("program_$baseKey" to program)
        }
        itemsList
    }

    // Calculate actual item index including delimiters
    val scrollToIndex = remember(currentProgramIndex, items) {
        if (currentProgramIndex >= 0) {
            var delimitersBefore = 0
            var lastDate = ""
            for (i in 0 until currentProgramIndex) {
                val programDate = TimeFormatter.formatEpgDate(Date(programs[i].startTimeMillis))
                if (programDate != lastDate) {
                    delimitersBefore++
                    lastDate = programDate
                }
            }
            currentProgramIndex + delimitersBefore
        } else {
            -1
        }
    }

    // Auto-scroll only once on first open
    var didInitialScroll by remember { mutableStateOf(false) }
    LaunchedEffect(scrollToIndex, items.size) {
        if (!didInitialScroll && scrollToIndex >= 0 && scrollToIndex < items.size) {
            listState.scrollToItem(scrollToIndex)
            listState.animateScrollToItem(scrollToIndex, scrollOffset = -200)
            didInitialScroll = true

            // Request focus on current program in remote mode
            if (isRemoteMode && currentProgramIndex >= 0 && currentProgramIndex < focusRequesters.size) {
                focusRequesters[currentProgramIndex].requestFocus()
            }
        }
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, top = 4.dp, end = 16.dp, bottom = 4.dp) // Add end padding for scrollbar
                ) {
                    items(items.size, key = { items[it].first }) { index ->
                        val item = items[index]
                        when (val data = item.second) {
                            is String -> {
                                // Date delimiters are not focusable - they're skipped automatically
                                EpgDateDelimiter(date = data)
                            }
                            is EpgProgram -> {
                                val programIndex = programs.indexOf(data)
                                val isPast = data.stopTimeMillis > 0 && data.stopTimeMillis <= currentTime
                                val catchupWindowMillis = channel
                                    ?.takeIf { it.supportsCatchup() }
                                    ?.let { java.util.concurrent.TimeUnit.DAYS.toMillis(it.catchupDays.toLong()) }
                                val isArchiveCandidate = catchupWindowMillis != null &&
                                    isPast &&
                                    data.startTimeMillis > 0 &&
                                    currentTime - data.startTimeMillis <= catchupWindowMillis
                                val canPlayArchive = isArchiveCandidate && !isArchivePlayback

                                var isItemFocused by remember { mutableStateOf(false) }

                                // Auto-scroll when item gets focus
                                LaunchedEffect(isItemFocused) {
                                    if (isItemFocused && isRemoteMode) {
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(index, scrollOffset = -200)
                                        }

                                        // Auto-load more EPG when near edges
                                        val visibleItemCount = listState.layoutInfo.visibleItemsInfo.size
                                        val firstVisible = listState.firstVisibleItemIndex
                                        val lastVisible = firstVisible + visibleItemCount

                                        if (index <= firstVisible + 3 && programIndex <= 2) {
                                            onLoadMorePast()
                                        }
                                        if (index >= lastVisible - 3 && programIndex >= programs.size - 3) {
                                            onLoadMoreFuture()
                                        }
                                    }
                                }

                                EpgProgramItem(
                                    program = data,
                                    isCurrent = programIndex == currentProgramIndex,
                                    isPast = isPast,
                                    showArchiveIndicator = isArchiveCandidate,
                                    onClick = { onProgramClick(data) },
                                    onPlayArchive = if (canPlayArchive) { { onPlayArchive(data) } } else null,
                                    focusRequester = if (programIndex >= 0 && programIndex < focusRequesters.size) {
                                        focusRequesters[programIndex]
                                    } else null,
                                    modifier = Modifier.onFocusChanged { isItemFocused = it.isFocused }
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

    // Request focus on close button when panel opens in remote mode
    LaunchedEffect(isRemoteMode) {
        if (isRemoteMode) {
            closeButtonFocus.requestFocus()
        }
    }

    val startTimeFormatted = program.startTimeMillis.takeIf { it > 0L }?.let {
        TimeFormatter.formatProgramDateTime(Date(it))
    } ?: stringResource(R.string.time_placeholder)

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
                        .focusable(enabled = isRemoteMode)
                        .focusRequester(closeButtonFocus)
                        .then(focusIndicatorModifier(isFocused = false))
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter, Key.Back -> {
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

            // Scrollable content with scrollbar
            Box(modifier = Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize(),
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


private fun PlayerView.applyControlCustomizations(
    isArchivePlayback: Boolean,
    currentProgram: EpgProgram?,
    onRestartPlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPausePlayback: () -> Unit,
    onResumePlayback: () -> Unit
) {
    setShowPreviousButton(true)
    setShowNextButton(true)
    setShowRewindButton(true)
    setShowFastForwardButton(true)

    findControlView("exo_prev")?.apply {
        visibility = View.VISIBLE
        enableControl()
        setOnClickListener { onRestartPlayback() }
    }

    findControlView("exo_next")?.apply {
        visibility = View.VISIBLE
        disableControl()
        setOnClickListener(null)
    }

    listOf("exo_rew", "exo_rew_with_amount").forEach { controlId ->
        findControlView(controlId)?.apply {
            visibility = View.VISIBLE
            enableControl()
            setOnClickListener { onSeekBack() }
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
        }
    }

    findControlView("exo_pause")?.setOnClickListener { onPausePlayback() }
    findControlView("exo_play")?.setOnClickListener { onResumePlayback() }
    findControlView("exo_play_pause")?.setOnClickListener {
        val playerInstance = player
        if (playerInstance?.isPlaying == true) {
            onPausePlayback()
        } else {
            onResumePlayback()
        }
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

private fun View.setVerticalOffsetDp(offsetDp: Float) {
    translationY = offsetDp * resources.displayMetrics.density
}






