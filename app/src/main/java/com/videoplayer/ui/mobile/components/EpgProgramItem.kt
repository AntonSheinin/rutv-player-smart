package com.videoplayer.ui.mobile.components

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
import com.videoplayer.R
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.ui.shared.presentation.TimeFormatter
import com.videoplayer.ui.theme.ruTvColors
import java.util.*

/**
 * EPG program item composable
 */
@Composable
fun EpgProgramItem(
    program: EpgProgram,
    isCurrent: Boolean,
    isPast: Boolean,
    showArchiveIndicator: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayArchive: (() -> Unit)? = null
) {
    val startTime = program.startTimeMillis.takeIf { it > 0L }?.let {
        TimeFormatter.formatTime(Date(it))
    } ?: "--:--"

    val backgroundColor = if (isCurrent) {
        MaterialTheme.ruTvColors.selectedBackground
    } else {
        MaterialTheme.ruTvColors.cardBackground
    }
    val contentAlpha = if (isPast && !isCurrent) 0.5f else 1f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .alpha(contentAlpha)
            .pointerInput(onClick, onPlayArchive) {
                detectTapGestures(
                    onTap = { onClick() },
                    onDoubleTap = { onPlayArchive?.invoke() }
                )
            }
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




