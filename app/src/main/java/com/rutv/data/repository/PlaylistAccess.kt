package com.rutv.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Orders source edits and commits across both ViewModels; network work stays outside the lock. */
@Singleton
class PlaylistAccess @Inject constructor() {
    private val mutex = Mutex()
    private var revision = 0L

    suspend fun <T> begin(readSource: suspend () -> T): Pair<Long, T> = mutex.withLock {
        ++revision to readSource()
    }

    suspend fun <T> changeSource(block: suspend () -> T): T = mutex.withLock {
        ++revision
        block()
    }

    suspend fun <T> commit(request: Long, block: suspend () -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (request != revision) throw CancellationException("Playlist request superseded")
        block()
    }
}
