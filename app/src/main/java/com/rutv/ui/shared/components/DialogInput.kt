package com.rutv.ui.shared.components

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import com.rutv.R
import com.rutv.presentation.PinFailure
import com.rutv.presentation.PinOperation
import com.rutv.presentation.PinRequest
import com.rutv.presentation.PinStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal class DialogInputState(
    private val hideKeyboard: () -> Unit,
    private val restore: (FocusRequester) -> Unit,
    private val cancelRestore: () -> Unit
) {
    var closing by mutableStateOf(false)
        private set
    var pending by mutableStateOf(false)
        private set
    var correctionVersion by mutableIntStateOf(0)
        private set
    val idle get() = !closing && !pending

    fun close(action: () -> Unit) {
        if (closing) return
        closing = true
        cancelRestore()
        hideKeyboard()
        action()
    }

    fun waitForResult() {
        pending = true
        cancelRestore()
        hideKeyboard()
    }

    fun correct(focus: FocusRequester) {
        if (closing) return
        pending = false
        correctionVersion++
        restore(focus)
    }
}

@Composable
internal fun rememberDialogInput(): DialogInputState {
    val keyboard = LocalSoftwareKeyboardController.current
    val currentKeyboard by rememberUpdatedState(keyboard)
    val scope = rememberCoroutineScope()
    var restoreJob by remember { mutableStateOf<Job?>(null) }
    return remember {
        DialogInputState(
            hideKeyboard = { currentKeyboard?.hide() },
            cancelRestore = { restoreJob?.cancel() },
            restore = { focus ->
                restoreJob?.cancel()
                restoreJob = scope.launch {
                    repeat(6) {
                        withFrameNanos { }
                        if (focus.requestFocusSafely()) {
                            withFrameNanos { }
                            currentKeyboard?.show()
                            return@launch
                        }
                    }
                }
            }
        )
    }
}

/** Keyboard disappearance is navigation only. Submission is always an explicit action. */
@Composable
internal fun ChannelDialogFocus(
    input: DialogInputState,
    field: FocusRequester,
    confirm: FocusRequester,
    focused: Boolean
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val window = LocalWindowInfo.current
    val density = LocalDensity.current
    val insets = WindowInsets.ime
    val latestFocused by rememberUpdatedState(focused)
    LaunchedEffect(input.closing) {
        if (!input.idle) return@LaunchedEffect
        withTimeoutOrNull(800) { snapshotFlow { window.isWindowFocused }.filter { it }.first() }
        repeat(6) {
            withFrameNanos { }
            if (!input.idle || latestFocused) return@LaunchedEffect
            field.requestFocusSafely()
        }
    }
    LaunchedEffect(focused, input.closing) {
        if (focused && input.idle) keyboard?.show()
    }
    LaunchedEffect(density, insets, input.correctionVersion, input.closing) {
        var wasVisible = false
        snapshotFlow { insets.getBottom(density) > 0 }.distinctUntilChanged().collect { visible ->
            if (visible) wasVisible = true
            else if (wasVisible && latestFocused && input.idle) {
                wasVisible = false
                confirm.requestFocusSafely()
            }
        }
    }
}

internal class PinDialogSubmission(val session: String, val input: DialogInputState) {
    private var attempting = false
    var request by mutableStateOf<PinRequest?>(null)
        private set

    fun submit(operation: PinOperation, closeImmediately: Boolean = false, onDismiss: () -> Unit, action: (PinRequest) -> Boolean) {
        if (attempting || !input.idle || operation.busy) return
        val next = PinRequest(session, (request?.attempt ?: 0) + 1)
        // Owner reservation is synchronous; a busy rejection must not strand this dialog.
        attempting = true
        try {
            if (!action(next)) return
            request = next
            if (closeImmediately) input.close(onDismiss) else input.waitForResult()
        } finally {
            attempting = false
        }
    }

    fun handleResult(operation: PinOperation, onDismiss: () -> Unit, failureFocus: (PinFailure?) -> FocusRequester) {
        if (request == null || operation.request != request) return
        when (operation.status) {
            PinStatus.Succeeded -> input.close(onDismiss)
            PinStatus.Failed, PinStatus.Idle -> input.correct(failureFocus(operation.failure))
            PinStatus.Pending -> Unit
        }
    }
}

@Composable
internal fun rememberPinDialogSubmission(
    operation: PinOperation,
    onDismiss: () -> Unit,
    failureFocus: (PinFailure?) -> FocusRequester,
    session: String = remember { UUID.randomUUID().toString() }
): PinDialogSubmission {
    val input = key(session) { rememberDialogInput() }
    val submission = remember(session) { PinDialogSubmission(session, input) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestFocus by rememberUpdatedState(failureFocus)
    LaunchedEffect(operation, submission.request) {
        submission.handleResult(operation, latestDismiss, latestFocus)
    }
    return submission
}

@Composable
internal fun pinFailureText(failure: PinFailure?): String? = when (failure) {
    PinFailure.Invalid -> stringResource(R.string.parental_pin_invalid)
    PinFailure.Wrong -> stringResource(R.string.parental_pin_wrong)
    PinFailure.Storage -> stringResource(R.string.parental_pin_operation_failed)
    null -> null
}
