package com.rutv.presentation.main

import com.rutv.data.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class EpgPagingTest {
    @Test fun sequentialAtomicUpdatesKeepBothPagesAndEmptyCoverage() {
        val initial = MainViewState(showEpgPanel = true, epgChannelTvgId = "one", epgLoadedFromUtc = 100, epgLoadedToUtc = 200)
        val past = EpgProgram("past", "", "", "past", startTimeMillis = 20, stopTimeMillis = 30)
        val future = EpgProgram("future", "", "", "future", startTimeMillis = 220, stopTimeMillis = 230)
        val combined = initial.withEpgPage("one", listOf(past), 1, 100).withEpgPage("one", listOf(future), 200, 300)
        assertEquals(listOf("past", "future"), combined.epgPrograms.map { it.id })
        assertEquals(400L, combined.withEpgPage("one", emptyList(), 300, 400).epgLoadedToUtc)
        assertSame(combined, combined.withEpgPage("other", listOf(future), 300, 400))
        val closed = combined.copy(showEpgPanel = false)
        assertSame(closed, closed.withEpgPage("one", listOf(future), 300, 400))
    }
}
