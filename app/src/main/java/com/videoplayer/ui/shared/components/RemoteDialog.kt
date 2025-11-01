package com.videoplayer.ui.shared.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.videoplayer.ui.theme.ruTvColors
import com.videoplayer.util.DeviceHelper

/**
 * Enhanced AlertDialog with remote control focus support
 * All buttons are focusable and navigable with D-pad
 */
@Composable
fun RemoteDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
    shape: RoundedCornerShape = RoundedCornerShape(16.dp)
) {
    val isRemoteMode = DeviceHelper.isRemoteInputActive()

    // Focus requesters for buttons
    val confirmFocus = remember { FocusRequester() }
    val dismissFocus = remember { FocusRequester() }

    // Request focus on confirm button when dialog opens in remote mode
    LaunchedEffect(isRemoteMode) {
        if (isRemoteMode) {
            confirmFocus.requestFocus()
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirmButton = {
            var isFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .focusable(enabled = isRemoteMode)
                    .focusRequester(confirmFocus)
                    .onFocusChanged { isFocused = it.isFocused }
                    .then(focusIndicatorModifier(isFocused = isFocused))
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && isFocused && isRemoteMode) {
                            when (event.key) {
                                Key.DirectionCenter, Key.Enter -> {
                                    // Trigger confirm button click
                                    // The button composable will handle onClick
                                    false // Let the button handle it
                                }
                                Key.DirectionLeft -> {
                                    // Navigate to dismiss button if available
                                    dismissButton?.let { dismissFocus.requestFocus() }
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
            ) {
                confirmButton()
            }
        },
        dismissButton = dismissButton?.let {
            {
                var isFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .focusable(enabled = isRemoteMode)
                        .focusRequester(dismissFocus)
                        .onFocusChanged { isFocused = it.isFocused }
                        .then(focusIndicatorModifier(isFocused = isFocused))
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && isFocused && isRemoteMode) {
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter -> {
                                        onDismissRequest()
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        // Navigate to confirm button
                                        confirmFocus.requestFocus()
                                        true
                                    }
                                    Key.Back -> {
                                        onDismissRequest()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                ) {
                    dismissButton()
                }
            }
        },
        containerColor = containerColor,
        shape = shape,
        modifier = modifier
    )
}

