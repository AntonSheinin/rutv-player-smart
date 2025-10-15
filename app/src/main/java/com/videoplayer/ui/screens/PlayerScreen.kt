package com.videoplayer.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
                        controllerShowTimeoutMs = 2000 // 2 seconds timeout
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
                            // Switch to previous channel
                            val currentIndex = viewState.currentChannelIndex
                            if (currentIndex > 0) {
                                onPlayChannel(currentIndex - 1)
                            }
                        }

                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_next)?.setOnClickListener {
                            // Switch to next channel
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
                    // Apply rotation to entire PlayerView (this rotates the video content)
                    playerView.rotation = viewState.videoRotation
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
                epgOpenIndex = if (viewState.showEpgPanel) viewState.currentChannelIndex else -1,
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
                onProgramClick = { /* Handle program click if needed */ },
                modifier = Modifier.align(Alignment.CenterEnd)
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
            currentProgram?.let { program ->
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.ruTvColors.textSecondary
                )
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
    val coroutineScope = rememberCoroutineScope()

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
        )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp), // Reduced vertical padding to match EPG
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

            Divider(color = MaterialTheme.ruTvColors.textDisabled)

            // Channel List with scrollbar
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp, end = 12.dp) // Add end padding for scrollbar
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
                val scrollProgress = remember {
                    derivedStateOf {
                        if (channels.isEmpty()) 0f
                        else listState.firstVisibleItemIndex.toFloat() / channels.size.toFloat()
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(4.dp)
                        .padding(vertical = 8.dp)
                        .background(MaterialTheme.ruTvColors.textDisabled.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxWidth()
                            .fillMaxHeight(0.1f)
                            .offset(y = (scrollProgress.value * 0.9f * 100).dp)
                            .background(MaterialTheme.ruTvColors.gold)
                    )
                }
            }
        }
    }
}

@Composable
private fun EpgPanel(
    programs: List<EpgProgram>,
    onProgramClick: (EpgProgram) -> Unit,
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
            // Scroll with offset to center the item in viewport
            listState.animateScrollToItem(scrollToIndex, scrollOffset = -200)
        }
    }

    Card(
        modifier = modifier
            .fillMaxHeight()
            .width(400.dp)
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f)
        )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp), // Consistent padding with channel list
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.epg_panel_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.ruTvColors.gold
                )
            }

            Divider(color = MaterialTheme.ruTvColors.textDisabled)

            // Programs List with scrollbar
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp, end = 16.dp) // Add end padding for scrollbar
                ) {
                    items(items.size, key = { items[it].first }) { index ->
                        val item = items[index]
                        when (val data = item.second) {
                            is String -> {
                                EpgDateDelimiter(date = data)
                            }
                            is EpgProgram -> {
                                val programIndex = programs.indexOf(data)
                                EpgProgramItem(
                                    program = data,
                                    isCurrent = programIndex == currentProgramIndex,
                                    onClick = { onProgramClick(data) }
                                )
                            }
                        }
                    }
                }

                // Scroll indicator
                val scrollProgress = remember {
                    derivedStateOf {
                        if (items.isEmpty()) 0f
                        else listState.firstVisibleItemIndex.toFloat() / items.size.toFloat()
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(4.dp)
                        .padding(vertical = 8.dp)
                        .background(MaterialTheme.ruTvColors.textDisabled.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxWidth()
                            .fillMaxHeight(0.1f)
                            .offset(y = (scrollProgress.value * 0.9f * 100).dp)
                            .background(MaterialTheme.ruTvColors.gold)
                    )
                }
            }
        }
    }
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
            Divider(color = MaterialTheme.ruTvColors.textDisabled)

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
                        imageVector = Icons.Default.List,
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
