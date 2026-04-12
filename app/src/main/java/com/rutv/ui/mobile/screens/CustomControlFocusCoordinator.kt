package com.rutv.ui.mobile.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.rutv.ui.shared.components.requestFocusSafely
import kotlinx.coroutines.isActive

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
        val requester = resolveRequester(target, leftRequesters, rightRequesters)
        if (requester != null && requester.requestFocusSafely()) {
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
                // Keep retrying while this target is pending; on slow STBs the focus node can
                // attach noticeably later than the first request.
                while (isActive && getPendingTarget() == pending) {
                    val requester = resolveRequester(pending, leftRequesters, rightRequesters)
                    if (requester != null && requester.requestFocusSafely()) {
                        setPendingTarget(null)
                        return@LaunchedEffect
                    }
                    withFrameNanos { }
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
