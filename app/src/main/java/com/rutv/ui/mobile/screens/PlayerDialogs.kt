package com.rutv.ui.mobile.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.rutv.R
import com.rutv.data.model.EpgProgram
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.shared.presentation.LayoutConstants
import com.rutv.ui.shared.presentation.TimeFormatter
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper
import kotlinx.coroutines.launch
import java.util.Date

@UnstableApi
@Composable
internal fun ProgramDetailsPanel(
    program: EpgProgram,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRemoteMode = DeviceHelper.isRemoteInputActive()
    val closeButtonFocus = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    var closeButtonFocused by remember { mutableStateOf(false) }

    BackHandler(enabled = true) { onClose() }

    LaunchedEffect(isRemoteMode) {
        if (isRemoteMode) {
            contentFocusRequester.requestFocus()
        }
    }

    val startTimeFormatted = program.startTimeMillis.takeIf { it > 0L }?.let {
        TimeFormatter.formatProgramDateTime(Date(it))
    } ?: stringResource(R.string.time_placeholder)
    val density = LocalDensity.current
    val scrollStepPx = remember(density) { with(density) { 200.dp.toPx() } }

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
                        .focusable()
                        .focusRequester(closeButtonFocus)
                        .onFocusChanged { closeButtonFocused = it.isFocused }
                        .then(focusIndicatorModifier(isFocused = closeButtonFocused))
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter, Key.Back -> {
                                        onClose()
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        contentFocusRequester.requestFocus()
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
                val listState = rememberLazyListState()

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(contentFocusRequester)
                        .focusable()
                        .onKeyEvent { event ->
                            val remoteActive = DeviceHelper.isRemoteInputActive()
                            if (!remoteActive || event.type != KeyEventType.KeyDown) {
                                return@onKeyEvent false
                            }
                            val totalItems = listState.layoutInfo.totalItemsCount
                            if (totalItems <= 0) return@onKeyEvent false
                            when (event.key) {
                                Key.Back,
                                Key.DirectionCenter,
                                Key.Enter -> {
                                    onClose()
                                    true
                                }
                                Key.DirectionLeft,
                                Key.DirectionRight -> true
                                Key.DirectionDown -> {
                                    coroutineScope.launch {
                                        listState.scrollByIfPossible(scrollStepPx)
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    val atTop = listState.firstVisibleItemIndex == 0 &&
                                        listState.firstVisibleItemScrollOffset == 0
                                    if (atTop) {
                                        closeButtonFocus.requestFocus()
                                    } else {
                                        coroutineScope.launch {
                                            listState.scrollByIfPossible(-scrollStepPx)
                                        }
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 20.dp, bottom = 16.dp)
                ) {
                    item {
                        Text(
                            text = program.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.ruTvColors.gold
                        )
                    }

                    item {
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

                FocusedScrollBar(listState = listState)
            }
        }
    }
}

@UnstableApi
@Composable
internal fun DebugLogPanel(
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

@Composable
private fun BoxScope.FocusedScrollBar(
    listState: LazyListState
) {
    val showScrollbar = remember {
        androidx.compose.runtime.derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }

    if (showScrollbar.value) {
        val scrollProgress = remember { androidx.compose.runtime.derivedStateOf { calculateScrollProgress(listState) } }
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
