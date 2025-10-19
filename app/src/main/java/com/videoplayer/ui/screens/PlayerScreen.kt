package com.videoplayer.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
    onCloseProgramDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showControls by remember { mutableStateOf(false) }

    // Show controls initially if player is loaded (for first-time users)
    LaunchedEffect(player) {
        if (player != null && !showControls) {
            showControls = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.ruTvColors.darkBackground)
    ) {
        // ExoPlayer View
        player?.let {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = it
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        useController = true
                        controllerShowTimeoutMs = Constants.CONTROLLER_AUTO_HIDE_TIMEOUT_MS // Auto-hide controls
                        controllerHideOnTouch = false // We'll handle tap manually
                        resizeMode = viewState.currentResizeMode
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        // Hide shuffle, subtitle, and settings buttons (keep prev/next)
                        setShowShuffleButton(false)
                        setShowSubtitleButton(false)
                        // Try to hide settings button if method exists
                        try {
                            this::class.java.getMethod("setShowSettingsButton", Boolean::class.javaPrimitiveType)
                                .invoke(this, false)
                        } catch (e: Exception) {
                            // Method doesn't exist in this version, ignore
                        }

                        // Wire prev/next buttons to channel switching
                        setShowPreviousButton(true)
                        setShowNextButton(true)

                        // Set custom forward/rewind listener to switch channels
                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_prev)?.setOnClickListener {
                            // Switch to previous channel in full channel list
                            val currentIndex = viewState.currentChannelIndex
                            if (currentIndex > 0) {
                                onPlayChannel(currentIndex - 1)
                            }
                        }

                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_next)?.setOnClickListener {
                            // Switch to next channel in full channel list
                            val currentIndex = viewState.currentChannelIndex
                            val maxIndex = viewState.channels.size - 1
                            if (currentIndex < maxIndex) {
                                onPlayChannel(currentIndex + 1)
                            }
                        }

                        // Listen for controller visibility changes
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                // Show custom controls when ExoPlayer controls are visible
                                showControls = (visibility == android.view.View.VISIBLE)
                            }
                        )
                    }
                },
                update = { playerView ->
                    playerView.player = it
                    playerView.resizeMode = viewState.currentResizeMode

                    // Rotate the video surface, not the entire player view (including controls)
                    // Find the video surface view and rotate it
                    val videoSurfaceView = playerView.videoSurfaceView
                    if (videoSurfaceView is android.view.View) {
                        videoSurfaceView.rotation = viewState.videoRotation
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Tap detector overlay for hiding controls
        // Only active when controls are visible and no panels are open
        if (showControls && !viewState.showPlaylist && !viewState.showEpgPanel) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // Hide controls on tap anywhere on screen
                                showControls = false
                            }
                        )
                    }
            )
        }

        // Custom Control Buttons Overlay (bottom) - synced with ExoPlayer controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            CustomControlButtons(
                onPlaylistClick = onTogglePlaylist,
                onFavoritesClick = onToggleFavorites,
                onGoToChannelClick = onGoToChannel,
                onAspectRatioClick = onCycleAspectRatio,
                onRotationClick = onToggleRotation,
                onSettingsClick = onOpenSettings,
                modifier = Modifier.padding(bottom = 56.dp) // Above ExoPlayer default controls (increased for bigger buttons)
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
    }
}

@Composable
private fun ChannelInfoOverlay(
    channelNumber: Int,
    channel: Channel,
    currentProgram: EpgProgram?,
    isArchivePlayback: Boolean,
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
                        text = stringResource(R.string.player_archive_label, program.title),
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
                        text = program.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.ruTvColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

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
            // Scroll with offset to center the item in viewport
            listState.animateScrollToItem(currentChannelIndex, scrollOffset = -50)
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
    Surface(
        modifier = modifier,
        color = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp), // Increased vertical padding
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp) // Increased spacing
            ) {
                IconButton(
                    onClick = onPlaylistClick,
                    modifier = Modifier.size(56.dp) // Bigger button size
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = stringResource(R.string.cd_playlist_button),
                        tint = MaterialTheme.ruTvColors.gold,
                        modifier = Modifier.size(32.dp) // Bigger icon size
                    )
                }

                IconButton(
                    onClick = onFavoritesClick,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = stringResource(R.string.cd_favorites_button),
                        tint = MaterialTheme.ruTvColors.gold,
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(
                    onClick = onGoToChannelClick,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Numbers,
                        contentDescription = stringResource(R.string.cd_go_to_channel_button),
                        tint = MaterialTheme.ruTvColors.gold,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Right side buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = onAspectRatioClick,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AspectRatio,
                        contentDescription = stringResource(R.string.cd_aspect_ratio_button),
                        tint = MaterialTheme.ruTvColors.gold,
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(
                    onClick = onRotationClick,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ScreenRotation,
                        contentDescription = stringResource(R.string.cd_orientation_button),
                        tint = MaterialTheme.ruTvColors.gold,
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.cd_settings_button),
                        tint = MaterialTheme.ruTvColors.gold,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}




