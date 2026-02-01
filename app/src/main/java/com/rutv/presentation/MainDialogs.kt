package com.rutv.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rutv.R
import com.rutv.ui.shared.components.RemoteDialog
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.shared.components.remoteDialogTextFieldNavigation
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

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
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val windowInfo = LocalWindowInfo.current
    val isTextFieldFocused = remember { mutableStateOf(false) }
    val imeWasVisible = remember { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        withTimeoutOrNull(800) {
            snapshotFlow { windowInfo.isWindowFocused }
                .filter { it }
                .first()
        }
        // Let the dialog settle before requesting focus/IME.
        withFrameNanos { }
        var attempts = 0
        while (attempts < 6 && !isTextFieldFocused.value) {
            textFieldFocus.requestFocus()
            withFrameNanos { }
            attempts++
        }
    }

    LaunchedEffect(density, imeInsets) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .collect { visible ->
                if (visible) {
                    imeWasVisible.value = true
                } else if (imeWasVisible.value && isTextFieldFocused.value) {
                    confirmButtonFocus.requestFocus()
                }
            }
    }

    LaunchedEffect(isTextFieldFocused.value) {
        if (isTextFieldFocused.value) {
            keyboardController?.show()
        }
    }

    RemoteDialog(
        autoFocusConfirm = false,
        onDismissRequest = onDismiss,
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
        onConfirm = onConfirm,
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
                        textFieldFocus.requestFocus()
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
                        isTextFieldFocused.value = state.hasFocus
                    }
                    .remoteDialogTextFieldNavigation(
                        enabled = DeviceHelper.isRemoteInputActive(),
                        primaryActionFocusRequester = confirmButtonFocus,
                        onBack = onDismiss
                    ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        confirmButtonFocus.requestFocus()
                    }
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
                onClick = onConfirm,
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
                onClick = onDismiss,
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

    LaunchedEffect(show, groups, selectedGroup) {
        if (!show) return@LaunchedEffect
        withFrameNanos { }
        when {
            groups.isNotEmpty() -> groupFocusRequesters.getOrNull(initialIndex)?.requestFocus()
            else -> resetFocus.requestFocus()
        }
    }

    RemoteDialog(
        onDismissRequest = onDismiss,
        autoFocusConfirm = false,
        confirmButtonFocusRequester = resetFocus,
        dismissButtonFocusRequester = cancelFocus,
        textFocusRequester = groupFocusRequesters.getOrNull(initialIndex),
        containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
        onConfirm = {
            onReset()
            onDismiss()
        },
        title = {
            Text(
                text = stringResource(R.string.dialog_title_channel_groups),
                color = MaterialTheme.ruTvColors.gold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.dialog_message_no_channel_groups),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.ruTvColors.textPrimary
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
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
                                .then(focusIndicatorModifier(isFocused = isFocused)),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (isSelected) {
                                    MaterialTheme.ruTvColors.gold
                                } else {
                                    MaterialTheme.ruTvColors.textPrimary
                                }
                            )
                        ) {
                            Text(text = group)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onReset()
                onDismiss()
            }, modifier = Modifier.focusable(false)) {
                Text(
                    text = stringResource(R.string.button_reset),
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
