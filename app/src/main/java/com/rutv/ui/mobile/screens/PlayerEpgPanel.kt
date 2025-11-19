package com.rutv.ui.mobile.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.rutv.R
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.ui.mobile.components.EpgDateDelimiter
import com.rutv.ui.mobile.components.EpgProgramItem
import com.rutv.ui.shared.components.RemoteDialog
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.shared.presentation.LayoutConstants
import com.rutv.ui.shared.presentation.TimeFormatter
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import java.time.Instant
import java.time.ZoneId
import java.util.Date

@UnstableApi
@Composable
internal fun EpgPanel(
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
internal fun EpgDatePickerDialog(
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
internal fun EpgDatePickerDialog(
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




