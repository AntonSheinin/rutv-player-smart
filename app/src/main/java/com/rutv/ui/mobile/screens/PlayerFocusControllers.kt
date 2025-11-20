package com.rutv.ui.mobile.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.rutv.util.DeviceHelper

internal enum class CustomControlFocusTarget {
    Favorites,
    Rotate
}

@Composable
internal fun rememberCustomControlFocusCoordinator(): CustomControlFocusCoordinator {
    var pendingTarget by remember { mutableStateOf<CustomControlFocusTarget?>(null) }
    return remember {
        CustomControlFocusCoordinator(
            getPendingTarget = { pendingTarget },
            setPendingTarget = { pendingTarget = it }
        )
    }
}

internal class CustomControlFocusCoordinator(
    private val getPendingTarget: () -> CustomControlFocusTarget?,
    private val setPendingTarget: (CustomControlFocusTarget?) -> Unit
) {

    fun requestFocus(
        target: CustomControlFocusTarget,
        leftRequesters: List<FocusRequester>?,
        rightRequesters: List<FocusRequester>?
    ) {
        // Mark remote interaction to ensure focus visuals use remote mode immediately.
        DeviceHelper.markRemoteInteraction()
        val requester = resolveRequester(target, leftRequesters, rightRequesters)
        if (requester != null) {
            requester.requestFocus()
            setPendingTarget(null)
        } else {
            setPendingTarget(target)
        }
    }

    @Composable
    fun Bind(leftRequesters: List<FocusRequester>?, rightRequesters: List<FocusRequester>?) {
        val pending = getPendingTarget()
        LaunchedEffect(pending, leftRequesters, rightRequesters) {
            if (pending != null) {
                val requester = resolveRequester(pending, leftRequesters, rightRequesters)
                if (requester != null) {
                    DeviceHelper.markRemoteInteraction()
                    requester.requestFocus()
                    setPendingTarget(null)
                }
            }
        }
    }

    private fun resolveRequester(
        target: CustomControlFocusTarget,
        leftRequesters: List<FocusRequester>?,
        rightRequesters: List<FocusRequester>?
    ): FocusRequester? {
        return when (target) {
            CustomControlFocusTarget.Favorites -> leftRequesters?.getOrNull(1)
            CustomControlFocusTarget.Rotate -> rightRequesters?.getOrNull(1)
        }
    }
}
