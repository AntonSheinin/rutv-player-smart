package com.rutv.ui.mobile.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.rutv.R
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.Constants
import com.rutv.util.DeviceHelper
import com.rutv.util.PlayerConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ChannelLogoSize = Constants.CHANNEL_LOGO_SIZE_DP.dp

/**
 * Channel list item composable with double-tap support and remote control focus
 */
@Composable
fun ChannelListItem(
    channel: Channel,
    channelNumber: Int,
    isPlaying: Boolean,
    isEpgOpen: Boolean,
    isEpgPanelVisible: Boolean = isEpgOpen,
    currentProgram: EpgProgram?,
    onChannelClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onShowPrograms: () -> Unit,
    isItemFocused: Boolean = false, // Visual focus indicator for state-based focus
    modifier: Modifier = Modifier
) {
    var lastClickTime by remember { mutableLongStateOf(0L) }
    var clickJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val isRemoteMode = DeviceHelper.isRemoteInputActive()

    val backgroundColor = when {
        isEpgOpen -> MaterialTheme.ruTvColors.epgOpenBackground // EPG panel is open (yellow) - takes priority
        isPlaying -> MaterialTheme.ruTvColors.selectedBackground // Currently playing channel (gray)
        else -> MaterialTheme.ruTvColors.cardBackground
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(focusIndicatorModifier(isFocused = isItemFocused)) // Use isItemFocused for visual indicator
            .clickable(enabled = !isRemoteMode) {
                // Touch-only clickable behavior (double-tap/single-tap)
                val currentTime = System.currentTimeMillis()
                val timeSinceLastClick = currentTime - lastClickTime

                // Cancel pending single tap
                clickJob?.cancel()

                if (timeSinceLastClick < PlayerConstants.DOUBLE_TAP_DELAY_MS) {
                    // Double tap - play channel
                    lastClickTime = 0
                    onChannelClick()
                } else {
                    // Single tap - schedule EPG show
                    lastClickTime = currentTime
                    clickJob = coroutineScope.launch {
                        delay(PlayerConstants.DOUBLE_TAP_DELAY_MS)
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
            // Channel Logo - optimized for performance
            val context = LocalContext.current
            val sizePx = with(LocalDensity.current) { ChannelLogoSize.toPx().toInt() }

            // Build request only when logo URL changes (not on every recomposition)
            val logoSizePx = remember(context) { sizePx }
            val logoRequest = remember(channel.logo) {
                channel.logo.takeIf { it.isNotBlank() }?.let { logoUrl ->
                    ImageRequest.Builder(context)
                        .data(logoUrl)
                        .size(logoSizePx)
                        .scale(Scale.FILL)
                        .precision(Precision.INEXACT)
                        .allowHardware(false)
                        .crossfade(false)
                        .diskCacheKey(logoUrl)
                        .memoryCacheKey(logoUrl)
                        .build()
                }
            }

            AsyncImage(
                model = logoRequest,
                contentDescription = stringResource(R.string.cd_channel_logo),
                placeholder = painterResource(R.drawable.ic_channel_placeholder),
                error = painterResource(R.drawable.ic_channel_placeholder),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(size = ChannelLogoSize)
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
                style = MaterialTheme.typography.headlineLarge,
                color = if (channel.isFavorite)
                    MaterialTheme.ruTvColors.gold
                else
                    MaterialTheme.ruTvColors.textDisabled,
                modifier = Modifier
                    .clickable(enabled = !isRemoteMode, onClick = onFavoriteClick)
                    .padding(8.dp)
            )
        }
    }
}
