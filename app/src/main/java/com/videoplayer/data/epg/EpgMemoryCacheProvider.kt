package com.videoplayer.data.epg

import com.videoplayer.data.model.EpgProgram
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EpgMemoryCacheProvider(
    private val fetcher: suspend (tvgId: String, fromMillis: Long, toMillis: Long) -> List<EpgProgram>
) : EpgProvider {
    // Key: (tvgId, fromMillis, toMillis)
    private data class Key(val tvgId: String, val from: Long, val to: Long)

    // Value: result or in-flight deferred
    private val cache = mutableMapOf<Key, List<EpgProgram>>()
    private val inFlight = mutableMapOf<Key, kotlinx.coroutines.Deferred<List<EpgProgram>>>()
    private val mutex = Mutex()

    override suspend fun getEpgForChannel(tvgId: String, fromMillis: Long, toMillis: Long): List<EpgProgram> {
        val key = Key(tvgId, fromMillis, toMillis)
        mutex.withLock {
            // Return cached value if present
            cache[key]?.let { return it }
            // If already in-flight, await its result
            val existingDeferred = inFlight[key]
            if (existingDeferred != null) {
                return existingDeferred.await()
            }
            // Launch and track
            val deferred = kotlinx.coroutines.GlobalScope.async {
                fetcher(tvgId, fromMillis, toMillis)
            }
            inFlight[key] = deferred
            mutex.unlock() // allow fetch to proceed in parallel
            try {
                val result = deferred.await()
                mutex.withLock {
                    cache[key] = result
                    inFlight.remove(key)
                }
                return result
            } finally {
                mutex.withLock {
                    inFlight.remove(key)
                }
            }
        }
    }

    override fun clearSessionCache() {
        cache.clear()
    }
}
