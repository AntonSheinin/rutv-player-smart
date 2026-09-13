package com.rutv.ui.shared.components

import com.rutv.presentation.PinOperation
import com.rutv.presentation.PinRequest
import com.rutv.presentation.PinStatus
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.focus.FocusRequester

class PinDialogSubmissionTest {
    private fun input() = DialogInputState({}, {}, {})

    @Test fun pendingAndClosingGuardsAllowOnlyOneAcceptedSubmission() {
        val input = input()
        val submission = PinDialogSubmission("session", input)
        var calls = 0
        submission.submit(PinOperation(), onDismiss = {}) { calls++; true }
        submission.submit(PinOperation(), onDismiss = {}) { calls++; true }
        assertEquals(1, calls)
        assertTrue(input.pending)
    }

    @Test fun ownerBusyAndRejectedReservationDoNotCreatePendingRequest() {
        val input = input()
        val submission = PinDialogSubmission("session", input)
        var calls = 0
        submission.submit(PinOperation(PinRequest("old", 1), PinStatus.Pending, busy = true), onDismiss = {}) {
            calls++
            true
        }
        assertEquals(0, calls)
        assertNull(submission.request)
        submission.submit(PinOperation(), onDismiss = {}) { calls++; false }
        assertEquals(1, calls)
        assertNull(submission.request)
        assertTrue(input.idle)
    }

    @Test fun acceptedImmediateCloseDismissesOnce() {
        val input = input()
        val submission = PinDialogSubmission("session", input)
        var dismisses = 0
        submission.submit(PinOperation(), closeImmediately = true, onDismiss = { dismisses++ }) { true }
        submission.submit(PinOperation(), closeImmediately = true, onDismiss = { dismisses++ }) { true }
        assertEquals(1, dismisses)
        assertTrue(input.closing)
    }

    @Test fun matchingFailureAllowsRetryAndStaleResultIsIgnored() {
        val input = input()
        val submission = PinDialogSubmission("current", input)
        val focus = FocusRequester()
        var calls = 0
        submission.submit(PinOperation(), onDismiss = {}) { calls++; true }
        val first = requireNotNull(submission.request)
        submission.handleResult(PinOperation(PinRequest("old", 9), PinStatus.Succeeded), {}, { focus })
        assertTrue(input.pending)
        submission.handleResult(PinOperation(first, PinStatus.Failed), {}, { focus })
        assertTrue(input.idle)
        submission.submit(PinOperation(), onDismiss = {}) { calls++; true }
        assertEquals(2, calls)
        assertEquals(2, submission.request?.attempt)
    }
}
