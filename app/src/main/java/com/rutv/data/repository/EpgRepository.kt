@file:Suppress("unused")

package com.rutv.data.repository

import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.rutv.data.model.EpgChannelRequest
import com.rutv.data.model.EpgHealthResponse
import com.rutv.data.model.EpgProgram
import com.rutv.data.model.EpgRequest
import com.rutv.data.model.EpgResponse
import com.rutv.util.EpgConstants
import com.rutv.util.Result
import com.rutv.util.logDebug
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.Volatile
import kotlin.math.abs

@UnstableApi
@Singleton
class EpgRepository @Inject constructor(
    private val gson: Gson
) {

    private val windowCacheLock = Any()
    private val windowCache =
        object : LinkedHashMap<WindowKey, List<EpgProgram>>(WINDOW_CACHE_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WindowKey, List<EpgProgram>>?): Boolean {
                return size > WINDOW_CACHE_CAPACITY
            }
        }
    private val windowInFlight = mutableMapOf<WindowKey, Deferred<List<EpgProgram>>>()

    private val channelProgramsLock = Any()
    private val channelPrograms =
        object : LinkedHashMap<String, MutableList<EpgProgram>>(CHANNEL_CACHE_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<EpgProgram>>?): Boolean {
                return size > CHANNEL_CACHE_CAPACITY
            }
        }

    private var currentProgramsCache: Map<String, EpgProgram?>? = null
    private var currentProgramsCacheTime: Long = 0L
    private val currentProgramsCacheTtl = 60_000L

    private var lastKnownTimezoneId: String = TimeZone.getDefault().id
    private var lastKnownUtcOffsetMinutes: Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    enum class TimeChangeTrigger {
        TIMEZONE,
        TIME_SET,
        DATE,
        UNKNOWN
    }

    enum class TimeChangeResult {
        NONE,
        CLOCK_CHANGED,
        TIMEZONE_CHANGED
    }

    suspend fun checkHealth(epgUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            logDebug { "Checking EPG service health: $epgUrl/health" }
            val connection = (URL("$epgUrl/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = EpgConstants.EPG_HEALTH_TIMEOUT_MS
                readTimeout = EpgConstants.EPG_HEALTH_TIMEOUT_MS
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val healthResponse = gson.fromJson(response, EpgHealthResponse::class.java)
                connection.disconnect()
                Result.Success(healthResponse.isHealthy)
            } else {
                connection.disconnect()
                Timber.w("EPG health check failed with code: $responseCode")
                Result.Error(Exception("Health check failed: $responseCode"))
            }
        } catch (e: javax.net.ssl.SSLException) {
            Timber.e(e, "SSL/TLS error during EPG health check")
            Result.Error(Exception("SSL connection error. Please check your network connection.", e))
        } catch (e: java.net.SocketException) {
            Timber.e(e, "Network error during EPG health check")
            Result.Error(Exception("Network error. Please check your connection.", e))
        } catch (e: java.net.UnknownHostException) {
            Timber.e(e, "Host resolution error for EPG URL")
            Result.Error(Exception("Cannot reach EPG server. Please verify the URL.", e))
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error during EPG health check")
            Result.Error(e)
        }
    }

    fun handleSystemTimeOrTimezoneChange(
        trigger: TimeChangeTrigger,
        now: Long = System.currentTimeMillis()
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
            currentProgramsCache = null
            currentProgramsCacheTime = 0
            return TimeChangeResult.CLOCK_CHANGED
        }

        logDebug {
            "Ignoring time change trigger $trigger (timezone=${timezone.id}, offsetMinutes=$offsetMinutes, " +
                "cachedTimezone=$lastKnownTimezoneId, cachedOffset=$lastKnownUtcOffsetMinutes)"
        }
        return TimeChangeResult.NONE
    }

    suspend fun getWindowedProgramsForChannel(
        epgUrl: String,
        tvgId: String,
        fromUtcMillis: Long,
        toUtcMillis: Long
    ): List<EpgProgram> = coroutineScope {
        val key = WindowKey(epgUrl, tvgId, fromUtcMillis, toUtcMillis)
        synchronized(windowCacheLock) {
            windowCache[key]?.let { return@coroutineScope it }
        }

        val deferred = synchronized(windowCacheLock) {
            windowInFlight[key] ?: async(Dispatchers.IO) {
                fetchSingleChannelWindow(epgUrl, tvgId, fromUtcMillis, toUtcMillis)
            }.also { windowInFlight[key] = it }
        }

        try {
            val result = deferred.await()
            synchronized(windowCacheLock) {
                windowCache[key] = result
            }
            rememberProgramsForChannel(tvgId, result)
            cacheCurrentProgramSnapshot(tvgId, result)
            result
        } finally {
            synchronized(windowCacheLock) {
                windowInFlight.remove(key)
            }
        }
    }

    fun getCurrentProgram(tvgId: String): EpgProgram? {
        val now = System.currentTimeMillis()
        currentProgramsCache?.let { cache ->
            if (now - currentProgramsCacheTime < currentProgramsCacheTtl) {
                return cache[tvgId]
            }
        }

        val programs = synchronized(channelProgramsLock) {
            channelPrograms[tvgId]?.toList()
        } ?: return null

        val current = programs.firstOrNull { it.isCurrent(now) }
        val cache = (currentProgramsCache ?: emptyMap()).toMutableMap()
        cache[tvgId] = current
        currentProgramsCache = cache
        currentProgramsCacheTime = now
        return current
    }

    fun getProgramsForChannel(tvgId: String): List<EpgProgram> {
        return synchronized(channelProgramsLock) {
            channelPrograms[tvgId]?.toList()
        } ?: emptyList()
    }

    fun clearCache() {
        synchronized(windowCacheLock) {
            windowCache.clear()
            windowInFlight.clear()
        }
        synchronized(channelProgramsLock) {
            channelPrograms.clear()
        }
        currentProgramsCache = null
        currentProgramsCacheTime = 0
        logDebug { "EPG cache cleared (lazy windows + current programs)" }
    }

    private fun cacheCurrentProgramSnapshot(tvgId: String, programs: List<EpgProgram>) {
        val now = System.currentTimeMillis()
        val cache = currentProgramsCache?.toMutableMap() ?: mutableMapOf()
        cache[tvgId] = programs.firstOrNull { it.isCurrent(now) }
        currentProgramsCache = cache
        currentProgramsCacheTime = now
    }

    private fun rememberProgramsForChannel(tvgId: String, programs: List<EpgProgram>) {
        if (programs.isEmpty()) return
        synchronized(channelProgramsLock) {
            val existing = channelPrograms[tvgId]?.toList() ?: emptyList()
            val merged = LinkedHashMap<String, EpgProgram>(existing.size + programs.size)
            fun key(program: EpgProgram) = program.id.ifBlank { "${program.startUtcMillis}:${program.title}" }
            existing.forEach { merged[key(it)] = it }
            programs.forEach { merged[key(it)] = it }
            val sorted = merged.values.sortedBy { it.startUtcMillis }
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
                        logDebug { "Parsing EPG map with streaming parser..." }
                        var channelCount = 0
                        jsonReader.beginObject()
                        while (jsonReader.hasNext()) {
                            val channelId = jsonReader.nextName()
                            val programs = parsePrograms(jsonReader)
                            epgMap[channelId] = programs
                            channelCount++
                            if (channelCount % 50 == 0) {
                                logDebug { "Parsed $channelCount channels so far..." }
                            }
                        }
                        jsonReader.endObject()
                        logDebug { "Finished parsing $channelCount channels" }
                    }
                    else -> jsonReader.skipValue()
                }
            }
            jsonReader.endObject()
            return EpgResponse(updateMode, timestamp, channelsRequested, channelsFound, totalPrograms, epgMap)
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
private const val CHANNEL_CACHE_CAPACITY = 48
private const val MAX_PROGRAMS_PER_CHANNEL = 512
private const val MAX_FIELD_LENGTH_ID = 128
private const val MAX_FIELD_LENGTH_TIME = 64
private const val MAX_FIELD_LENGTH_TITLE = 256
private const val MAX_FIELD_LENGTH_DESCRIPTION = 1_024
@Volatile
private var truncationWarningLogged = false
