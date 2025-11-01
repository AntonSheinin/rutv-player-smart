package com.videoplayer.ui.shared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoplayer.R
import com.videoplayer.ui.theme.ruTvColors
import com.videoplayer.util.DeviceHelper

/**
 * Virtual keyboard for remote control text input
 * Displays a grid of keys that can be navigated with D-pad
 */
@Composable
fun VirtualKeyboard(
    onCharSelected: (Char) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showNumbers: Boolean = true,
    showSpecialChars: Boolean = false
) {
    val isRemoteMode = DeviceHelper.isRemoteInputActive()

    // Keyboard layout - QWERTY with numbers row
    val numberRow = if (showNumbers) "1234567890" else ""
    val topRow = "QWERTYUIOP"
    val middleRow = "ASDFGHJKL"
    val bottomRow = "ZXCVBNM"

    val allRows = remember {
        buildList {
            if (numberRow.isNotEmpty()) add(numberRow)
            add(topRow)
            add(middleRow)
            add(bottomRow)
            if (showSpecialChars) {
                add(".,!?")
                add("@#$%")
            }
        }
    }

    // Focus management - create focus requesters for all keys
    val focusRequesters = remember(allRows.size) {
        allRows.map { row ->
            List(row.length) { FocusRequester() }
        }
    }

    // Special buttons focus requesters
    val backspaceFocus = remember { FocusRequester() }
    val enterFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }

    // Request focus on first key when keyboard opens
    LaunchedEffect(isRemoteMode) {
        if (isRemoteMode && allRows.isNotEmpty() && focusRequesters.isNotEmpty()) {
            focusRequesters[0][0].requestFocus()
        }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Keyboard rows
            allRows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEachIndexed { charIndex, char ->
                        KeyboardKey(
                            char = char,
                            onClick = { onCharSelected(char) },
                            focusRequester = focusRequesters[rowIndex][charIndex],
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Control buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Backspace
                KeyboardKey(
                    char = '⌫',
                    label = stringResource(R.string.keyboard_backspace),
                    onClick = onBackspace,
                    focusRequester = backspaceFocus,
                    modifier = Modifier.weight(1.5f)
                )

                Spacer(modifier = Modifier.weight(1f))

                // Enter
                KeyboardKey(
                    char = '↵',
                    label = stringResource(R.string.keyboard_enter),
                    onClick = onEnter,
                    focusRequester = enterFocus,
                    modifier = Modifier.weight(1.5f)
                )

                Spacer(modifier = Modifier.weight(1f))

                // Close
                KeyboardKey(
                    char = '✕',
                    label = stringResource(R.string.keyboard_close),
                    onClick = onClose,
                    focusRequester = closeFocus,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun KeyboardKey(
    char: Char,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val isRemoteMode = DeviceHelper.isRemoteInputActive()

    Button(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .focusable(enabled = isRemoteMode)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .then(focusIndicatorModifier(isFocused = isFocused))
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && isFocused && isRemoteMode) {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            onClick()
                            true
                        }
                        else -> false
                    }
                } else false
            },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isFocused && isRemoteMode) {
                MaterialTheme.ruTvColors.gold
            } else {
                MaterialTheme.ruTvColors.cardBackground
            },
            contentColor = if (isFocused && isRemoteMode) {
                MaterialTheme.ruTvColors.darkBackground
            } else {
                MaterialTheme.ruTvColors.textPrimary
            }
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label ?: char.toString(),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = if (char.isLetterOrDigit()) 18.sp else 16.sp,
                fontWeight = if (isFocused && isRemoteMode) FontWeight.Bold else FontWeight.Normal
            )
        )
    }
}

