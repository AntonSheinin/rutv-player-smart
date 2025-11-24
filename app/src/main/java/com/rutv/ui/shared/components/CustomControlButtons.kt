package com.rutv.ui.shared.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.rutv.R
import com.rutv.ui.shared.presentation.LayoutConstants
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper
import com.rutv.ui.shared.components.focusIndicatorModifier

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
    onNavigateRightFromFavorites: (() -> Unit)? = null,
    onNavigateLeftFromRotate: (() -> Unit)? = null,
    onRegisterFocusRequesters: ((List<FocusRequester>, List<FocusRequester>) -> Unit)? = null,
    onRegisterExternalFocusControllers: ((setLeft: (Int?) -> Unit, setRight: (Int?) -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isRemoteMode by remember { mutableStateOf(true) }

    val updateRemoteMode: (Boolean) -> Unit = { active ->
        if (isRemoteMode != active) {
            isRemoteMode = active
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = LayoutConstants.LargePadding,
                vertical = LayoutConstants.LargePadding
            ),
        propagateMinConstraints = false
    ) {
        val leftColumnFocusRequesters = remember {
            List(3) { FocusRequester() }
        }
        val rightColumnFocusRequesters = remember {
            List(3) { FocusRequester() }
        }
        var externalLeftFocus by remember { mutableStateOf<Int?>(null) }
        var externalRightFocus by remember { mutableStateOf<Int?>(null) }

        LaunchedEffect(Unit) {
            onRegisterFocusRequesters?.invoke(leftColumnFocusRequesters, rightColumnFocusRequesters)
            onRegisterExternalFocusControllers?.invoke(
                { externalLeftFocus = it },
                { externalRightFocus = it }
            )
        }

        val leftColumnKeyHandler: (Int, KeyEvent) -> Boolean = leftHandler@{ index, event ->
            if (event.type != KeyEventType.KeyDown) return@leftHandler false
            when (index) {
                0 -> when (event.key) {
                    Key.DirectionRight -> {
                        rightColumnFocusRequesters.getOrNull(0)?.requestFocus()
                        true
                    }
                    Key.DirectionDown -> {
                        leftColumnFocusRequesters.getOrNull(1)?.requestFocus()
                        true
                    }
                    Key.DirectionLeft, Key.DirectionUp -> true
                    else -> false
                }
                1 -> when (event.key) {
                    Key.DirectionLeft -> true
                    Key.DirectionRight -> {
                        onNavigateRightFromFavorites?.invoke()
                        true
                    }
                    Key.DirectionUp -> {
                        leftColumnFocusRequesters.getOrNull(0)?.requestFocus()
                        true
                    }
                    Key.DirectionDown -> {
                        leftColumnFocusRequesters.getOrNull(2)?.requestFocus()
                        true
                    }
                    else -> false
                }
                2 -> when (event.key) {
                    Key.DirectionLeft -> true
                    Key.DirectionRight -> {
                        rightColumnFocusRequesters.getOrNull(2)?.requestFocus()
                        true
                    }
                    Key.DirectionDown -> {
                        leftColumnFocusRequesters.getOrNull(1)?.requestFocus()
                        true
                    }
                    Key.DirectionUp -> {
                        leftColumnFocusRequesters.getOrNull(1)?.requestFocus()
                        true
                    }
                    else -> false
                }
                else -> false
            }
        }

        val rightColumnKeyHandler: (Int, KeyEvent) -> Boolean = rightHandler@{ index, event ->
            if (event.type != KeyEventType.KeyDown) return@rightHandler false
            when (index) {
                0 -> when (event.key) {
                    Key.DirectionLeft -> {
                        leftColumnFocusRequesters.getOrNull(0)?.requestFocus()
                        true
                    }
                    Key.DirectionDown -> {
                        rightColumnFocusRequesters.getOrNull(1)?.requestFocus()
                        true
                    }
                    Key.DirectionRight, Key.DirectionUp -> true
                    else -> false
                }
                1 -> when (event.key) {
                    Key.DirectionLeft -> {
                        onNavigateLeftFromRotate?.invoke()
                        true
                    }
                    Key.DirectionUp -> {
                        rightColumnFocusRequesters.getOrNull(0)?.requestFocus()
                        true
                    }
                    Key.DirectionDown -> {
                        rightColumnFocusRequesters.getOrNull(2)?.requestFocus()
                        true
                    }
                    Key.DirectionRight -> true
                    else -> false
                }
                2 -> when (event.key) {
                    Key.DirectionLeft -> {
                        leftColumnFocusRequesters.getOrNull(2)?.requestFocus()
                        true
                    }
                    Key.DirectionUp -> {
                        rightColumnFocusRequesters.getOrNull(1)?.requestFocus()
                        true
                    }
                    Key.DirectionRight, Key.DirectionDown -> true
                    else -> false
                }
                else -> false
            }
        }

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
            ),
            focusRequesters = leftColumnFocusRequesters,
            onKeyHandler = leftColumnKeyHandler,
            isRemoteMode = isRemoteMode,
            onRemoteModeChange = updateRemoteMode,
            externalForcedIndex = externalLeftFocus,
            onExternalCleared = { externalLeftFocus = null }
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
            ),
            focusRequesters = rightColumnFocusRequesters,
            onKeyHandler = rightColumnKeyHandler,
            isRemoteMode = isRemoteMode,
            onRemoteModeChange = updateRemoteMode,
            externalForcedIndex = externalRightFocus,
            onExternalCleared = { externalRightFocus = null }
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
    buttons: List<ControlButtonData>,
    focusRequesters: List<FocusRequester>,
    onKeyHandler: ((Int, KeyEvent) -> Boolean)? = null,
    isRemoteMode: Boolean,
    onRemoteModeChange: (Boolean) -> Unit,
    externalForcedIndex: Int? = null,
    onExternalCleared: () -> Unit = {}
) {
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
                val forced = externalForcedIndex == index
                val showFocus = isFocused || forced

                IconButton(
                    onClick = if (isRotation) ({}) else button.onClick,
                    modifier = Modifier
                        .size(LayoutConstants.ControlButtonSize)
                        .alpha(alpha)
                        // Make Rotate button focusable for navigation, even though it's disabled
                        .focusable()
                        .focusRequester(focusRequesters[index])
                        .onFocusChanged {
                            isFocused = it.isFocused
                            if (it.isFocused) {
                                onRemoteModeChange(true)
                            } else if (forced) {
                                onExternalCleared()
                            }
                        }
                        .then(
                            if (showFocus) {
                                focusIndicatorModifier(isFocused = true, forceShow = true)
                            } else {
                                Modifier
                            }
                        )
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && isFocused) {
                                DeviceHelper.updateLastInputMethod(event.nativeKeyEvent)
                                onRemoteModeChange(true)
                                if (onKeyHandler?.invoke(index, event) == true) {
                                    return@onKeyEvent true
                                }
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter -> {
                                        if (!isRotation) {
                                            button.onClick()
                                        }
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        if (index > 0) {
                                            focusRequesters[index - 1].requestFocus()
                                        }
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        if (index < buttons.size - 1) {
                                            focusRequesters[index + 1].requestFocus()
                                        }
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
