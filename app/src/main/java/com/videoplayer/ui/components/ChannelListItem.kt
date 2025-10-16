package com.videoplayer.ui.components

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.videoplayer.R
import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.ui.theme.ruTvColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ChannelLogoSize = 48.dp

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
        isPlaying -> MaterialTheme.ruTvColors.selectedBackground // Currently playing channel
        isEpgOpen -> MaterialTheme.ruTvColors.epgOpenBackground // EPG panel is open for this channel
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
                .padding(horizontal = 12.dp, vertical = 6.dp), // Reduced vertical padding to make items narrower
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Channel Logo
            val context = LocalContext.current
            val sizePx = with(LocalDensity.current) { ChannelLogoSize.roundToPx() }
            val logoRequest = remember(channel.logo, sizePx) {
                ImageRequest.Builder(context)
                    .data(channel.logo.takeIf { it.isNotBlank() })
                    .size(sizePx, sizePx)
                    .scale(Scale.FIT)
                    .precision(Precision.EXACT)
                    .allowHardware(true)
                    .crossfade(false)
                    .build()
            }

            AsyncImage(
                model = logoRequest,
                imageLoader = LocalImageLoader.current,
                contentDescription = stringResource(R.string.cd_channel_logo),
                placeholder = painterResource(R.drawable.ic_channel_placeholder),
                error = painterResource(R.drawable.ic_channel_placeholder),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(ChannelLogoSize)
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

                // Current Program (only show if we have program data, not just "No program")
                if (channel.hasEpg && currentProgram != null) {
                    Text(
                        text = currentProgram.title,
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
                style = MaterialTheme.typography.headlineLarge, // Changed from headlineSmall to headlineLarge
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
