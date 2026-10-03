package com.rutv.presentation.main

import com.rutv.data.model.ArchiveEndBehavior
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.presentation.player.ArchiveEndReason
import com.rutv.presentation.player.PlayerState
import com.rutv.presentation.player.ProgramDvrMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveCompletionPolicyTest {
    private val channel = Channel(url = "https://example.com/live", title = "Channel")
    private val program = EpgProgram(
        id = "program",
        startTime = "",
        stopTime = "",
        title = "Program",
        startTimeMillis = 100L,
        stopTimeMillis = 200L
    )

    @Test
    fun behaviorAndNextProgramAvailabilityChooseExpectedAction() {
        assertEquals(
            ArchiveCompletionDecision.RETURN_TO_LIVE,
            decideArchiveCompletion(ArchiveEndBehavior.RETURN_TO_LIVE, hasNextProgram = true)
        )
        assertEquals(
            ArchiveCompletionDecision.ASK,
            decideArchiveCompletion(ArchiveEndBehavior.ASK, hasNextProgram = true)
        )
        assertEquals(
            ArchiveCompletionDecision.ASK,
            decideArchiveCompletion(ArchiveEndBehavior.ASK, hasNextProgram = false)
        )
        assertEquals(
            ArchiveCompletionDecision.PLAY_NEXT,
            decideArchiveCompletion(ArchiveEndBehavior.PLAY_NEXT, hasNextProgram = true)
        )
        assertEquals(
            ArchiveCompletionDecision.RETURN_TO_LIVE,
            decideArchiveCompletion(ArchiveEndBehavior.PLAY_NEXT, hasNextProgram = false)
        )
    }

    @Test
    fun completionMatchRequiresCompletedStateAndSamePlaybackIdentity() {
        val completed = PlayerState.Archive(
            channel = channel,
            program = program,
            mode = ProgramDvrMode.ARCHIVE_PROGRAM,
            endReason = ArchiveEndReason.COMPLETED
        )

        assertTrue(matchesArchiveCompletion(completed, channel, program))
        assertFalse(matchesArchiveCompletion(completed.copy(channel = channel.copy(url = "other")), channel, program))
        assertFalse(matchesArchiveCompletion(completed.copy(program = program.copy(startTimeMillis = 101L)), channel, program))
        assertFalse(matchesArchiveCompletion(completed.copy(endReason = null), channel, program))
        assertFalse(matchesArchiveCompletion(PlayerState.Idle, channel, program))
    }
}
