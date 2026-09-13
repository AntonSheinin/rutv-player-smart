package com.rutv.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PinOperationTest {
    @Test fun reservesBeforeLaunchAndKeepsSuccessAfterOwnerIsReleased() = runTest {
        val states = mutableListOf<PinOperation>()
        val runner = PinOperationRunner(this, states::add)
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        assertTrue(runner.start(PinRequest("one", 1)) { calls++; finish.await() })
        assertFalse(runner.start(PinRequest("two", 1)) { calls++ })
        assertTrue(states.last().busy)
        runCurrent()
        assertEquals(1, calls)
        finish.complete(Unit)
        runCurrent()
        assertEquals(PinStatus.Succeeded, states.last().status)
        assertFalse(states.last().busy)
        assertEquals(PinRequest("one", 1), states.last().request)
        assertTrue(runner.start(PinRequest("two", 1)) { calls++ })
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun identicalFailuresAreDistinctAttemptsAndAllowRetry() = runTest {
        val states = mutableListOf<PinOperation>()
        val runner = PinOperationRunner(this, states::add)
        repeat(2) { index ->
            val request = PinRequest("one", index + 1)
            assertTrue(runner.start(request) { throw PinRejected(PinFailure.Wrong) })
            runCurrent()
            assertEquals(PinOperation(request, PinStatus.Failed, PinFailure.Wrong), states.last())
        }
        assertTrue(runner.start(PinRequest("one", 3)) { })
        runCurrent()
        assertEquals(PinStatus.Succeeded, states.last().status)
    }

    @Test fun storageFailureReleasesOwnerWithoutExposingException() = runTest {
        var state = PinOperation()
        val runner = PinOperationRunner(this) { state = it }
        runner.start(PinRequest("one", 1)) { throw IllegalStateException("private details") }
        runCurrent()
        assertEquals(PinFailure.Storage, state.failure)
        assertFalse(state.busy)
    }

    @Test fun cancelBeforeCoroutineStartsReleasesReservation() = runTest {
        var state = PinOperation()
        val runner = PinOperationRunner(this) { state = it }
        var calls = 0
        runner.start(PinRequest("one", 1)) { calls++ }
        runner.cancel()
        runCurrent()
        assertFalse(state.busy)
        assertEquals(0, calls)
        assertTrue(runner.start(PinRequest("two", 1)) { calls++ })
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun cancelSuspendedVerificationDoesNotExecuteProtectedAction() = runTest {
        var state = PinOperation()
        val runner = PinOperationRunner(this) { state = it }
        val read = CompletableDeferred<Unit>()
        var actions = 0
        runner.start(PinRequest("one", 1)) { read.await(); actions++ }
        runCurrent()
        runner.cancel()
        read.complete(Unit)
        runCurrent()
        assertEquals(0, actions)
        assertEquals(PinStatus.Idle, state.status)
        assertFalse(state.busy)
    }
}
