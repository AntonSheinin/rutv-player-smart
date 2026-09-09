package com.rutv.data.repository

import com.rutv.data.model.EpgProgram
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class EpgRepositoryTest {
    private fun program(id: String, start: Long = 100, stop: Long = 200) =
        EpgProgram(id, "", "", id, startTimeMillis = start, stopTimeMillis = stop)

    @Test fun concurrentWaitersShareFetchAndCancellationDoesNotCancelOthers() = runTest {
        val response = CompletableDeferred<Unit>()
        var calls = 0
        val repo = EpgRepositoryImpl({ _, ids, _, _ ->
            calls++; response.await(); EpgFetchResult(ids.associateWith { listOf(program(it)) }, "")
        }, StandardTestDispatcher(testScheduler)) { 150 }
        val first = async { repo.getWindowedProgramsForChannel("a", "1", 0, 300) }
        val second = async { repo.getWindowedProgramsForChannel("a", "1", 0, 300) }
        runCurrent()
        assertEquals(1, calls)
        first.cancelAndJoin()
        response.complete(Unit)
        assertEquals("1", second.await().single().id)
    }

    @Test fun lastWaiterCancellationReleasesFetchAndAllowsRetry() = runTest {
        var cancelled = false
        var calls = 0
        val repo = EpgRepositoryImpl({ _, _, _, _ ->
            calls++
            try { awaitCancellation() } finally { cancelled = true }
        }, StandardTestDispatcher(testScheduler)) { 150 }
        val first = launch { repo.getWindowedProgramsForChannel("a", "1", 0, 300) }
        runCurrent(); first.cancelAndJoin(); runCurrent()
        assertTrue(cancelled)
        val second = launch { repo.getWindowedProgramsForChannel("a", "1", 0, 300) }
        runCurrent(); assertEquals(2, calls); second.cancelAndJoin()
    }

    @Test fun emptySuccessReplacesWindowButFailureDoesNot() = runTest {
        var fail = false
        var programs = listOf(program("1"))
        val repo = EpgRepositoryImpl({ _, ids, _, _ ->
            if (fail) throw IOException("offline")
            EpgFetchResult(ids.associateWith { programs }, "")
        }, StandardTestDispatcher(testScheduler)) { 150 }
        repo.getWindowedProgramsForChannel("a", "1", 0, 300)
        fail = true
        try { repo.getWindowedProgramsForChannel("a", "1", 0, 300); fail("Expected failure") } catch (_: IOException) { }
        assertNotNull(repo.getCurrentProgram("a", "1"))
        fail = false; programs = emptyList()
        repo.getWindowedProgramsForChannel("a", "1", 0, 300)
        assertNull(repo.getCurrentProgram("a", "1"))
    }

    @Test fun pastPageDoesNotEraseCurrentAndProgramExpiresAtBoundary() = runTest {
        var now = 150L
        val repo = EpgRepositoryImpl({ _, ids, from, _ ->
            EpgFetchResult(ids.associateWith { if (from == 0L) listOf(program("live")) else listOf(program("past", 10, 20)) }, "")
        }, StandardTestDispatcher(testScheduler)) { now }
        repo.getWindowedProgramsForChannel("a", "1", 0, 300)
        repo.getWindowedProgramsForChannel("a", "1", 1, 50)
        assertEquals("live", repo.getCurrentProgram("a", "1")?.id)
        now = 200
        assertNull(repo.getCurrentProgram("a", "1"))
        assertTrue(repo.getProgramsForChannel("other-source", "1").isEmpty())
    }

    @Test fun invalidationRejectsEvenNonCooperativeOldResponse() = runTest {
        val release = CompletableDeferred<Unit>()
        val repo = EpgRepositoryImpl({ _, ids, _, _ ->
            withContext(NonCancellable) { release.await() }
            EpgFetchResult(ids.associateWith { listOf(program(it)) }, "")
        }, StandardTestDispatcher(testScheduler)) { 150 }
        val request = async { repo.getWindowedProgramsForChannel("a", "1", 0, 300) }
        runCurrent(); repo.clearCache(); release.complete(Unit)
        try { request.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertTrue(repo.getProgramsForChannel("a", "1").isEmpty())
    }
    @Test fun cachedBatchWindowsAreReusedForSingleChannelArchiveRequests() = runTest {
        var calls = 0
        val repo = EpgRepositoryImpl({ _, ids, _, _ ->
            calls++; EpgFetchResult(ids.associateWith { listOf(program(it)) }, "")
        }, StandardTestDispatcher(testScheduler)) { 1000 }
        repo.getWindowedProgramsForChannels("a", listOf("1", "2"), 0, 300)
        repo.getWindowedProgramsForChannel("a", "1", 0, 300)
        assertEquals(1, calls)
    }

    @Test fun olderBackendResponseCannotOverwriteNewerGeneration() = runTest {
        val oldResponse = CompletableDeferred<Unit>()
        val repo = EpgRepositoryImpl({ _, ids, _, _ ->
            if (ids == listOf("old")) oldResponse.await()
            EpgFetchResult(ids.associateWith { listOf(program(it)) }, if (ids == listOf("old")) "2026-09-05T00:00:00Z" else "2026-09-06T00:00:00Z")
        }, StandardTestDispatcher(testScheduler)) { 150 }
        val old = async { repo.getWindowedProgramsForChannel("a", "old", 0, 300) }
        runCurrent()
        repo.getWindowedProgramsForChannel("a", "new", 0, 300)
        oldResponse.complete(Unit)
        try { old.await(); fail("Expected obsolete response to be rejected") } catch (_: CancellationException) { }
        assertTrue(repo.getProgramsForChannel("a", "old").isEmpty())
        assertEquals("new", repo.getCurrentProgram("a", "new")?.id)
    }
    @Test fun partialCachedBatchDoesNotMixBackendVersions() = runTest {
        var version = "2026-09-05T00:00:00Z"
        val repo = EpgRepositoryImpl({ _, ids, _, _ ->
            EpgFetchResult(ids.associateWith { listOf(program(version)) }, version)
        }, StandardTestDispatcher(testScheduler)) { 1000 }
        repo.getWindowedProgramsForChannel("a", "1", 0, 300)
        version = "2026-09-06T00:00:00Z"
        val result = repo.getWindowedProgramsForChannels("a", listOf("1", "2"), 0, 300)
        assertEquals(setOf(version), result.values.flatten().map { it.id }.toSet())
    }

}
