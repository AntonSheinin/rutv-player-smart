package com.rutv.presentation.main

import com.rutv.data.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class EpgOpeningStateTest {
    @Test
    fun targetIdentityWinsAndSurvivesMetadataChanges() {
        val target = program(id = "archive", start = 100L, stop = 200L, title = "Old title")
        val refreshed = listOf(
            program(id = "live", start = 300L, stop = 500L),
            program(id = "archive", start = 110L, stop = 210L, title = "New title")
        )

        assertEquals(
            1,
            resolveInitialEpgProgramIndex(refreshed, target.focusIdentity(), nowUtcMillis = 350L)
        )
    }

    @Test
    fun timestampIdentityWorksWithoutIdAndChoosesFirstChronologicalDuplicate() {
        val identity = program(id = "", start = 100L, stop = 200L).focusIdentity()
        val programs = listOf(
            program(id = "", start = 100L, stop = 200L, title = "Later in list"),
            program(id = "", start = 100L, stop = 200L, title = "Duplicate")
        )

        assertEquals(0, programs.indexOfProgram(identity))
    }

    @Test
    fun resolverUsesHalfOpenCurrentProgramThenFirstAndHandlesEmpty() {
        val programs = listOf(
            program(id = "first", start = 100L, stop = 200L),
            program(id = "second", start = 200L, stop = 300L)
        )

        assertEquals(1, resolveInitialEpgProgramIndex(programs, null, nowUtcMillis = 200L))
        assertEquals(0, resolveInitialEpgProgramIndex(programs, null, nowUtcMillis = 400L))
        assertEquals(-1, resolveInitialEpgProgramIndex(emptyList(), null, nowUtcMillis = 200L))
    }

    @Test
    fun malformedProgramHasNoIdentityAndOverlapUsesHalfOpenWindow() {
        assertNull(program(id = "", start = 0L, stop = 0L).focusIdentity())
        val programs = listOf(
            program(id = "before", start = 50L, stop = 100L),
            program(id = "overlap", start = 50L, stop = 150L),
            program(id = "inside", start = 100L, stop = 200L),
            program(id = "after", start = 200L, stop = 250L)
        )

        assertEquals(
            listOf("overlap", "inside"),
            programs.overlapping(fromUtcMillis = 100L, toUtcMillis = 200L).map { it.id }
        )
    }

    @Test
    fun idOnlyTargetUsesTimestampFromExactCachedMatch() {
        val target = program(id = "archive", start = 0L, stop = 0L)
        val cached = program(id = "archive", start = 500L, stop = 600L)

        val resolved = resolveEpgTargetWindow(target, listOf(cached))

        assertEquals(target, resolved?.target)
        assertEquals(500L, resolved?.dayAnchorUtcMillis)
        assertNull(resolveEpgTargetWindow(target, emptyList()))
    }

    @Test
    fun closingPanelClearsOnlyEpgOwnedState() {
        val details = program(id = "details", start = 100L, stop = 200L)
        val state = MainViewState(
            showEpgPanel = true,
            isEpgLoading = true,
            selectedProgramDetails = details,
            epgOpeningState = EpgOpeningState(7L, "channel", details, true)
        )

        val closed = state.withEpgPanelClosed()

        assertFalse(closed.showEpgPanel)
        assertFalse(closed.isEpgLoading)
        assertNull(closed.epgOpeningState)
        assertEquals(details, closed.selectedProgramDetails)
    }

    private fun program(
        id: String,
        start: Long,
        stop: Long,
        title: String = id
    ) = EpgProgram(
        id = id,
        startTime = "",
        stopTime = "",
        title = title,
        startTimeMillis = start,
        stopTimeMillis = stop
    )
}
