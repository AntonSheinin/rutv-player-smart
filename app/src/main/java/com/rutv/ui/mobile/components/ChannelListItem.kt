package com.rutv.ui.mobile.components

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.withTimeoutOrNull

private val ChannelLogoSize = Constants.CHANNEL_LOGO_SIZE_DP.dp
private val ChannelItemRowModifier = Modifier
    .fillMaxWidth()
    .padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 6.dp)
private val ChannelLogoModifier = Modifier
    .size(size = ChannelLogoSize)
    .padding(end = 12.dp)

/**
 * Channel list item with state-based remote focus and explicit touch actions.
 */
@Composable
fun ChannelListItem(
    channel: Channel,
    channelIndex: Int,
    channelNumber: Int,
    isPlaying: Boolean,
    statusText: String? = null,
    isEpgOpen: Boolean,
    isEpgPanelVisible: Boolean = isEpgOpen,
    currentProgram: EpgProgram?,
    onChannelSelected: (Int) -> Unit,
    onChannelPlay: (Int) -> Unit,
    onFavoriteClick: (String) -> Unit,
    onLockToggle: (Int) -> Unit,
    isItemFocused: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isRemoteMode = DeviceHelper.isRemoteInputActive()
    val latestOnFavoriteClick by rememberUpdatedState(onFavoriteClick)
    val latestOnLockToggle by rememberUpdatedState(onLockToggle)
    val favoriteClick = remember(channel.url) {
        { latestOnFavoriteClick(channel.url) }
    }
    val lockClick = remember(channelIndex) {
        { latestOnLockToggle(channelIndex) }
    }

    val backgroundColor = when {
        isEpgOpen -> MaterialTheme.ruTvColors.epgOpenBackground
        isPlaying -> MaterialTheme.ruTvColors.selectedBackground
        else -> MaterialTheme.ruTvColors.cardBackground
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(focusIndicatorModifier(isFocused = isItemFocused)),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = ChannelItemRowModifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(channel.url, channelIndex, onChannelSelected, onChannelPlay) {
                        detectImmediateTapAndDoubleTap(
                            onTap = { onChannelSelected(channelIndex) },
                            onDoubleTap = { onChannelPlay(channelIndex) }
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                val context = LocalContext.current
                val sizePx = with(LocalDensity.current) { ChannelLogoSize.toPx().toInt() }
                val logoSizePx = remember(context) { sizePx }
                val logoRequest = remember(channel.logo) {
                    channel.logo.takeIf { it.isNotBlank() }?.let { logoUrl ->
                        ImageRequest.Builder(context)
                            .data(logoUrl)
                            .size(logoSizePx)
                            .scale(Scale.FILL)
                            .precision(Precision.INEXACT)
                            .bitmapConfig(Bitmap.Config.RGB_565)
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
                    modifier = ChannelLogoModifier
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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

                    val groupLabel = remember(channel.group, channel.groups) {
                        val groups = channel.allGroups
                        when {
                            groups.isEmpty() -> ""
                            groups.size == 1 -> groups.first()
                            groups.size == 2 -> "${groups[0]} • ${groups[1]}"
                            else -> "${groups[0]} • ${groups[1]} +${groups.size - 2}"
                        }
                    }
                    groupLabel.takeIf { it.isNotBlank() }?.let { group ->
                        Text(
                            text = group,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.ruTvColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (channel.hasEpg && !channel.isLocked && currentProgram != null) {
                        Text(
                            text = currentProgram.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.ruTvColors.textHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (isPlaying) {
                        Text(
                            text = stringResource(R.string.status_playing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.ruTvColors.statusPlaying
                        )
                    }

                    statusText?.takeIf { it.isNotBlank() }?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = favoriteClick,
                    modifier = Modifier
                        .size(40.dp)
                        .focusProperties { canFocus = !isRemoteMode }
                ) {
                    Icon(
                        imageVector = if (channel.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = stringResource(R.string.cd_favorites_button),
                        tint = if (channel.isFavorite) {
                            MaterialTheme.ruTvColors.gold
                        } else {
                            MaterialTheme.ruTvColors.textDisabled
                        }
                    )
                }
                IconButton(
                    onClick = lockClick,
                    modifier = Modifier
                        .size(40.dp)
                        .focusProperties { canFocus = !isRemoteMode }
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = stringResource(R.string.cd_toggle_channel_lock),
                        tint = if (channel.isLocked) {
                            MaterialTheme.ruTvColors.gold
                        } else {
                            MaterialTheme.ruTvColors.textDisabled
                        }
                    )
                }
            }
        }
    }
}

private suspend fun PointerInputScope.detectImmediateTapAndDoubleTap(
    onTap: () -> Unit,
    onDoubleTap: () -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val firstUp = waitForUpOrCancellation()
        if (firstUp != null) {
            onTap()
            val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                awaitFirstDown(requireUnconsumed = false)
            }
            if (secondDown != null && waitForUpOrCancellation() != null) {
                onDoubleTap()
            }
        }
    }
}
