package com.videoplayer.data.epg

import com.videoplayer.data.model.EpgProgram
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EpgMemoryCacheProvider(
    private val fetcher: suspend (tvgId: String, fromMillis: Long, toMillis: Long) -> List<EpgProgram>
) : EpgProvider {
    private data class Key(val tvgId: String, val from: Long, val to: Long)

    private val cache = mutableMapOf<Key, List<EpgProgram>>()
    private val inFlight = mutableMapOf<Key, Deferred<List<EpgProgram>>>()
    private val mutex = Mutex()

    override suspend fun getEpgForChannel(tvgId: String, fromMillis: Long, toMillis: Long): List<EpgProgram> = coroutineScope {
        val key = Key(tvgId, fromMillis, toMillis)

        // Fast-path: check cache without holding await
        mutex.withLock { cache[key] }?.let { return@coroutineScope it }

        // Create or join in-flight request
        val deferred = mutex.withLock {
            inFlight[key] ?: async {
                fetcher(tvgId, fromMillis, toMillis)
            }.also { inFlight[key] = it }
        }

        try {
            val result = deferred.await()
            mutex.withLock {
                cache[key] = result
                inFlight.remove(key)
            }
            result
        } catch (e: Exception) {
            mutex.withLock { inFlight.remove(key) }
            throw e
        }
    }

    override fun clearSessionCache() {
        cache.clear()
    }
}
