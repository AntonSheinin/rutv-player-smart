package com.rutv.ui.mobile.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rutv.R
import com.rutv.data.model.EpgProgram
import com.rutv.ui.shared.presentation.TimeFormatter
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.*

/**
 * EPG program item composable with remote control focus support
 */
@Composable
fun EpgProgramItem(
    program: EpgProgram,
    isCurrent: Boolean,
    isPast: Boolean,
    showArchiveIndicator: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayArchive: (() -> Unit)? = null,
    onCloseEpg: (() -> Unit)? = null,
    onNavigateUp: (() -> Boolean)? = null,
    onNavigateDown: (() -> Boolean)? = null,
    onNavigateLeft: (() -> Boolean)? = null,
    onFocused: ((Boolean) -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val isRemoteMode = DeviceHelper.isRemoteInputActive()

    val startTime = program.startTimeMillis.takeIf { it > 0L }?.let {
        TimeFormatter.formatTime(Date(it))
    } ?: stringResource(R.string.time_placeholder_colon)

    val backgroundColor = if (isCurrent) {
        MaterialTheme.ruTvColors.selectedBackground
    } else {
        MaterialTheme.ruTvColors.cardBackground
    }
    val contentAlpha = if (isPast && !isCurrent) 0.5f else 1f

    // Handle remote key events
    val onRemoteKeyEvent: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        if (event.type == KeyEventType.KeyDown && isFocused && isRemoteMode) {
            when (event.key) {
                Key.DirectionCenter, // DPAD_CENTER (OK button)
                Key.Enter -> {
                    // OK button: If program is past and in archive, play archive; otherwise show details
                    // onPlayArchive is only provided when program can be played from archive
                    if (onPlayArchive != null && isPast && showArchiveIndicator) {
                        onPlayArchive.invoke()
                    } else {
                        onClick()
                    }
                    true
                }
                Key.DirectionRight -> {
                    // RIGHT: Play archive if available (alternative way to play archive)
                    onPlayArchive?.invoke()
                    true
                }
                Key.DirectionUp -> {
                    // UP: Navigate to previous program in EPG list
                    onNavigateUp?.invoke() ?: false
                }
                Key.DirectionDown -> {
                    // DOWN: Navigate to next program in EPG list
                    onNavigateDown?.invoke() ?: false
                }
                Key.DirectionLeft -> {
                    // LEFT: Return focus to channel list (or close EPG if no channel list)
                    when {
                        onNavigateLeft != null -> onNavigateLeft.invoke()
                        onCloseEpg != null -> {
                            onCloseEpg.invoke()
                            true
                        }
                        else -> false
                    }
                }
                else -> false
            }
        } else {
            false
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .alpha(contentAlpha)
            .focusable(enabled = true)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                onFocused?.invoke(it.isFocused)
            }
            .onKeyEvent(onRemoteKeyEvent)
            .then(focusIndicatorModifier(isFocused = isFocused))
            .then(
                if (!isRemoteMode) {
                    Modifier.pointerInput(onClick, onPlayArchive) {
                        detectTapGestures(
                            onTap = { onClick() },
                            onDoubleTap = { onPlayArchive?.invoke() }
                        )
                    }
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Start Time only
        Text(
            text = startTime,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) {
                MaterialTheme.ruTvColors.gold
            } else {
                MaterialTheme.ruTvColors.textSecondary
            },
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(50.dp) // Compact width keeps layout tight
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Program Info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = program.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) {
                    MaterialTheme.ruTvColors.gold
                } else {
                    MaterialTheme.ruTvColors.textPrimary
                },
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            program.description.takeIf { it.isNotEmpty() }?.let { description ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.ruTvColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showArchiveIndicator) {
            Spacer(modifier = Modifier.width(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        BorderStroke(1.dp, MaterialTheme.ruTvColors.gold.copy(alpha = 0.65f)),
                        RoundedCornerShape(12.dp)
                    )
                    .background(MaterialTheme.ruTvColors.gold.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = stringResource(R.string.cd_epg_archive_indicator),
                    tint = MaterialTheme.ruTvColors.gold,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * EPG date delimiter composable
 */
@Composable
fun EpgDateDelimiter(
    date: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.ruTvColors.darkBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = date,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.ruTvColors.gold,
            fontWeight = FontWeight.Bold
        )
    }
}


