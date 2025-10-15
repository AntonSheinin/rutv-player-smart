package com.videoplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.ui.theme.ruTvColors
import java.text.SimpleDateFormat
import java.util.*

/**
 * EPG program item composable
 */
@Composable
fun EpgProgramItem(
    program: EpgProgram,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val startTime = program.startTimeMillis.takeIf { it > 0L }?.let { timeFormat.format(Date(it)) } ?: "--:--"

    val backgroundColor = if (isCurrent)
        MaterialTheme.ruTvColors.selectedBackground
    else
        MaterialTheme.ruTvColors.cardBackground

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Start Time only
        Text(
            text = startTime,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent)
                MaterialTheme.ruTvColors.gold
            else
                MaterialTheme.ruTvColors.textSecondary,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(50.dp) // Further reduced width
        )

        Spacer(modifier = Modifier.width(8.dp)) // Further reduced spacing to bring content even closer

        // Program Info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = program.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent)
                    MaterialTheme.ruTvColors.gold
                else
                    MaterialTheme.ruTvColors.textPrimary,
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
