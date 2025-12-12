package com.rutv.ui.mobile.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.rutv.R
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper

@UnstableApi
@Composable
internal fun ChannelInfoOverlay(
    channelNumber: Int,
    channel: Channel,
    currentProgram: EpgProgram?,
    isArchivePlayback: Boolean,
    isTimeshiftPlayback: Boolean,
    archiveProgram: EpgProgram?,
    onReturnToLive: () -> Unit,
    onShowProgramInfo: (EpgProgram) -> Unit,
    returnToLiveFocusRequester: FocusRequester,
    programInfoFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.8f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.channel_info_format, channelNumber, channel.title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.ruTvColors.textPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Program Name
            val program = if (isArchivePlayback) archiveProgram else currentProgram

            if (program != null) {
                if (isArchivePlayback) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = stringResource(R.string.player_archive_label, ""),
                            tint = MaterialTheme.ruTvColors.gold,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = program.title.truncateForOverlay(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.ruTvColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Text(
                        text = program.title.truncateForOverlay(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.ruTvColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Buttons Row
                ChannelOverlayButtons(
                    primaryLabel = R.string.player_return_to_live,
                    onPrimary = onReturnToLive,
                    showPrimary = isArchivePlayback || isTimeshiftPlayback,
                    secondaryProgram = program,
                    onSecondary = onShowProgramInfo,
                    returnToLiveFocusRequester = returnToLiveFocusRequester,
                    programInfoFocusRequester = programInfoFocusRequester
                )
            }
        }
    }
}

@Composable
private fun ChannelOverlayButtons(
    @StringRes primaryLabel: Int,
    onPrimary: () -> Unit,
    showPrimary: Boolean,
    secondaryProgram: EpgProgram,
    onSecondary: (EpgProgram) -> Unit,
    returnToLiveFocusRequester: FocusRequester,
    programInfoFocusRequester: FocusRequester
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showPrimary) {
                ReturnToLiveButton(
                    onClick = onPrimary,
                    focusRequester = returnToLiveFocusRequester,
                    buttonHeight = CHANNEL_BUTTON_HEIGHT
                )
            }

            ProgramInfoButton(
                program = secondaryProgram,
                buttonHeight = CHANNEL_BUTTON_HEIGHT,
                onShowProgramInfo = onSecondary,
                focusRequester = programInfoFocusRequester
            )
        }
    }
}

@UnstableApi
@Composable
internal fun ReturnToLiveButton(
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    buttonHeight: Dp = 48.dp
) {
    var isFocused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.ruTvColors.gold,
            contentColor = MaterialTheme.ruTvColors.darkBackground
        ),
        modifier = Modifier
            .height(buttonHeight)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .then(focusIndicatorModifier(isFocused))
            .onKeyEvent { event ->
                if (isFocused && event.type == KeyEventType.KeyDown && DeviceHelper.isRemoteInputActive()) {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            onClick()
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        Text(text = stringResource(R.string.player_return_to_live))
    }
}

@UnstableApi
@Composable
internal fun ProgramInfoButton(
    program: EpgProgram,
    buttonHeight: Dp,
    onShowProgramInfo: (EpgProgram) -> Unit,
    focusRequester: FocusRequester,
    containerColor: Color = MaterialTheme.ruTvColors.darkBackground,
    iconSizeMultiplier: Float = 0.75f
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(buttonHeight),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = { onShowProgramInfo(program) },
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.ruTvColors.gold,
                containerColor = containerColor
            ),
            modifier = Modifier
                .size(buttonHeight)
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .then(focusIndicatorModifier(isFocused))
                .onKeyEvent { event ->
                    if (isFocused && event.type == KeyEventType.KeyDown && DeviceHelper.isRemoteInputActive()) {
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter -> {
                                onShowProgramInfo(program)
                                true
                            }
                            else -> false
                        }
                    } else false
                }
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(R.string.player_program_info),
                modifier = Modifier.size(buttonHeight * iconSizeMultiplier)
            )
        }
    }
}

internal fun String.truncateForOverlay(maxChars: Int = MAX_PROGRAM_TITLE_CHARS): String {
    if (length <= maxChars) return this
    if (maxChars <= 1) return "…"
    val trimmed = take(maxChars - 1).trimEnd()
    return if (trimmed.isEmpty()) "…" else "$trimmed…"
}

private val CHANNEL_BUTTON_HEIGHT = 48.dp
private const val MAX_PROGRAM_TITLE_CHARS = 48
