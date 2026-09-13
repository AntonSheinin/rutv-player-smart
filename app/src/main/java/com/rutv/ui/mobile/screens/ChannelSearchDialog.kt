package com.rutv.ui.mobile.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.rutv.R
import com.rutv.ui.shared.components.*
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper

@Composable
internal fun ChannelSearchDialog(
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val searchFieldFocusRequester = remember { FocusRequester() }
    val okButtonFocusRequester = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    val input = rememberDialogInput()
    ChannelDialogFocus(input, searchFieldFocusRequester, okButtonFocusRequester, fieldFocused)
    val dismiss = { input.close(onDismiss) }
    val confirm = {
        if (input.idle) {
            if (searchText.isBlank()) input.correct(searchFieldFocusRequester)
            else input.close(onConfirm)
        }
    }
    RemoteDialog(
        onDismissRequest = dismiss,
        containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
        title = {
            Text(
                text = stringResource(R.string.dialog_title_search_channel),
                color = MaterialTheme.ruTvColors.gold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        confirmButtonFocusRequester = okButtonFocusRequester,
        textFocusRequester = searchFieldFocusRequester,
        autoFocusConfirm = false,
        onConfirm = confirm,
        text = {
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                label = { Text(stringResource(R.string.hint_search_channel)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        confirm()
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFieldFocusRequester)
                    .onFocusChanged { state ->
                        fieldFocused = state.hasFocus
                    }
                    .onKeyEvent { event ->
                        if (!DeviceHelper.isRemoteInputActive()) return@onKeyEvent false
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            // Requirement: DPAD DOWN moves focus to OK button.
                            Key.DirectionDown -> {
                                okButtonFocusRequester.requestFocusSafely()
                                true
                            }
                            else -> false
                        }
                    }
                    .remoteDialogTextFieldNavigation(
                        enabled = DeviceHelper.isRemoteInputActive(),
                        primaryActionFocusRequester = okButtonFocusRequester,
                        onBack = dismiss
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
                // Keep focus on RemoteDialog's wrapper (gold border) for consistency
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
                // Keep focus on RemoteDialog's wrapper (gold border) for consistency
                modifier = Modifier.focusable(false)
            ) {
                Text(
                    text = stringResource(R.string.button_cancel),
                    color = MaterialTheme.ruTvColors.textPrimary
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.border(
            2.dp,
            MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f),
            RoundedCornerShape(16.dp)
        )
    )
}
