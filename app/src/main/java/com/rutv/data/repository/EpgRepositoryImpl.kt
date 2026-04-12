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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val windowInFlight = mutableMapOf<WindowKey, Deferred<List<EpgProgram>>>()

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
        windowCacheMutex.withLock {
            windowCache[key]?.let { return@coroutineScope it }
        }

        val deferred = windowCacheMutex.withLock {
            // Important: we store the Deferred so concurrent callers share one network request.
            windowInFlight[key] ?: async(Dispatchers.IO) {
                fetchSingleChannelWindow(epgUrl, tvgId, fromUtcMillis, toUtcMillis)
            }.also { windowInFlight[key] = it }
        }

        try {
            val result = deferred.await()
            windowCacheMutex.withLock {
                windowCache[key] = result
            }
            rememberProgramsForChannel(tvgId, result)
            cacheCurrentProgramSnapshot(tvgId, result)
            result
        } finally {
            windowCacheMutex.withLock {
                windowInFlight.remove(key)
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
            windowCache.clear()
            windowInFlight.clear()
        }
        channelDataMutex.withLock {
            channelPrograms.clear()
            currentProgramsCache = null
            currentProgramsCacheTime = 0
        }
        logDebug { "EPG cache cleared (lazy windows + current programs)" }
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

    private suspend fun rememberProgramsForChannel(tvgId: String, programs: List<EpgProgram>) {
        if (programs.isEmpty()) return
        channelDataMutex.withLock {
            val existing = channelPrograms[tvgId]?.toList() ?: emptyList()
            val merged = LinkedHashMap<String, EpgProgram>(existing.size + programs.size)
            fun key(program: EpgProgram) = program.id.ifBlank { "${program.startUtcMillis}:${program.title}" }
            existing.forEach { merged[key(it)] = it }
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

    private fun fetchSingleChannelWindow(
        epgUrl: String,
        tvgId: String,
        fromUtcMillis: Long,
        toUtcMillis: Long
    ): List<EpgProgram> {
        var connection: HttpURLConnection? = null
        return try {
            val deviceTimezone = TimeZone.getDefault().id
            val fromIso = Instant.ofEpochMilli(fromUtcMillis).toString()
            val toIso = Instant.ofEpochMilli(toUtcMillis).toString()

            // EPG backend expects a POST with:
            // - channel IDs (xmltv_id)
            // - a timezone ID string for server-side conversions
            // - optional ISO8601 from/to filters
            val request = EpgRequest(
                channels = listOf(EpgChannelRequest(xmltvId = tvgId)),
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
                Timber.e("EPG single-channel HTTP error: $code")
                return emptyList()
            }

            val response = connection.inputStream.bufferedReader().use { reader ->
                parseEpgResponseStreaming(reader)
            } ?: return emptyList()

            val programs = response.epg[tvgId] ?: emptyList()
            trimProgramsToWindow(programs, fromUtcMillis, toUtcMillis)
        } catch (e: javax.net.ssl.SSLException) {
            Timber.e(e, "SSL/TLS error fetching single-channel EPG")
            emptyList()
        } catch (e: java.net.SocketException) {
            Timber.e(e, "Network error fetching single-channel EPG")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            Timber.e(e, "Host resolution error for EPG URL")
            emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch single-channel EPG window")
            emptyList()
        } finally {
            connection?.disconnect()
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
            return EpgResponse(updateMode, timestamp, channelsRequested, channelsFound, totalPrograms, epgMap)
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
}

private const val WINDOW_CACHE_CAPACITY = 32
private const val CHANNEL_CACHE_CAPACITY = 128
private const val MAX_PROGRAMS_PER_CHANNEL = 512
private const val MAX_FIELD_LENGTH_ID = 128
private const val MAX_FIELD_LENGTH_TIME = 64
private const val MAX_FIELD_LENGTH_TITLE = 256
private const val MAX_FIELD_LENGTH_DESCRIPTION = 1_024
