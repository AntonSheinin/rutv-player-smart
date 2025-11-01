package com.videoplayer.ui.shared.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.videoplayer.R
import com.videoplayer.ui.shared.presentation.LayoutConstants
import com.videoplayer.ui.theme.ruTvColors
import androidx.compose.material3.MaterialTheme
import com.videoplayer.util.DeviceHelper
import com.videoplayer.ui.shared.components.focusIndicatorModifier

private const val DISABLED_CONTROL_ALPHA = 0.4f

/**
 * Custom control buttons overlay for player
 */
@Composable
fun CustomControlButtons(
    onPlaylistClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onGoToChannelClick: () -> Unit,
    onAspectRatioClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = LayoutConstants.LargePadding,
                vertical = LayoutConstants.LargePadding
            ),
        propagateMinConstraints = false
    ) {
        ControlColumn(
            modifier = Modifier.align(Alignment.CenterStart),
            buttons = listOf(
                ControlButtonData(
                    icon = Icons.AutoMirrored.Filled.List,
                    description = R.string.cd_playlist_button,
                    onClick = onPlaylistClick
                ),
                ControlButtonData(
                    icon = Icons.Default.Star,
                    description = R.string.cd_favorites_button,
                    onClick = onFavoritesClick
                ),
                ControlButtonData(
                    icon = Icons.Default.Numbers,
                    description = R.string.cd_go_to_channel_button,
                    onClick = onGoToChannelClick
                )
            )
        )

        ControlColumn(
            modifier = Modifier.align(Alignment.CenterEnd),
            buttons = listOf(
                ControlButtonData(
                    icon = Icons.Default.AspectRatio,
                    description = R.string.cd_aspect_ratio_button,
                    onClick = onAspectRatioClick
                ),
                ControlButtonData(
                    icon = Icons.Default.ScreenRotation,
                    description = R.string.cd_orientation_button,
                    onClick = {}
                ),
                ControlButtonData(
                    icon = Icons.Default.Settings,
                    description = R.string.cd_settings_button,
                    onClick = onSettingsClick
                )
            )
        )
    }
}

private data class ControlButtonData(
    val icon: ImageVector,
    @StringRes val description: Int,
    val onClick: () -> Unit
)

@Composable
private fun ControlColumn(
    modifier: Modifier,
    buttons: List<ControlButtonData>
) {
    val isRemoteMode = DeviceHelper.isRemoteInputActive()
    val focusRequesters = remember(buttons.size) {
        List(buttons.size) { FocusRequester() }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.55f),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(
                    horizontal = LayoutConstants.CardHorizontalPadding,
                    vertical = LayoutConstants.DefaultPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)
        ) {
            buttons.forEachIndexed { index, button ->
                val isRotation = button.description == R.string.cd_orientation_button
                val alpha = if (isRotation) DISABLED_CONTROL_ALPHA else 1f
                var isFocused by remember { mutableStateOf(false) }

                IconButton(
                    onClick = if (isRotation) ({}) else button.onClick,
                    modifier = Modifier
                        .size(LayoutConstants.ControlButtonSize)
                        .alpha(alpha)
                        .focusable(enabled = isRemoteMode && !isRotation)
                        .focusRequester(focusRequesters[index])
                        .onFocusChanged { isFocused = it.isFocused }
                        .then(if (isRemoteMode) focusIndicatorModifier(isFocused = isFocused) else Modifier)
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && isFocused && !isRotation) {
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter -> {
                                        button.onClick()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                ) {
                    Icon(
                        imageVector = button.icon,
                        contentDescription = stringResource(button.description),
                        tint = MaterialTheme.ruTvColors.gold,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
