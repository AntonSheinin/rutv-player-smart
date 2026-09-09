package com.rutv.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlaylistAccessTest {
    @Test fun newerLoadAndSourceEditsInvalidateEarlierCommits() = runTest {
        val access = PlaylistAccess()
        val (old, _) = access.begin { "a" }
        val (new, _) = access.begin { "a" }
        try { access.commit(old) { fail("Old commit ran") }; fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals("saved", access.commit(new) { "saved" })
        access.changeSource { }
        try { access.commit(new) { fail("Commit after source change") }; fail("Expected cancellation") } catch (_: CancellationException) { }
    }
}
