package com.videoplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.videoplayer.R
import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.ui.theme.ruTvColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Channel list item composable with double-tap support
 */
@Composable
fun ChannelListItem(
    channel: Channel,
    channelNumber: Int,
    isPlaying: Boolean,
    isEpgOpen: Boolean,
    currentProgram: EpgProgram?,
    onChannelClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onShowPrograms: () -> Unit,
    modifier: Modifier = Modifier
) {
    var lastClickTime by remember { mutableLongStateOf(0L) }
    var clickJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val backgroundColor = when {
        isEpgOpen -> MaterialTheme.ruTvColors.epgOpenBackground
        isPlaying -> MaterialTheme.ruTvColors.selectedBackground
        else -> MaterialTheme.ruTvColors.cardBackground
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastClick = currentTime - lastClickTime

                // Cancel pending single tap
                clickJob?.cancel()

                if (timeSinceLastClick < 300) {
                    // Double tap - play channel
                    lastClickTime = 0
                    onChannelClick()
                } else {
                    // Single tap - schedule EPG show
                    lastClickTime = currentTime
                    clickJob = coroutineScope.launch {
                        delay(300)
                        if (channel.hasEpg) {
                            onShowPrograms()
                        }
                    }
                }
            },
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Channel Logo
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(channel.logo.ifEmpty { null })
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(R.string.cd_channel_logo),
                placeholder = painterResource(R.drawable.ic_channel_placeholder),
                error = painterResource(R.drawable.ic_channel_placeholder),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .padding(end = 12.dp)
            )

            // Channel Info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                // Channel Number and Title
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$channelNumber",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.ruTvColors.textHint,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = channel.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.ruTvColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Channel Group
                channel.group.takeIf { it.isNotEmpty() }?.let { group ->
                    Text(
                        text = group,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ruTvColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Current Program
                if (channel.hasEpg) {
                    Text(
                        text = currentProgram?.title ?: stringResource(R.string.status_no_program),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ruTvColors.textHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Playing Status
                if (isPlaying) {
                    Text(
                        text = stringResource(R.string.status_playing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ruTvColors.statusPlaying
                    )
                }
            }

            // Favorite Button
            Text(
                text = if (channel.isFavorite)
                    stringResource(R.string.favorite_filled)
                else
                    stringResource(R.string.favorite_empty),
                style = MaterialTheme.typography.headlineSmall,
                color = if (channel.isFavorite)
                    MaterialTheme.ruTvColors.gold
                else
                    MaterialTheme.ruTvColors.textDisabled,
                modifier = Modifier
                    .clickable(onClick = onFavoriteClick)
                    .padding(8.dp)
            )
        }
    }
}
