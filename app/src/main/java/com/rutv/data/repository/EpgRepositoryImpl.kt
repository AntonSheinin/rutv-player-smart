package com.rutv.data.repository

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.rutv.data.model.EpgChannelRequest
import com.rutv.data.model.EpgProgram
import com.rutv.data.model.EpgRequest
import com.rutv.data.model.EpgResponse
import com.rutv.domain.repository.EpgRepository
import com.rutv.domain.repository.EpgRepository.TimeChangeResult
import com.rutv.domain.repository.EpgRepository.TimeChangeTrigger
import com.rutv.util.Constants
import com.rutv.util.EpgConstants
import com.rutv.util.logDebug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
/**
 * EPG repository responsible for fetching and caching EPG programs.
 *
 * Key ideas:
 * - **Windowed fetching**: callers request a (from,to) window in UTC millis.
 * - **Request de-duplication**: identical windows are coalesced via [windowInFlight].
 * - **Two caches**:
 *   - [windowCache]: window -> program list (small LRU)
 *   - [channelPrograms]: tvgId -> merged list of programs (small LRU)
 * - **Cheap "current program" lookups**:
 *   - [getCurrentProgram] uses [channelPrograms] and keeps a short TTL map to avoid repeated scans.
 *
 * Time correctness:
 * - Cache invalidation is sensitive to:
 *   - **day changes** (to avoid showing yesterday's snapshot indefinitely)
 *   - **timezone / UTC offset changes** (program boundaries move in local time)
 *   - **system clock changes** (invalidate current-program snapshot)
 *
 * Parsing:
 * - Uses a streaming JSON parser ([JsonReader]) because EPG payloads can be large.
 * - Truncates overly large fields defensively to avoid OOM / UI issues.
 */
class EpgRepositoryImpl @Inject constructor(
    private val gson: Gson
) : EpgRepository {

    private val windowCacheMutex = Mutex()
    private val windowCache =
        object : LinkedHashMap<WindowKey, List<EpgProgram>>(WINDOW_CACHE_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WindowKey, List<EpgProgram>>?): Boolean {
                return size > WINDOW_CACHE_CAPACITY
            }
        }
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val windowInFlight = mutableMapOf<WindowKey, InFlight<SingleFetchResult>>()
    private val batchInFlight = mutableMapOf<BatchWindowKey, InFlight<FetchResult?>>()

    private val channelDataMutex = Mutex()
    private val channelPrograms =
        object : LinkedHashMap<String, MutableList<EpgProgram>>(CHANNEL_CACHE_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<EpgProgram>>?): Boolean {
                return size > CHANNEL_CACHE_CAPACITY
            }
        }

    private var currentProgramsCache: Map<String, EpgProgram?>? = null
    private var currentProgramsCacheTime: Long = 0L
    private val currentProgramsCacheTtl = 60_000L

    // Backend regen timestamp from the last response. Guarded by channelDataMutex.
    private var lastEpgUpdateAtSeen: String? = null

    private var truncationWarningLogged = false

    private var lastKnownTimezoneId: String = TimeZone.getDefault().id
    private var lastKnownUtcOffsetMinutes: Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
    @Volatile
    private var lastCacheEpochDay: Long = currentEpochDay()

    override suspend fun handleSystemTimeOrTimezoneChange(
        trigger: TimeChangeTrigger,
        now: Long
    ): TimeChangeResult {
        val timezone = TimeZone.getDefault()
        val offsetMinutes = timezone.getOffset(now) / 60_000
        val timezoneChanged = timezone.id != lastKnownTimezoneId
        val offsetChanged = offsetMinutes != lastKnownUtcOffsetMinutes

        if (timezoneChanged || offsetChanged) {
            val previousId = lastKnownTimezoneId
            val previousOffset = lastKnownUtcOffsetMinutes
            lastKnownTimezoneId = timezone.id
            lastKnownUtcOffsetMinutes = offsetMinutes
            Timber.i(
                "Device timezone changed from $previousId (UTC${formatUtcOffset(previousOffset)}) " +
                    "to ${timezone.id} (UTC${formatUtcOffset(offsetMinutes)})"
            )
            clearCache()
            return TimeChangeResult.TIMEZONE_CHANGED
        }

        if (trigger == TimeChangeTrigger.TIMEZONE) {
            logDebug { "Timezone change broadcast received but timezone snapshot unchanged; ignoring" }
            return TimeChangeResult.NONE
        }

        if (trigger == TimeChangeTrigger.TIME_SET || trigger == TimeChangeTrigger.DATE) {
            Timber.i("System clock adjusted (${trigger.name.lowercase()}), clearing current-program cache")
            channelDataMutex.withLock {
                currentProgramsCache = null
                currentProgramsCacheTime = 0
            }
            return TimeChangeResult.CLOCK_CHANGED
        }

        logDebug {
            "Ignoring time change trigger $trigger (timezone=${timezone.id}, offsetMinutes=$offsetMinutes, " +
                "cachedTimezone=$lastKnownTimezoneId, cachedOffset=$lastKnownUtcOffsetMinutes)"
        }
        return TimeChangeResult.NONE
    }

    override suspend fun getWindowedProgramsForChannel(
        epgUrl: String,
        tvgId: String,
        fromUtcMillis: Long,
        toUtcMillis: Long
    ): List<EpgProgram> = coroutineScope {
        ensureCacheFresh()
        val key = WindowKey(epgUrl, tvgId, fromUtcMillis, toUtcMillis)
        // The EPG backend refreshes once a day, so any window that overlaps "now" must
        // hit the network on each call — otherwise a stale in-memory copy shadows the
        // nightly refresh until the calendar day rolls over. Past-only windows (archive
        // paging) can't change after broadcast and continue to use the cache.
        val now = System.currentTimeMillis()
        val windowOverlapsNow = now in fromUtcMillis..toUtcMillis
        if (!windowOverlapsNow) {
            windowCacheMutex.withLock {
                windowCache[key]?.let { return@coroutineScope it }
            }
        } else {
            logDebug { "Bypassing windowCache for $tvgId (window overlaps now)" }
        }

        val inFlight = windowCacheMutex.withLock {
            // Important: we store the Deferred so concurrent callers share one network request.
            (windowInFlight[key] ?: InFlight(
                repositoryScope.async {
                    fetchSingleChannelWindow(epgUrl, tvgId, fromUtcMillis, toUtcMillis)
                }
            ).also { windowInFlight[key] = it }).also { it.waiters++ }
        }

        try {
            val fetched = inFlight.deferred.await()
            handleLastEpgUpdateAtChange(fetched.lastEpgUpdateAt)
            val result = fetched.programs
            windowCacheMutex.withLock {
                windowCache[key] = result
            }
            rememberProgramsForChannel(tvgId, result, fromUtcMillis, toUtcMillis)
            cacheCurrentProgramSnapshot(tvgId, result)
            result
        } finally {
            windowCacheMutex.withLock {
                releaseInFlight(windowInFlight, key, inFlight)
            }
        }
    }

    override suspend fun getWindowedProgramsForChannels(
        epgUrl: String,
        tvgIds: List<String>,
        fromUtcMillis: Long,
        toUtcMillis: Long
    ): Map<String, List<EpgProgram>> = coroutineScope {
        if (tvgIds.isEmpty()) return@coroutineScope emptyMap()
        ensureCacheFresh()

        val now = System.currentTimeMillis()
        val windowOverlapsNow = now in fromUtcMillis..toUtcMillis
        val result = mutableMapOf<String, List<EpgProgram>>()
        val toFetch: List<String>

        if (!windowOverlapsNow) {
            val misses = mutableListOf<String>()
            windowCacheMutex.withLock {
                for (tvgId in tvgIds.distinct()) {
                    val key = WindowKey(epgUrl, tvgId, fromUtcMillis, toUtcMillis)
                    val cached = windowCache[key]
                    if (cached != null) {
                        result[tvgId] = cached
                    } else {
                        misses.add(tvgId)
                    }
                }
            }
            toFetch = misses
        } else {
            logDebug { "Bypassing windowCache for batch of ${tvgIds.size} channels (window overlaps now)" }
            toFetch = tvgIds.distinct()
        }

        if (toFetch.isEmpty()) return@coroutineScope result

        val batchKey = BatchWindowKey(epgUrl, toFetch.sorted(), fromUtcMillis, toUtcMillis)
        val inFlight = windowCacheMutex.withLock {
            (batchInFlight[batchKey] ?: InFlight(
                repositoryScope.async {
                    executeFetch(epgUrl, toFetch, fromUtcMillis, toUtcMillis)
                }
            ).also { batchInFlight[batchKey] = it }).also { it.waiters++ }
        }

        try {
            val fetched = inFlight.deferred.await()
            if (fetched != null) {
                handleLastEpgUpdateAtChange(fetched.lastEpgUpdateAt)
                for (tvgId in toFetch) {
                    val programs = trimProgramsToWindow(
                        fetched.programsByTvgId[tvgId].orEmpty(),
                        fromUtcMillis,
                        toUtcMillis
                    )
                    val key = WindowKey(epgUrl, tvgId, fromUtcMillis, toUtcMillis)
                    windowCacheMutex.withLock {
                        windowCache[key] = programs
                    }
                    rememberProgramsForChannel(tvgId, programs, fromUtcMillis, toUtcMillis)
                    cacheCurrentProgramSnapshot(tvgId, programs)
                    result[tvgId] = programs
                }
            }
            result
        } finally {
            windowCacheMutex.withLock {
                releaseInFlight(batchInFlight, batchKey, inFlight)
            }
        }
    }

    override suspend fun getCurrentProgram(tvgId: String): EpgProgram? {
        ensureCacheFresh()
        val now = System.currentTimeMillis()
        return channelDataMutex.withLock {
            // Fast path: return from TTL cache if fresh
            val cache = currentProgramsCache
            if (cache != null && now - currentProgramsCacheTime < currentProgramsCacheTtl) {
                return@withLock cache[tvgId]
            }

            val programs = channelPrograms[tvgId] ?: return@withLock null
            val current = programs.firstOrNull { it.isCurrent(now) }

            val updated = (currentProgramsCache ?: emptyMap()).toMutableMap()
            updated[tvgId] = current
            currentProgramsCache = updated
            currentProgramsCacheTime = now
            current
        }
    }

    override suspend fun getProgramsForChannel(tvgId: String): List<EpgProgram> {
        ensureCacheFresh()
        return channelDataMutex.withLock {
            channelPrograms[tvgId]?.toList()
        } ?: emptyList()
    }

    override suspend fun clearCache() {
        lastCacheEpochDay = currentEpochDay()
        windowCacheMutex.withLock {
            windowInFlight.values.forEach { it.deferred.cancel() }
            batchInFlight.values.forEach { it.deferred.cancel() }
            windowCache.clear()
            windowInFlight.clear()
            batchInFlight.clear()
        }
        channelDataMutex.withLock {
            channelPrograms.clear()
            currentProgramsCache = null
            currentProgramsCacheTime = 0
            lastEpgUpdateAtSeen = null
        }
        logDebug { "EPG cache cleared (lazy windows + current programs)" }
    }

    private fun <K, T> releaseInFlight(
        map: MutableMap<K, InFlight<T>>,
        key: K,
        entry: InFlight<T>
    ) {
        val current = map[key]
        if (current !== entry) return
        entry.waiters = (entry.waiters - 1).coerceAtLeast(0)
        if (entry.waiters == 0 || entry.deferred.isCompleted) {
            if (!entry.deferred.isCompleted) {
                entry.deferred.cancel()
            }
            map.remove(key)
        }
    }

    private suspend fun ensureCacheFresh(now: Long = System.currentTimeMillis()) {
        val epochDay = currentEpochDay(now)
        if (epochDay == lastCacheEpochDay) return
        // clearCache() sets lastCacheEpochDay first, so concurrent callers that
        // pass this check will see the updated value and short-circuit.
        Timber.i("EPG cache stale (day changed from $lastCacheEpochDay to $epochDay); forcing refresh")
        clearCache()
    }

    private fun currentEpochDay(now: Long = System.currentTimeMillis()): Long {
        return Instant.ofEpochMilli(now)
            .atZone(TimeZone.getDefault().toZoneId())
            .toLocalDate()
            .toEpochDay()
    }

    private suspend fun cacheCurrentProgramSnapshot(tvgId: String, programs: List<EpgProgram>) {
        val now = System.currentTimeMillis()
        channelDataMutex.withLock {
            val cache = currentProgramsCache?.toMutableMap() ?: mutableMapOf()
            cache[tvgId] = programs.firstOrNull { it.isCurrent(now) }
            currentProgramsCache = cache
            currentProgramsCacheTime = now
        }
    }

    private suspend fun rememberProgramsForChannel(
        tvgId: String,
        programs: List<EpgProgram>,
        fromUtcMillis: Long,
        toUtcMillis: Long
    ) {
        if (programs.isEmpty()) return
        channelDataMutex.withLock {
            val existing = channelPrograms[tvgId]?.toList() ?: emptyList()
            // Drop existing programs that fall inside the fetched window: the fresh
            // response is authoritative for that range, so programs the backend
            // removed (or whose timing shifted within the window) must not linger.
            // Programs outside the window (loaded via archive paging) stay put.
            val preserved = existing.filter { program ->
                program.startUtcMillis !in fromUtcMillis..toUtcMillis
            }
            val merged = LinkedHashMap<String, EpgProgram>(preserved.size + programs.size)
            fun key(program: EpgProgram) = program.id.ifBlank { "${program.startUtcMillis}:${program.title}" }
            preserved.forEach { merged[key(it)] = it }
            programs.forEach { merged[key(it)] = it }
            val sorted = merged.values.sortedBy { it.startUtcMillis }
            // Keep memory bounded even if callers request very large windows repeatedly.
            val clamped = if (sorted.size > MAX_PROGRAMS_PER_CHANNEL) {
                sorted.takeLast(MAX_PROGRAMS_PER_CHANNEL)
            } else {
                sorted
            }
            channelPrograms[tvgId] = clamped.toMutableList()
        }
    }

    private suspend fun fetchSingleChannelWindow(
        epgUrl: String,
        tvgId: String,
        fromUtcMillis: Long,
        toUtcMillis: Long
    ): SingleFetchResult {
        val fetched = executeFetch(epgUrl, listOf(tvgId), fromUtcMillis, toUtcMillis)
            ?: return SingleFetchResult(emptyList(), "")
        val programs = trimProgramsToWindow(
            fetched.programsByTvgId[tvgId].orEmpty(),
            fromUtcMillis,
            toUtcMillis
        )
        return SingleFetchResult(programs, fetched.lastEpgUpdateAt)
    }

    // EPG backend expects a POST with:
    // - channel IDs (xmltv_id)
    // - a timezone ID string for server-side conversions
    // - optional ISO8601 from/to filters
    // Backend supports batching; callers (single-channel or batch) funnel through here.
    private suspend fun executeFetch(
        epgUrl: String,
        tvgIds: List<String>,
        fromUtcMillis: Long,
        toUtcMillis: Long
    ): FetchResult? = withContext(Dispatchers.IO) {
        if (tvgIds.isEmpty()) return@withContext null
        var connection: HttpURLConnection? = null
        val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            connection?.disconnect()
        }
        try {
            val deviceTimezone = TimeZone.getDefault().id
            val fromIso = Instant.ofEpochMilli(fromUtcMillis).toString()
            val toIso = Instant.ofEpochMilli(toUtcMillis).toString()

            val request = EpgRequest(
                channels = tvgIds.map { EpgChannelRequest(xmltvId = it) },
                timezone = deviceTimezone,
                fromDate = fromIso,
                toDate = toIso
            )
            val body = gson.toJson(request)

            connection = (URL("$epgUrl/epg").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", Constants.DEFAULT_USER_AGENT)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("Connection", "keep-alive")
                connectTimeout = EpgConstants.EPG_CONNECT_TIMEOUT_MS
                readTimeout = EpgConstants.EPG_READ_TIMEOUT_MS
                doOutput = true
            }

            connection.outputStream.use { os -> os.write(body.toByteArray()) }

            val code = connection.responseCode
            if (code != 200) {
                Timber.e("EPG HTTP error: $code (channels=${tvgIds.size})")
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().use { reader ->
                parseEpgResponseStreaming(reader)
            } ?: return@withContext null

            FetchResult(response.epg, response.lastEpgUpdateAt)
        } catch (e: javax.net.ssl.SSLException) {
            Timber.e(e, "SSL/TLS error fetching EPG")
            null
        } catch (e: java.net.SocketException) {
            Timber.e(e, "Network error fetching EPG")
            null
        } catch (e: java.net.UnknownHostException) {
            Timber.e(e, "Host resolution error for EPG URL")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch EPG window")
            null
        } finally {
            completionHandle?.dispose()
            connection?.disconnect()
        }
    }

    // Detects a backend regeneration between calls. The field itself lives in the response body,
    // so this is a post-fetch consistency check, not a way to skip network calls. On change we
    // invalidate windowCache + currentProgramsCache so subsequent accesses re-fetch; channelPrograms
    // gets overwritten lazily by rememberProgramsForChannel on each re-fetch.
    private suspend fun handleLastEpgUpdateAtChange(newTimestamp: String) {
        if (newTimestamp.isBlank()) return
        val changed = channelDataMutex.withLock {
            val prev = lastEpgUpdateAtSeen
            lastEpgUpdateAtSeen = newTimestamp
            prev != null && prev != newTimestamp
        }
        if (!changed) return
        Timber.i("EPG backend regenerated (last_epg_update_at changed); invalidating window/current caches")
        windowCacheMutex.withLock { windowCache.clear() }
        channelDataMutex.withLock {
            currentProgramsCache = null
            currentProgramsCacheTime = 0
        }
    }

    private fun trimProgramsToWindow(
        programs: List<EpgProgram>,
        fromUtcMillis: Long,
        toUtcMillis: Long
    ): List<EpgProgram> {
        if (programs.isEmpty()) return emptyList()
        return programs.filter { program ->
            val start = program.startUtcMillis
            val stop = program.stopUtcMillis
            stop >= fromUtcMillis && start <= toUtcMillis
        }
    }

    private fun formatUtcOffset(totalMinutes: Int): String {
        val sign = if (totalMinutes >= 0) "+" else "-"
        val absolute = abs(totalMinutes)
        val hours = absolute / 60
        val minutes = absolute % 60
        return "$sign${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
    }

    private fun JsonReader.safeNextString(maxLength: Int): String {
        val value = nextString()
        if (value.length > maxLength) {
            if (!truncationWarningLogged) {
                Timber.w("EPG field at $path truncated to $maxLength characters (further truncation messages suppressed)")
                truncationWarningLogged = true
            }
            return value.take(maxLength)
        }
        return value
    }

    private fun parseEpgResponseStreaming(reader: Reader): EpgResponse? {
        truncationWarningLogged = false
        val jsonReader = JsonReader(reader)
        var updateMode = ""
        var timestamp = ""
        var lastEpgUpdateAt = ""
        var channelsRequested = 0
        var channelsFound = 0
        var totalPrograms = 0
        val epgMap = mutableMapOf<String, List<EpgProgram>>()

        try {
            jsonReader.beginObject()
            while (jsonReader.hasNext()) {
                when (jsonReader.nextName()) {
                    "update_mode" -> updateMode = jsonReader.safeNextString(MAX_FIELD_LENGTH_TITLE)
                    "timestamp" -> timestamp = jsonReader.safeNextString(MAX_FIELD_LENGTH_TIME)
                    "last_epg_update_at" -> lastEpgUpdateAt = jsonReader.safeNextString(MAX_FIELD_LENGTH_TIME)
                    "channels_requested" -> channelsRequested = jsonReader.nextInt()
                    "channels_found" -> channelsFound = jsonReader.nextInt()
                    "total_programs" -> totalPrograms = jsonReader.nextInt()
                    "epg" -> {
                        logDebug { "Parsing EPG map (streaming)..." }
                        var channelCount = 0
                        jsonReader.beginObject()
                        while (jsonReader.hasNext()) {
                            val channelId = jsonReader.nextName()
                            val programs = parsePrograms(jsonReader)
                            epgMap[channelId] = programs
                            channelCount++
                        }
                        jsonReader.endObject()
                        logDebug { "Finished parsing $channelCount channels" }
                    }
                    else -> jsonReader.skipValue()
                }
            }
            jsonReader.endObject()
            return EpgResponse(updateMode, timestamp, lastEpgUpdateAt, channelsRequested, channelsFound, totalPrograms, epgMap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error in streaming JSON parser")
            return null
        } finally {
            jsonReader.close()
        }
    }

    private fun parsePrograms(jsonReader: JsonReader): List<EpgProgram> {
        val programs = mutableListOf<EpgProgram>()
        jsonReader.beginArray()
        while (jsonReader.hasNext()) {
            programs.add(parseProgram(jsonReader))
        }
        jsonReader.endArray()
        return programs
    }

    private fun parseProgram(jsonReader: JsonReader): EpgProgram {
        var id = ""
        var startTime = ""
        var stopTime = ""
        var title = ""
        var description = ""

        jsonReader.beginObject()
        while (jsonReader.hasNext()) {
            when (jsonReader.nextName()) {
                "id" -> id = jsonReader.safeNextString(MAX_FIELD_LENGTH_ID)
                "start_time" -> startTime = jsonReader.safeNextString(MAX_FIELD_LENGTH_TIME)
                "stop_time" -> stopTime = jsonReader.safeNextString(MAX_FIELD_LENGTH_TIME)
                "title" -> title = jsonReader.safeNextString(MAX_FIELD_LENGTH_TITLE)
                "description" -> description = when (jsonReader.peek()) {
                    JsonToken.NULL -> {
                        jsonReader.nextNull()
                        ""
                    }
                    JsonToken.STRING -> jsonReader.safeNextString(MAX_FIELD_LENGTH_DESCRIPTION)
                    else -> {
                        jsonReader.skipValue()
                        ""
                    }
                }
                else -> jsonReader.skipValue()
            }
        }
        jsonReader.endObject()

        return EpgProgram(id, startTime, stopTime, title, description)
    }

    private data class WindowKey(
        val epgUrl: String,
        val tvgId: String,
        val fromUtcMillis: Long,
        val toUtcMillis: Long
    )

    private data class BatchWindowKey(
        val epgUrl: String,
        val sortedTvgIds: List<String>,
        val fromUtcMillis: Long,
        val toUtcMillis: Long
    )

    private data class FetchResult(
        val programsByTvgId: Map<String, List<EpgProgram>>,
        val lastEpgUpdateAt: String
    )

    private data class SingleFetchResult(
        val programs: List<EpgProgram>,
        val lastEpgUpdateAt: String
    )

    private data class InFlight<T>(
        val deferred: Deferred<T>,
        var waiters: Int = 0
    )
}

private const val WINDOW_CACHE_CAPACITY = 32
private const val CHANNEL_CACHE_CAPACITY = 128
private const val MAX_PROGRAMS_PER_CHANNEL = 512
private const val MAX_FIELD_LENGTH_ID = 128
private const val MAX_FIELD_LENGTH_TIME = 64
private const val MAX_FIELD_LENGTH_TITLE = 256
private const val MAX_FIELD_LENGTH_DESCRIPTION = 1_024
