package com.rutv.ui.shared.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Small helpers to keep DPAD/remote button activation consistent across the app.
 *
 * These helpers are intentionally minimal and composable; they don't impose styling.
 */
fun Modifier.remoteActivate(
    enabled: Boolean = true,
    onActivate: () -> Unit
): Modifier = onKeyEvent { event ->
    if (!enabled) return@onKeyEvent false
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    when (event.key) {
        Key.DirectionCenter, Key.Enter -> {
            onActivate()
            true
        }
        else -> false
    }
}

fun Modifier.remoteBack(
    enabled: Boolean = true,
    onBack: () -> Unit
): Modifier = onKeyEvent { event ->
    if (!enabled) return@onKeyEvent false
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    when (event.key) {
        Key.Back -> {
            onBack()
            true
        }
        else -> false
    }
}


