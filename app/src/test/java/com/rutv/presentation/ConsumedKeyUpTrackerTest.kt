package com.rutv.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumedKeyUpTrackerTest {
    @Test
    fun consumesUpAfterHandledDownOnlyOnce() {
        val tracker = ConsumedKeyUpTracker()

        tracker.onDown(consumed = true)

        assertTrue(tracker.onUp())
        assertFalse(tracker.onUp())
    }

    @Test
    fun doesNotConsumeUpWhenDownWasNotHandled() {
        val tracker = ConsumedKeyUpTracker()

        tracker.onDown(consumed = false)

        assertFalse(tracker.onUp())
    }

    @Test
    fun freshDownReplacesStaleConsumedState() {
        val tracker = ConsumedKeyUpTracker()

        tracker.onDown(consumed = true)
        tracker.onDown(consumed = false)

        assertFalse(tracker.onUp())
    }
}
