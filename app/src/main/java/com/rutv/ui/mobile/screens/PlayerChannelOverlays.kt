package com.rutv.ui.mobile.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rutv.R
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.shared.components.requestFocusSafely
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper
import coil.compose.AsyncImage

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
    selectedAudioLanguage: String?,
    onOpenAudioLanguages: () -> Unit,
    onNavigateDown: () -> Unit,
    returnToLiveFocusRequester: FocusRequester,
    programInfoFocusRequester: FocusRequester,
    audioLanguageFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.8f)
        )
    ) {
        val program = if (isArchivePlayback) archiveProgram else currentProgram
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val channelLogoPlaceholder = painterResource(R.drawable.ic_channel_placeholder)
                AsyncImage(
                    model = channel.logo.takeIf { it.isNotBlank() },
                    contentDescription = stringResource(R.string.cd_channel_logo),
                    placeholder = channelLogoPlaceholder,
                    error = channelLogoPlaceholder,
                    fallback = channelLogoPlaceholder,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(OVERLAY_CHANNEL_LOGO_SIZE)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.channel_info_format, channelNumber, channel.title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.ruTvColors.textPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

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
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            ChannelOverlayButtons(
                onPrimary = onReturnToLive,
                showPrimary = isArchivePlayback || isTimeshiftPlayback,
                secondaryProgram = program,
                onSecondary = onShowProgramInfo,
                selectedAudioLanguage = selectedAudioLanguage,
                onOpenAudioLanguages = onOpenAudioLanguages,
                onNavigateDown = onNavigateDown,
                returnToLiveFocusRequester = returnToLiveFocusRequester,
                programInfoFocusRequester = programInfoFocusRequester,
                audioLanguageFocusRequester = audioLanguageFocusRequester
            )
        }
    }
}

@Composable
private fun ChannelOverlayButtons(
    onPrimary: () -> Unit,
    showPrimary: Boolean,
    secondaryProgram: EpgProgram?,
    onSecondary: (EpgProgram) -> Unit,
    selectedAudioLanguage: String?,
    onOpenAudioLanguages: () -> Unit,
    onNavigateDown: () -> Unit,
    returnToLiveFocusRequester: FocusRequester,
    programInfoFocusRequester: FocusRequester,
    audioLanguageFocusRequester: FocusRequester
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showPrimary) {
                ReturnToLiveButton(
                    onClick = onPrimary,
                    focusRequester = returnToLiveFocusRequester,
                    onNavigateDown = onNavigateDown,
                    onNavigateRight = when {
                        secondaryProgram != null -> ({ programInfoFocusRequester.requestFocusSafely() })
                        else -> ({ audioLanguageFocusRequester.requestFocusSafely() })
                    },
                    buttonHeight = CHANNEL_BUTTON_HEIGHT
                )
            }

            secondaryProgram?.let { program ->
                ProgramInfoButton(
                    program = program,
                    buttonHeight = CHANNEL_BUTTON_HEIGHT,
                    onShowProgramInfo = onSecondary,
                    focusRequester = programInfoFocusRequester,
                    onNavigateDown = onNavigateDown,
                    onNavigateLeft = if (showPrimary) {
                        { returnToLiveFocusRequester.requestFocusSafely() }
                    } else null,
                    onNavigateRight = { audioLanguageFocusRequester.requestFocusSafely() }
                )
            }

            AudioLanguageControl(
                language = selectedAudioLanguage,
                onClick = onOpenAudioLanguages,
                focusRequester = audioLanguageFocusRequester,
                onNavigateDown = onNavigateDown,
                onNavigateLeft = when {
                    secondaryProgram != null -> ({ programInfoFocusRequester.requestFocusSafely() })
                    showPrimary -> ({ returnToLiveFocusRequester.requestFocusSafely() })
                    else -> null
                }
            )
        }
    }
}

@Composable
internal fun ReturnToLiveButton(
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onNavigateDown: (() -> Unit)? = null,
    onNavigateRight: (() -> Unit)? = null,
    buttonHeight: Dp = 48.dp
) {
    var isFocused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
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
                        Key.DirectionDown -> {
                            onNavigateDown?.invoke()
                            true
                        }
                        Key.DirectionRight -> {
                            onNavigateRight?.invoke()
                            true
                        }
                        Key.DirectionLeft, Key.DirectionUp -> true
                        else -> false
                    }
                } else false
            }
    ) {
        Text(
            text = stringResource(R.string.player_return_to_live),
            maxLines = 1
        )
    }
}

@Composable
internal fun ProgramInfoButton(
    program: EpgProgram,
    buttonHeight: Dp,
    onShowProgramInfo: (EpgProgram) -> Unit,
    focusRequester: FocusRequester,
    onNavigateDown: (() -> Unit)? = null,
    onNavigateLeft: (() -> Unit)? = null,
    onNavigateRight: (() -> Unit)? = null,
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
                            Key.DirectionDown -> {
                                onNavigateDown?.invoke()
                                true
                            }
                            Key.DirectionLeft -> {
                                if (onNavigateLeft != null) {
                                    onNavigateLeft()
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                onNavigateRight?.invoke()
                                true
                            }
                            Key.DirectionUp -> true
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

@Composable
private fun AudioLanguageControl(
    language: String?,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onNavigateDown: () -> Unit,
    onNavigateLeft: (() -> Unit)?
) {
    val languageLabel = language
    val accessibilityLabel = languageLabel
        ?: stringResource(R.string.player_audio_language_unknown)

    var isFocused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.ruTvColors.darkBackground,
            contentColor = MaterialTheme.ruTvColors.gold
        ),
        modifier = Modifier
            .height(CHANNEL_BUTTON_HEIGHT)
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
                        Key.DirectionDown -> {
                            onNavigateDown()
                            true
                        }
                        Key.DirectionLeft -> {
                            onNavigateLeft?.invoke()
                            true
                        }
                        Key.DirectionRight, Key.DirectionUp -> true
                        else -> false
                    }
                } else false
            }
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = stringResource(R.string.cd_audio_language_button, accessibilityLabel),
            modifier = Modifier.size(20.dp)
        )
        languageLabel?.let { label ->
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 48.dp)
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
private val OVERLAY_CHANNEL_LOGO_SIZE = 64.dp
private const val MAX_PROGRAM_TITLE_CHARS = 48
