package com.rutv.ui.mobile.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.rutv.R
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.ui.mobile.components.ChannelListItem
import com.rutv.ui.shared.components.RemoteDialog
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.shared.presentation.LayoutConstants
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.math.max

@UnstableApi
@Composable
internal fun PlaylistPanel(
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
    focusManager: PlayerFocusManager,
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

    val closeButtonFocus = remember { FocusRequester() }
    var channelThatOpenedEpg by remember { mutableStateOf<Int?>(null) }
    var pendingScrollJob by remember { mutableStateOf<Job?>(null) }

    val focusChannel: (Int, Boolean) -> Boolean = { targetIndex, play ->
        when {
            targetIndex !in channels.indices -> false
            play -> {
                // If playing, always call onChannelClick even if not in displayedList
                // This handles favorites view where displayedList is filtered
                onChannelClick(targetIndex)
                true
            }
            targetIndex !in displayedList.indices -> {
                // For focus-only operations, check if in displayedList
                onRequestMoreChannels(targetIndex + PLAYLIST_PREFETCH_MARGIN)
                false
            }
            else -> {
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
                true
            }
        }
    }
    val lazyColumnFocusRequester = remember { FocusRequester() }

    // Register with focus manager
    LaunchedEffect(lazyColumnFocusRequester, focusChannel) {
        focusManager.registerEntry(PlayerFocusDestination.PLAYLIST_PANEL, lazyColumnFocusRequester)
        focusManager.registerFocusCallback(PlayerFocusDestination.PLAYLIST_PANEL, focusChannel)
    }

    DisposableEffect(Unit) {
        onDispose {
            focusManager.unregisterEntry(PlayerFocusDestination.PLAYLIST_PANEL)
            focusManager.registerFocusCallback(PlayerFocusDestination.PLAYLIST_PANEL, null)
            val latestFocusedIndex = focusedChannelIndex
            val latestChannels = channels
            val latestCurrentChannelIndex = currentChannelIndex
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

    // When playlist panel becomes active, ensure focus is on the list
    LaunchedEffect(focusManager.currentDestination) {
        if (focusManager.currentDestination == PlayerFocusDestination.PLAYLIST_PANEL) {
            lazyColumnFocusRequester.requestFocus()
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
            .padding(LayoutConstants.DefaultPadding)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        showSearchDialog = true
                        true
                    }
                    Key.DirectionRight -> {
                        val currentIdx = when {
                            focusedChannelIndex >= 0 -> focusedChannelIndex
                            currentChannelIndex in channels.indices -> currentChannelIndex
                            else -> -1
                        }
                        val channel = channels.getOrNull(currentIdx)
                        if (channel?.hasEpg == true) {
                            playlistHasFocus = false
                            onShowPrograms(channel.tvgId)
                            onRequestEpgFocus?.invoke()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f)
        ),
        border = BorderStroke(2.dp, MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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

            Box(modifier = Modifier.fillMaxSize()) {
                val isEpgPanelVisible = epgOpenIndex >= 0
                val longPressThresholdMs = 450L

                LaunchedEffect(Unit) {
                    delay(150)
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
                                            // Find previous channel in displayedList, then get its index in channels
                                            val currentDisplayedIndex = displayedList.indexOfFirst {
                                                channels.indexOf(it) == focusedChannelIndex
                                            }
                                            if (currentDisplayedIndex > 0) {
                                                val prevChannel = displayedList[currentDisplayedIndex - 1]
                                                val prevChannelIndex = channels.indexOf(prevChannel)
                                                if (prevChannelIndex >= 0) {
                                                    focusChannel(prevChannelIndex, false)
                                                }
                                            }
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            // Find next channel in displayedList, then get its index in channels
                                            val currentDisplayedIndex = displayedList.indexOfFirst {
                                                channels.indexOf(it) == focusedChannelIndex
                                            }
                                            if (currentDisplayedIndex >= 0 && currentDisplayedIndex < displayedList.lastIndex) {
                                                val nextChannel = displayedList[currentDisplayedIndex + 1]
                                                val nextChannelIndex = channels.indexOf(nextChannel)
                                                if (nextChannelIndex >= 0) {
                                                    focusChannel(nextChannelIndex, false)
                                                }
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
                                                    okLongPressHandled = true
                                                    val channel = channels.getOrNull(focusedChannelIndex)
                                                    if (channel != null) {
                                                        onFavoriteClick(channel.url)
                                                    }
                                                }
                                            }
                                            true
                                        }
                                        Key.DirectionLeft -> {
                                            onRequestEpgFocus?.invoke()
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            onRequestEpgFocus?.invoke()
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
                        val actualIndex = remember(channel.url) {
                            val byUrl = channels.indexOfFirst { it.url == channel.url }
                            val byRef = channels.indexOf(channel)
                            when {
                                byUrl >= 0 -> byUrl
                                byRef >= 0 -> byRef
                                channels.isNotEmpty() -> index.coerceIn(0, channels.lastIndex)
                                else -> -1
                            }
                        }
                        val resolvedIndex = actualIndex.takeIf { it >= 0 } ?: return@itemsIndexed
                        val programInfo = remember(channel.tvgId, currentProgramsMap[channel.tvgId]) {
                            currentProgramsMap[channel.tvgId]
                        }
                        ChannelListItem(
                            channel = channel,
                            channelNumber = resolvedIndex + 1,
                            isPlaying = resolvedIndex == currentChannelIndex,
                            isEpgOpen = resolvedIndex == epgOpenIndex,
                            isEpgPanelVisible = isEpgPanelVisible,
                            currentProgram = programInfo,
                            isItemFocused = playlistHasFocus && resolvedIndex == focusedChannelIndex,
                            onChannelClick = { focusChannel(resolvedIndex, true) },
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

private const val PLAYLIST_PREFETCH_MARGIN = 8
