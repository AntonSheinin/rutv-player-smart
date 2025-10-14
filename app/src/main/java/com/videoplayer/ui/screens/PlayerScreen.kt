package com.videoplayer.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
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
import kotlinx.coroutines.launch
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
    getCurrentProgramForChannel: (String) -> EpgProgram?,
    modifier: Modifier = Modifier
) {
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
                        resizeMode = viewState.currentResizeMode

                        // Setup fullscreen button listener if needed
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                            // Handle controller visibility changes
                        })
                    }
                },
                update = { playerView ->
                    playerView.player = it
                    playerView.resizeMode = viewState.currentResizeMode
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Channel Info Overlay (top)
        viewState.currentChannel?.let { channel ->
            ChannelInfoOverlay(
                channelNumber = viewState.currentChannelIndex + 1,
                channel = channel,
                currentProgram = viewState.currentProgram,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )
        }

        // Playlist Panel
        if (viewState.showPlaylist) {
            PlaylistPanel(
                channels = viewState.filteredChannels,
                playlistTitle = viewState.playlistTitle,
                currentChannelIndex = viewState.currentChannelIndex,
                epgOpenIndex = if (viewState.showEpgPanel) viewState.currentChannelIndex else -1,
                onChannelClick = onPlayChannel,
                onFavoriteClick = onToggleFavorite,
                onShowPrograms = onShowEpgForChannel,
                onClose = onClosePlaylist,
                getCurrentProgram = getCurrentProgramForChannel,
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
                    .align(Alignment.BottomStart)
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
    onChannelClick: (Int) -> Unit,
    onFavoriteClick: (String) -> Unit,
    onShowPrograms: (String) -> Unit,
    onClose: () -> Unit,
    getCurrentProgram: (String) -> EpgProgram?,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to current channel
    LaunchedEffect(currentChannelIndex) {
        if (currentChannelIndex >= 0 && currentChannelIndex < channels.size) {
            listState.animateScrollToItem(currentChannelIndex)
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
                    .padding(16.dp),
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

            // Channel List
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(channels) { index, channel ->
                    ChannelListItem(
                        channel = channel,
                        channelNumber = index + 1,
                        isPlaying = index == currentChannelIndex,
                        isEpgOpen = index == epgOpenIndex,
                        currentProgram = getCurrentProgram(channel.tvgId),
                        onChannelClick = { onChannelClick(index) },
                        onFavoriteClick = { onFavoriteClick(channel.url) },
                        onShowPrograms = { onShowPrograms(channel.tvgId) },
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
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

    // Find current program index
    val currentProgramIndex = programs.indexOfFirst { program ->
        val start = program.startTimeMillis
        val end = program.stopTimeMillis
        start > 0L && end > 0L && currentTime in start..end
    }

    // Auto-scroll to current program
    LaunchedEffect(currentProgramIndex) {
        if (currentProgramIndex >= 0) {
            val offset = listState.layoutInfo.viewportSize.height / 2
            listState.animateScrollToItem(currentProgramIndex, -offset / 2)
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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            var lastDate = ""

            programs.forEachIndexed { index, program ->
                val programDate = dateFormat.format(Date(program.startTimeMillis))

                // Add date delimiter if date changed
                if (programDate != lastDate) {
                    item(key = "date_$programDate") {
                        EpgDateDelimiter(date = programDate)
                    }
                    lastDate = programDate
                }

                item(key = "program_${program.startTime}_${program.title}") {
                    EpgProgramItem(
                        program = program,
                        isCurrent = index == currentProgramIndex,
                        onClick = { onProgramClick(program) }
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
