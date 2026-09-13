package com.rutv.presentation

import com.rutv.ui.shared.components.rememberDialogInput
import com.rutv.ui.shared.components.ChannelDialogFocus
import com.rutv.ui.shared.components.rememberPinDialogSubmission
import com.rutv.ui.shared.components.pinFailureText
import com.rutv.presentation.PinOperation
import com.rutv.presentation.PinRequest
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rutv.R
import com.rutv.presentation.main.ParentalPinPrompt
import com.rutv.presentation.main.ParentalPinPromptReason
import com.rutv.ui.shared.components.RemoteDialog
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.shared.components.requestFocusSafely
import com.rutv.ui.shared.components.remoteDialogTextFieldNavigation
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper
import kotlinx.coroutines.flow.filter

@Composable
internal fun NoPlaylistDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onExitApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.dialog_title_no_playlist),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.ruTvColors.gold
            )
        },
        text = {
            Text(
                text = stringResource(R.string.dialog_message_no_playlist),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.ruTvColors.textPrimary
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(
                    text = stringResource(R.string.button_open_settings),
                    color = MaterialTheme.ruTvColors.gold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onExitApp) {
                Text(
                    text = stringResource(R.string.button_exit),
                    color = MaterialTheme.ruTvColors.textPrimary
                )
            }
        },
        containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.border(
            2.dp,
            MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f),
            RoundedCornerShape(16.dp)
        )
    )
}

@Composable
internal fun GoToChannelDialog(
    show: Boolean,
    channelInput: String,
    channelCount: Int,
    onChannelInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!show) return
    val confirmButtonFocus = remember { FocusRequester() }
    val textFieldFocus = remember { FocusRequester() }
    val input = rememberDialogInput()
    var fieldFocused by remember { mutableStateOf(false) }
    ChannelDialogFocus(input, textFieldFocus, confirmButtonFocus, fieldFocused)
    val confirm = { if (input.idle) input.close(onConfirm) }
    val dismiss = { input.close(onDismiss) }

    RemoteDialog(
        autoFocusConfirm = false,
        onDismissRequest = dismiss,
        containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
        title = {
            Text(
                text = stringResource(R.string.dialog_title_go_to_channel),
                color = MaterialTheme.ruTvColors.gold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        confirmButtonFocusRequester = confirmButtonFocus,
        textFocusRequester = textFieldFocus,
        onConfirm = confirm,
        modifier = modifier
            .border(
                2.dp,
                MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f),
                RoundedCornerShape(16.dp)
            )
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    val number = when (event.key) {
                        Key.Zero, Key.NumPad0 -> "0"
                        Key.One, Key.NumPad1 -> "1"
                        Key.Two, Key.NumPad2 -> "2"
                        Key.Three, Key.NumPad3 -> "3"
                        Key.Four, Key.NumPad4 -> "4"
                        Key.Five, Key.NumPad5 -> "5"
                        Key.Six, Key.NumPad6 -> "6"
                        Key.Seven, Key.NumPad7 -> "7"
                        Key.Eight, Key.NumPad8 -> "8"
                        Key.Nine, Key.NumPad9 -> "9"
                        else -> null
                    }
                    if (number != null) {
                        if (channelInput.length < 4) {
                            onChannelInputChange(channelInput + number)
                        }
                        textFieldFocus.requestFocusSafely()
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            },
        text = {
            OutlinedTextField(
                value = channelInput,
                onValueChange = { new ->
                    onChannelInputChange(new.filter { it.isDigit() }.take(4))
                },
                label = { Text(stringResource(R.string.hint_channel_number, channelCount)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(textFieldFocus)
                    .onFocusChanged { state ->
                        fieldFocused = state.hasFocus
                    }
                    .remoteDialogTextFieldNavigation(
                        enabled = DeviceHelper.isRemoteInputActive(),
                        primaryActionFocusRequester = confirmButtonFocus,
                        onBack = dismiss
                    ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { confirm() }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.ruTvColors.gold,
                    unfocusedBorderColor = MaterialTheme.ruTvColors.textDisabled,
                    focusedTextColor = MaterialTheme.ruTvColors.textPrimary,
                    unfocusedTextColor = MaterialTheme.ruTvColors.textPrimary,
                    focusedLabelColor = MaterialTheme.ruTvColors.gold,
                    unfocusedLabelColor = MaterialTheme.ruTvColors.textSecondary
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = confirm,
                modifier = Modifier.focusable(false)
            ) {
                Text(
                    text = stringResource(R.string.button_ok),
                    color = MaterialTheme.ruTvColors.gold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = dismiss,
                modifier = Modifier.focusable(false)
            ) {
                Text(
                    text = stringResource(R.string.button_cancel),
                    color = MaterialTheme.ruTvColors.textPrimary
                )
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
internal fun ChannelGroupDialog(
    show: Boolean,
    groups: List<String>,
    selectedGroup: String?,
    onSelectGroup: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!show) return

    val groupFocusRequesters = remember(groups) { List(groups.size) { FocusRequester() } }
    val resetFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val initialIndex = remember(groups, selectedGroup) {
        groups.indexOfFirst { it == selectedGroup }.takeIf { it >= 0 } ?: 0
    }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val dialogWidth = remember(screenWidthDp) {
        (screenWidthDp * 0.70f).coerceIn(280.dp, 420.dp)
    }
    val dialogMaxHeight = remember(screenHeightDp) {
        (screenHeightDp - 32.dp).coerceAtLeast(240.dp)
    }
    val dialogModifier = modifier
        .width(dialogWidth)
        .heightIn(max = dialogMaxHeight)
        .border(
            2.dp,
            MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f),
            RoundedCornerShape(16.dp)
        )

    LaunchedEffect(show, groups, selectedGroup) {
        if (!show) return@LaunchedEffect
        withFrameNanos { }
        when {
            groups.isNotEmpty() -> groupFocusRequesters.getOrNull(initialIndex)?.requestFocusSafely() == true
            else -> resetFocus.requestFocusSafely()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = dialogModifier,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = dialogMaxHeight)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.dialog_title_channel_groups),
                        color = MaterialTheme.ruTvColors.gold,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close_playlist),
                            tint = MaterialTheme.ruTvColors.textPrimary
                        )
                    }
                }

                if (groups.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dialog_message_no_channel_groups),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.ruTvColors.textPrimary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        itemsIndexed(groups) { index, group ->
                            val isSelected = group == selectedGroup
                            var isFocused by remember { mutableStateOf(false) }
                            TextButton(
                                onClick = {
                                    onSelectGroup(group)
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .focusRequester(groupFocusRequesters[index])
                                    .onFocusChanged { state -> isFocused = state.isFocused }
                                    .focusable()
                                    .onKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                        when (event.key) {
                                            Key.DirectionDown -> {
                                                if (index < groups.lastIndex) {
                                                    groupFocusRequesters[index + 1].requestFocusSafely()
                                                } else {
                                                    resetFocus.requestFocusSafely()
                                                }
                                                true
                                            }
                                            Key.DirectionUp -> {
                                                if (index > 0) {
                                                    groupFocusRequesters[index - 1].requestFocusSafely()
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    .then(focusIndicatorModifier(isFocused = isFocused)),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (isSelected) {
                                        MaterialTheme.ruTvColors.gold
                                    } else {
                                        MaterialTheme.ruTvColors.textPrimary
                                    }
                                )
                            ) {
                                Text(
                                    text = group,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            onReset()
                            onDismiss()
                        },
                        modifier = Modifier
                            .focusRequester(resetFocus)
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        groupFocusRequesters.lastOrNull()?.requestFocusSafely() == true
                                    }
                                    Key.DirectionRight -> {
                                        cancelFocus.requestFocusSafely()
                                    }
                                    Key.Back -> {
                                        onDismiss()
                                        true
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        Text(
                            text = stringResource(R.string.button_reset),
                            color = MaterialTheme.ruTvColors.gold
                        )
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .focusRequester(cancelFocus)
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        groupFocusRequesters.lastOrNull()?.requestFocusSafely() == true
                                    }
                                    Key.DirectionLeft -> {
                                        resetFocus.requestFocusSafely()
                                    }
                                    Key.Back -> {
                                        onDismiss()
                                        true
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        Text(
                            text = stringResource(R.string.button_cancel),
                            color = MaterialTheme.ruTvColors.textPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ParentalPinDialog(
    prompt: ParentalPinPrompt?,
    operation: PinOperation,
    onSubmit: (PinRequest, String) -> Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (prompt == null) return

    var pin by remember(prompt.session) { mutableStateOf("") }
    val inputFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val submission = rememberPinDialogSubmission(operation, onDismiss, { inputFocus }, prompt.session)
    val failure = operation.failure.takeIf { operation.request == submission.request }
    val failureText = pinFailureText(failure)
    val dismiss = { submission.input.close(onDismiss) }


    LaunchedEffect(prompt.session) {
        inputFocus.requestFocusSafely()
    }

    val title = when (prompt.reason) {
        ParentalPinPromptReason.PlayChannel -> stringResource(R.string.parental_unlock_channel_title)
        ParentalPinPromptReason.OpenEpg -> stringResource(R.string.parental_unlock_epg_title)
        ParentalPinPromptReason.ToggleLock -> stringResource(R.string.parental_toggle_lock_title)
    }
    val message = when {
        prompt.reason == ParentalPinPromptReason.ToggleLock && !prompt.channelIsLocked ->
            stringResource(R.string.parental_pin_prompt_lock_message, prompt.channelTitle)
        prompt.reason == ParentalPinPromptReason.ToggleLock ->
            stringResource(R.string.parental_pin_prompt_unlock_message, prompt.channelTitle)
        else -> stringResource(R.string.parental_pin_prompt_message, prompt.channelTitle)
    }

    fun commit() {
        if (pin.length == 4) {
            submission.submit(operation, onDismiss = onDismiss) { onSubmit(it, pin) }
        } else {
            submission.input.correct(inputFocus)
        }
    }

    RemoteDialog(
        onDismissRequest = dismiss,
        autoFocusConfirm = false,
        confirmButtonFocusRequester = confirmFocus,
        textFocusRequester = inputFocus,
        onConfirm = { commit() },
        containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
        title = {
            Text(
                text = title,
                color = MaterialTheme.ruTvColors.gold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    text = message,
                    color = MaterialTheme.ruTvColors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = pin,
                    readOnly = operation.busy,
                    onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.parental_pin_current)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { commit() }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .focusRequester(inputFocus)
                        .remoteDialogTextFieldNavigation(
                            enabled = DeviceHelper.isRemoteInputActive(),
                            primaryActionFocusRequester = confirmFocus,
                            onBack = dismiss
                        ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.ruTvColors.gold,
                        unfocusedBorderColor = MaterialTheme.ruTvColors.textDisabled,
                        focusedTextColor = MaterialTheme.ruTvColors.textPrimary,
                        unfocusedTextColor = MaterialTheme.ruTvColors.textPrimary
                    ),
                    supportingText = {
                        Text(
                            text = if (failureText != null) {
                                failureText
                            } else {
                                stringResource(R.string.parental_pin_hint)
                            },
                            color = if (failureText != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.ruTvColors.textSecondary
                            }
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { commit() }, modifier = Modifier.focusable(false)) {
                Text(
                    text = stringResource(R.string.button_ok),
                    color = MaterialTheme.ruTvColors.gold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, modifier = Modifier.focusable(false)) {
                Text(
                    text = stringResource(R.string.button_cancel),
                    color = MaterialTheme.ruTvColors.textPrimary
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.border(
            2.dp,
            MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f),
            RoundedCornerShape(16.dp)
        )
    )
}

@Composable
internal fun ParentalPinSetupDialog(
    show: Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!show) return
    RemoteDialog(
        onDismissRequest = onDismiss,
        onConfirm = onOpenSettings,
        containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
        title = {
            Text(
                text = stringResource(R.string.settings_parental_controls),
                color = MaterialTheme.ruTvColors.gold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = stringResource(R.string.parental_pin_setup_required),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.ruTvColors.textPrimary
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings, modifier = Modifier.focusable(false)) {
                Text(
                    text = stringResource(R.string.button_open_settings),
                    color = MaterialTheme.ruTvColors.gold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusable(false)) {
                Text(
                    text = stringResource(R.string.button_cancel),
                    color = MaterialTheme.ruTvColors.textPrimary
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.border(
            2.dp,
            MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f),
            RoundedCornerShape(16.dp)
        )
    )
}

@Composable
internal fun CloseAppDialog(
    show: Boolean,
    onConfirmExit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!show) return
    RemoteDialog(
        onDismissRequest = onDismiss,
        onConfirm = onConfirmExit,
        containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
        title = {
            Text(
                text = stringResource(R.string.dialog_title_close_app),
                color = MaterialTheme.ruTvColors.gold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = stringResource(R.string.dialog_message_close_app),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.ruTvColors.textPrimary
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirmExit, modifier = Modifier.focusable(false)) {
                Text(
                    text = stringResource(R.string.button_exit),
                    color = MaterialTheme.ruTvColors.gold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusable(false)) {
                Text(
                    text = stringResource(R.string.button_cancel),
                    color = MaterialTheme.ruTvColors.textPrimary
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.border(
            2.dp,
            MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f),
            RoundedCornerShape(16.dp)
        )
    )
}
