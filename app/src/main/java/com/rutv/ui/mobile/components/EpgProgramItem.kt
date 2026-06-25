package com.rutv.ui.mobile.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rutv.R
import com.rutv.data.model.EpgProgram
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.shared.presentation.LayoutConstants
import com.rutv.ui.shared.presentation.TimeFormatter
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper
import java.util.Date

private val EpgProgramItemBaseModifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = 12.dp, vertical = 4.dp)
private val EpgStartTimeModifier = Modifier.width(50.dp)
private val EpgArchiveIconButtonModifier = Modifier.size(LayoutConstants.MinTouchTarget)
private val EpgArchiveIconModifier = Modifier.size(16.dp)

/**
 * EPG program item with state-based remote focus and explicit touch actions.
 */
@Composable
fun EpgProgramItem(
    program: EpgProgram,
    isCurrent: Boolean,
    isPast: Boolean,
    showArchiveIndicator: Boolean,
    onProgramClick: (EpgProgram) -> Unit,
    isItemFocused: Boolean = false,
    modifier: Modifier = Modifier,
    onPlayArchive: ((EpgProgram) -> Unit)? = null
) {
    val isRemoteMode = DeviceHelper.isRemoteInputActive()
    val timePlaceholder = stringResource(R.string.time_placeholder_colon)
    val startTime = remember(program.startTimeMillis, timePlaceholder) {
        program.startTimeMillis.takeIf { it > 0L }?.let {
            TimeFormatter.formatTime(Date(it))
        } ?: timePlaceholder
    }

    val backgroundColor = if (isCurrent) {
        MaterialTheme.ruTvColors.selectedBackground
    } else {
        MaterialTheme.ruTvColors.cardBackground
    }
    val contentAlpha = if (isPast && !isCurrent) 0.5f else 1f
    val focusShape = RoundedCornerShape(12.dp)
    val itemHeight = if (LocalConfiguration.current.screenWidthDp.dp >= LayoutConstants.EpgTabletWidthBreakpoint) {
        LayoutConstants.EpgTabletItemHeight
    } else {
        LayoutConstants.EpgItemHeight
    }

    Row(
        modifier = modifier
            .then(EpgProgramItemBaseModifier)
            .height(itemHeight)
            .then(focusIndicatorModifier(isFocused = isItemFocused, shape = focusShape))
            .clip(focusShape)
            .background(backgroundColor)
            .alpha(contentAlpha)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .pointerInput(program.id, onProgramClick, onPlayArchive) {
                    if (onPlayArchive == null) {
                        detectImmediateTap(onTap = { onProgramClick(program) })
                    } else {
                        detectTapGestures(
                            onTap = { onProgramClick(program) },
                            onDoubleTap = { onPlayArchive(program) }
                        )
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = startTime,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) {
                    MaterialTheme.ruTvColors.gold
                } else {
                    MaterialTheme.ruTvColors.textSecondary
                },
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                modifier = EpgStartTimeModifier
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrent) {
                        MaterialTheme.ruTvColors.gold
                    } else {
                        MaterialTheme.ruTvColors.textPrimary
                    },
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                program.description.takeIf { it.isNotEmpty() }?.let { description ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ruTvColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (showArchiveIndicator) {
            Spacer(modifier = Modifier.width(12.dp))
            IconButton(
                onClick = { onPlayArchive?.invoke(program) },
                enabled = onPlayArchive != null,
                modifier = EpgArchiveIconButtonModifier
                    .focusProperties { canFocus = !isRemoteMode }
            ) {
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
                        modifier = EpgArchiveIconModifier
                    )
                }
            }
        }
    }
}

private suspend fun PointerInputScope.detectImmediateTap(onTap: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val firstUp = waitForUpOrCancellation()
        if (firstUp != null) {
            onTap()
        }
    }
}

/**
 * EPG date delimiter composable.
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
