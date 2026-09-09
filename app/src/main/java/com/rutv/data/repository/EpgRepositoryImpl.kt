package com.rutv.data.repository

import com.rutv.data.model.EpgProgram
import com.rutv.domain.repository.EpgRepository
import com.rutv.domain.repository.EpgRepository.TimeChangeResult
import com.rutv.domain.repository.EpgRepository.TimeChangeTrigger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** One lock owns cache invalidation/publication; fetch and parsing never hold it. */
@Singleton
class EpgRepositoryImpl internal constructor(
    private val fetch: suspend (String, List<String>, Long, Long) -> EpgFetchResult,
    dispatcher: CoroutineDispatcher,
    private val now: () -> Long
) : EpgRepository {
    @Inject constructor(remote: EpgRemoteDataSource) : this(remote::fetch, Dispatchers.IO, System::currentTimeMillis)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private var generation = 0L
    private var source: String? = null
    private var day = epochDay()
    private var zone = ZoneId.systemDefault()
    private var utcOffset = zone.rules.getOffset(Instant.ofEpochMilli(now()))
    private var backendVersion: Instant? = null
    private val windows = object : LinkedHashMap<WindowKey, List<EpgProgram>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WindowKey, List<EpgProgram>>?) = size > 32
    }
    private val channels = object : LinkedHashMap<String, List<EpgProgram>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EpgProgram>>?) = size > 128
    }
    private val inFlight = mutableMapOf<Key, Flight>()
    private data class WindowKey(val id: String, val from: Long, val to: Long)
    private data class Key(val ids: List<String>, val from: Long, val to: Long)
    private class Flight(val deferred: Deferred<Map<String, List<EpgProgram>>>, var waiters: Int = 0)

    override suspend fun getWindowedProgramsForChannel(epgUrl: String, tvgId: String, fromUtcMillis: Long, toUtcMillis: Long): List<EpgProgram> =
        getWindowedProgramsForChannels(epgUrl, listOf(tvgId), fromUtcMillis, toUtcMillis).getValue(tvgId)

    override suspend fun getWindowedProgramsForChannels(epgUrl: String, tvgIds: List<String>, fromUtcMillis: Long, toUtcMillis: Long): Map<String, List<EpgProgram>> {
        if (tvgIds.isEmpty()) return emptyMap()
        require(fromUtcMillis < toUtcMillis) { "Invalid EPG window" }
        val key = Key(tvgIds.distinct().sorted(), fromUtcMillis, toUtcMillis)
        val cached = mutableMapOf<String, List<EpgProgram>>()
        val flight = mutex.withLock {
            ensureFresh(epgUrl)
            if (now() !in key.from until key.to) {
                for (id in key.ids) windows[WindowKey(id, key.from, key.to)]?.let { cached[id] = it }
                if (cached.size == key.ids.size) return cached
                // Fetch the complete batch so a new backend version cannot mix with old cached rows.
                cached.clear()
            }
            inFlight.getOrPut(key) {
                val requestedGeneration = generation
                Flight(scope.async(start = CoroutineStart.LAZY) {
                    val response = fetch(epgUrl, key.ids, key.from, key.to)
                    ensureActive()
                    mutex.withLock {
                        if (generation != requestedGeneration) throw CancellationException("EPG cache invalidated")
                        val version = runCatching { Instant.parse(response.updateAt) }.getOrNull()
                        if (version != null && backendVersion != null && version < backendVersion) {
                            throw CancellationException("Older EPG generation")
                        }
                        if (version != null && backendVersion != null && version != backendVersion) {
                            windows.clear()
                            channels.clear()
                        }
                        if (version != null) backendVersion = version
                        val result = key.ids.associateWith { id ->
                            val received = response.programs[id] ?: throw java.io.IOException("EPG omitted channel")
                            received.filter { it.stopUtcMillis > key.from && it.startUtcMillis < key.to }
                        }
                        for ((id, programs) in result) {
                            // Backend windows use overlap, so replace every overlapping old interval, even for [].
                            val preserved = channels[id].orEmpty().filterNot { it.stopUtcMillis > key.from && it.startUtcMillis < key.to }
                            channels[id] = (preserved + programs).associateBy { it.id.ifBlank { "${it.startUtcMillis}:${it.title}" } }
                                .values.sortedBy { it.startUtcMillis }.takeLast(512)
                        }
                        // Preserve complete response semantics without retaining oversized windows.
                        for ((id, programs) in result) {
                            if (programs.size <= 512) windows[WindowKey(id, key.from, key.to)] = programs
                        }
                        result
                    }
                })
            }.also { it.waiters++; it.deferred.start() }
        }
        try {
            return cached + flight.deferred.await()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (inFlight[key] === flight && --flight.waiters == 0) {
                        inFlight.remove(key)
                        flight.deferred.cancel()
                    }
                }
            }
        }
    }

    override suspend fun getCurrentProgram(epgUrl: String, tvgId: String): EpgProgram? = mutex.withLock {
        ensureFresh(epgUrl)
        // At most 512 entries. Deriving this avoids a second stale/independently expiring cache.
        val time = now()
        channels[tvgId]?.firstOrNull { it.isCurrent(time) }
    }

    override suspend fun getProgramsForChannel(epgUrl: String, tvgId: String): List<EpgProgram> = mutex.withLock {
        ensureFresh(epgUrl)
        channels[tvgId].orEmpty()
    }

    override suspend fun clearCache() = mutex.withLock { invalidate() }

    override suspend fun handleSystemTimeOrTimezoneChange(trigger: TimeChangeTrigger, now: Long): TimeChangeResult = mutex.withLock {
        val currentZone = ZoneId.systemDefault()
        if (zone != currentZone || utcOffset != currentZone.rules.getOffset(Instant.ofEpochMilli(now))) {
            invalidate()
            TimeChangeResult.TIMEZONE_CHANGED
        } else if (trigger == TimeChangeTrigger.TIME_SET || trigger == TimeChangeTrigger.DATE) {
            invalidate()
            TimeChangeResult.CLOCK_CHANGED
        } else TimeChangeResult.NONE
    }

    private fun epochDay(): Long = Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private fun ensureFresh(epgUrl: String) {
        if (source != epgUrl || day != epochDay() || zone != ZoneId.systemDefault() || utcOffset != zone.rules.getOffset(Instant.ofEpochMilli(now()))) {
            invalidate()
            source = epgUrl
        }
    }

    private fun invalidate() {
        generation++
        inFlight.values.forEach { it.deferred.cancel() }
        inFlight.clear()
        windows.clear()
        channels.clear()
        backendVersion = null
        day = epochDay()
        zone = ZoneId.systemDefault()
        utcOffset = zone.rules.getOffset(Instant.ofEpochMilli(now()))
    }
}
