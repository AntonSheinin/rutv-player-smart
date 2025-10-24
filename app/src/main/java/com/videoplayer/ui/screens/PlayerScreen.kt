package com.videoplayer.ui.screens

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import androidx.annotation.StringRes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as Media3UiR
import com.videoplayer.R
import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.presentation.main.MainViewState
import com.videoplayer.ui.components.ChannelListItem
import com.videoplayer.ui.components.EpgDateDelimiter
import com.videoplayer.ui.components.EpgProgramItem
import com.videoplayer.ui.theme.ruTvColors
import com.videoplayer.util.Constants
import java.text.SimpleDateFormat
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
    onToggleRotation: () -> Unit,
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.ruTvColors.darkBackground)
    ) {
        // ExoPlayer View
        player?.let {
            @Suppress("DiscouragedApi")
            AndroidView(
                factory = { context ->
                    PlayerView(context).also { playerView ->
                        playerViewRef = playerView
                        playerView.player = it
                        playerView.layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        playerView.useController = true
                        playerView.controllerShowTimeoutMs = Constants.CONTROLLER_AUTO_HIDE_TIMEOUT_MS // Auto-hide controls
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
                        } catch (e: Exception) {
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
                        onRestartPlayback = onRestartPlayback,
                        onSeekBack = onSeekBack,
                        onSeekForward = onSeekForward,
                        onPausePlayback = onPausePlayback,
                        onResumePlayback = onResumePlayback
                    )
                    playerView.hideSettingsControls()

                    // Rotate the video surface, not the entire player view (including controls)
                    // Find the video surface view and rotate it
                    val videoSurfaceView = playerView.videoSurfaceView
                    if (videoSurfaceView is View) {
                        videoSurfaceView.rotation = viewState.videoRotation
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
            CustomControlButtons(
                onPlaylistClick = onTogglePlaylist,
                onFavoritesClick = onToggleFavorites,
                onGoToChannelClick = onGoToChannel,
                onAspectRatioClick = onCycleAspectRatio,
                onRotationClick = onToggleRotation,
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
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Playlist Panel
        if (viewState.showPlaylist) {
            PlaylistPanel(
                channels = viewState.filteredChannels,
                playlistTitle = viewState.playlistTitle,
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
        if (viewState.showEpgPanel && viewState.epgPrograms.isNotEmpty()) {
            EpgPanel(
                programs = viewState.epgPrograms,
                onProgramClick = onShowProgramDetails,
                onPlayArchive = onPlayArchiveProgram,
                isArchivePlayback = viewState.isArchivePlayback,
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
            val hasNext = prompt.nextProgram != null
            val message = if (hasNext) {
                stringResource(R.string.archive_prompt_message, prompt.nextProgram.title)
            } else {
                stringResource(R.string.archive_prompt_message_no_next)
            }

            AlertDialog(
                onDismissRequest = onArchivePromptBackToLive,
                title = { Text(stringResource(R.string.archive_prompt_title)) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(
                        onClick = onArchivePromptContinue,
                        enabled = hasNext
                    ) {
                        Text(text = stringResource(R.string.archive_prompt_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onArchivePromptBackToLive) {
                        Text(text = stringResource(R.string.player_return_to_live))
                    }
                }
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
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.8f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = stringResource(R.string.channel_info_format, channelNumber, channel.title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.ruTvColors.textPrimary
            )
            if (isArchivePlayback) {
                archiveProgram?.let { program ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.player_archive_label, program.title.truncateForOverlay()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.ruTvColors.gold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onReturnToLive,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.ruTvColors.gold,
                        contentColor = MaterialTheme.ruTvColors.darkBackground)
                ) {
                    Text(text = stringResource(R.string.player_return_to_live))
                }
            } else {
                currentProgram?.let { program ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = program.title.truncateForOverlay(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.ruTvColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isTimeshiftPlayback) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onReturnToLive,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.ruTvColors.gold,
                            contentColor = MaterialTheme.ruTvColors.darkBackground
                        )
                    ) {
                        Text(text = stringResource(R.string.player_return_to_live))
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
    playlistTitle: String,
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

    // Auto-scroll to current channel when panel opens (center it in viewport)
    LaunchedEffect(currentChannelIndex, channels.size) {
        if (currentChannelIndex >= 0 && currentChannelIndex < channels.size) {
            // Jump instantly, then apply a small animated offset for a snappier feel
            listState.scrollToItem(currentChannelIndex)
            listState.animateScrollToItem(currentChannelIndex, scrollOffset = -160)
        }
    }

    Card(
        modifier = modifier
            .fillMaxHeight()
            .width(400.dp)
            .padding(16.dp),
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
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = playlistTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.ruTvColors.gold
                )
                IconButton(onClick = onClose) {
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
                        ChannelListItem(
                            channel = channel,
                            channelNumber = index + 1,
                            isPlaying = index == currentChannelIndex,
                            isEpgOpen = index == epgOpenIndex,
                            currentProgram = currentProgramsMap[channel.tvgId],
                            onChannelClick = { onChannelClick(index) },
                            onFavoriteClick = { onFavoriteClick(channel.url) },
                            onShowPrograms = { onShowPrograms(channel.tvgId) },
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
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
        }
    }
}

@UnstableApi
@Composable
private fun EpgPanel(
    programs: List<EpgProgram>,
    onProgramClick: (EpgProgram) -> Unit,
    onPlayArchive: (EpgProgram) -> Unit,
    isArchivePlayback: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
    val currentTime = System.currentTimeMillis()

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
        programs.forEach { program ->
            val programDate = dateFormat.format(Date(program.startTimeMillis))
            if (programDate != lastDate) {
                itemsList.add("date_$programDate" to programDate)
                lastDate = programDate
            }
            itemsList.add("program_${program.startTime}_${program.title}" to program)
        }
        itemsList
    }

    // Calculate actual item index including delimiters
    val scrollToIndex = remember(currentProgramIndex, items) {
        if (currentProgramIndex >= 0) {
            var delimitersBefore = 0
            var lastDate = ""
            for (i in 0 until currentProgramIndex) {
                val programDate = dateFormat.format(Date(programs[i].startTimeMillis))
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

    // Auto-scroll to current program when panel opens (center it in viewport)
    LaunchedEffect(scrollToIndex, items.size) {
        if (scrollToIndex >= 0 && scrollToIndex < items.size) {
            // Jump close to the current program immediately, then apply a small offset animation.
            listState.scrollToItem(scrollToIndex)
            listState.animateScrollToItem(
                scrollToIndex,
                scrollOffset = -200
            )
        }
    }

    Card(
        modifier = modifier
            .fillMaxHeight()
            .width(400.dp)
            .padding(16.dp),
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
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.epg_panel_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.ruTvColors.gold
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
                                EpgDateDelimiter(date = data)
                            }
                            is EpgProgram -> {
                                val programIndex = programs.indexOf(data)
                                val canPlayArchive = data.startTimeMillis < currentTime && !isArchivePlayback
                                EpgProgramItem(
                                    program = data,
                                    isCurrent = programIndex == currentProgramIndex,
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
    val timeFormat = SimpleDateFormat("HH:mm, EEEE, MMMM d", Locale.getDefault())
    val startTimeFormatted = program.startTimeMillis.takeIf { it > 0L }?.let {
        timeFormat.format(Date(it))
    } ?: "--"

    Card(
        modifier = modifier
            .fillMaxHeight(0.8f)
            .width(500.dp)
            .padding(16.dp),
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
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.program_details_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.ruTvColors.gold
                )
                IconButton(onClick = onClose) {
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
                                    text = "Description",
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
            .width(400.dp)
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
                text = "Debug Log",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.ruTvColors.gold,
                modifier = Modifier.padding(8.dp)
            )
            HorizontalDivider(color = MaterialTheme.ruTvColors.textDisabled)

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
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

/**
 * Custom control buttons overlay
 */
@Composable
private fun CustomControlButtons(
    onPlaylistClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onGoToChannelClick: () -> Unit,
    onAspectRatioClick: () -> Unit,
    onRotationClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .pointerInteropFilter { false }
        ) {
            ControlColumn(
                modifier = Modifier.align(Alignment.CenterStart),
                buttons = listOf(
                    ControlButtonData(
                        icon = Icons.AutoMirrored.Filled.List,
                        description = R.string.cd_playlist_button,
                        onClick = onPlaylistClick
                    ),
                    ControlButtonData(
                        icon = Icons.Default.Star,
                        description = R.string.cd_favorites_button,
                        onClick = onFavoritesClick
                    ),
                    ControlButtonData(
                        icon = Icons.Default.Numbers,
                        description = R.string.cd_go_to_channel_button,
                        onClick = onGoToChannelClick
                    )
                )
            )

            ControlColumn(
                modifier = Modifier.align(Alignment.CenterEnd),
                buttons = listOf(
                    ControlButtonData(
                        icon = Icons.Default.AspectRatio,
                        description = R.string.cd_aspect_ratio_button,
                        onClick = onAspectRatioClick
                    ),
                    ControlButtonData(
                        icon = Icons.Default.ScreenRotation,
                        description = R.string.cd_orientation_button,
                        onClick = onRotationClick
                    ),
                    ControlButtonData(
                        icon = Icons.Default.Settings,
                        description = R.string.cd_settings_button,
                        onClick = onSettingsClick
                    )
                )
            )
        }
    }
}

private data class ControlButtonData(
    val icon: ImageVector,
    @StringRes val description: Int,
    val onClick: () -> Unit
)

@Composable
private fun ControlColumn(
    modifier: Modifier,
    buttons: List<ControlButtonData>
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.55f),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)
        ) {
            buttons.forEach { button ->
                IconButton(
                    onClick = button.onClick,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = button.icon,
                        contentDescription = stringResource(button.description),
                        tint = MaterialTheme.ruTvColors.gold,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

private const val MEDIA3_UI_PACKAGE = "androidx.media3.ui"
private const val DISABLED_CONTROL_ALPHA = 0.4f
private const val MAX_PROGRAM_TITLE_CHARS = 48

private fun View.enableControl() {
    alpha = 1f
    isEnabled = true
}

private fun View.disableControl() {
    alpha = DISABLED_CONTROL_ALPHA
    isEnabled = false
}

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
}
