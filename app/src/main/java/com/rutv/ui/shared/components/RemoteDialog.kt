package com.rutv.ui.shared.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper

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
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    usePlatformDefaultWidth: Boolean = true,
    confirmButtonFocusRequester: FocusRequester? = null,
    dismissButtonFocusRequester: FocusRequester? = null,
    textFocusRequester: FocusRequester? = null,
    autoFocusConfirm: Boolean = true,
    onConfirm: (() -> Unit)? = null
) {
    val isRemoteMode = DeviceHelper.isRemoteInputActive()

    // Focus requesters for buttons (use provided or create new)
    val confirmFocus = confirmButtonFocusRequester ?: remember { FocusRequester() }
    val dismissFocus = dismissButtonFocusRequester ?: remember { FocusRequester() }

    // Request focus on confirm button when dialog opens in remote mode
    LaunchedEffect(isRemoteMode, autoFocusConfirm) {
        if (isRemoteMode && autoFocusConfirm) {
            repeat(4) {
                if (confirmFocus.requestFocusSafely()) return@LaunchedEffect
                withFrameNanos { }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirmButton = {
            // Use hasFocus (not isFocused) so focus visuals work even if the child composable
            // (e.g., a TextButton) is the actual focused node.
            var hasFocus by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .focusable()
                    .focusRequester(confirmFocus)
                    .onFocusChanged { hasFocus = it.hasFocus }
                    .then(focusIndicatorModifier(isFocused = hasFocus))
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && hasFocus) {
                            val hasSecondary = dismissButton != null
                            when (event.key) {
                                Key.DirectionCenter, Key.Enter -> {
                                    // Trigger confirm action if provided
                                    if (onConfirm != null) {
                                        onConfirm()
                                        true
                                    } else {
                                        false // Let the button handle it if no explicit action provided
                                    }
                                }
                                Key.Back -> {
                                    onDismissRequest()
                                    true
                                }
                                else -> false
                            }
                                || DialogFocusPolicy.nextTarget(
                                    from = DialogFocusPolicy.Target.PrimaryAction,
                                    key = event.key,
                                    hasSecondaryAction = hasSecondary
                                )?.let { target ->
                                    when (target) {
                                        DialogFocusPolicy.Target.TextField -> {
                                            textFocusRequester?.requestFocusSafely() == true
                                        }

                                        DialogFocusPolicy.Target.SecondaryAction -> {
                                            hasSecondary && dismissFocus.requestFocusSafely()
                                        }

                                        DialogFocusPolicy.Target.PrimaryAction -> true
                                    }
                                } ?: false
                        } else false
                    }
            ) {
                confirmButton()
            }
        },
        dismissButton = dismissButton?.let {
            {
                // Use hasFocus (not isFocused) so wrapper visuals/key handling work even if
                // the inner dismiss button is focusable and receives focus.
                var hasFocus by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .focusable()
                        .focusRequester(dismissFocus)
                        .onFocusChanged { hasFocus = it.hasFocus }
                        .then(focusIndicatorModifier(isFocused = hasFocus))
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && hasFocus) {
                                val hasSecondary = true
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter -> {
                                        onDismissRequest()
                                        true
                                    }
                                    Key.Back -> {
                                        onDismissRequest()
                                        true
                                    }
                                    else -> false
                                }
                                    || DialogFocusPolicy.nextTarget(
                                        from = DialogFocusPolicy.Target.SecondaryAction,
                                        key = event.key,
                                        hasSecondaryAction = hasSecondary
                                    )?.let { target ->
                                        when (target) {
                                            DialogFocusPolicy.Target.TextField -> {
                                                textFocusRequester?.requestFocusSafely() == true
                                            }

                                            DialogFocusPolicy.Target.PrimaryAction -> {
                                                confirmFocus.requestFocusSafely()
                                            }

                                            DialogFocusPolicy.Target.SecondaryAction -> true
                                        }
                                    } ?: false
                            } else false
                        }
                ) {
                    dismissButton()
                }
            }
        },
        containerColor = containerColor,
        shape = shape,
        properties = DialogProperties(usePlatformDefaultWidth = usePlatformDefaultWidth),
        modifier = modifier
    )
}

