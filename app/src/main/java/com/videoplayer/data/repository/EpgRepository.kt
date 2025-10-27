@file:Suppress("unused")

package com.videoplayer.data.repository

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.videoplayer.data.model.*
import com.videoplayer.util.Constants
import com.videoplayer.util.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import timber.log.Timber
import java.io.File
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.Volatile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


/**
 * Repository for EPG data
 */
@UnstableApi
@Singleton
class EpgRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {

    private val epgFile = File(context.filesDir, "epg_data.json")
    private var cachedEpgData: EpgResponse? = null
    @Volatile private var cachedBounds: EpgBounds? = null

    // Cache for current programs only (lightweight)
    private var currentProgramsCache: Map<String, EpgProgram?>? = null
    private var currentProgramsCacheTime: Long = 0
    private val currentProgramsCacheTtl = 60_000L // 1 minute
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

    /**
     * Check if EPG service is healthy
     */
    suspend fun checkHealth(epgUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Checking EPG service health: $epgUrl/health")
            val healthUrl = URL("$epgUrl/health")
            val connection = healthUrl.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = Constants.EPG_HEALTH_TIMEOUT_MS
            connection.readTimeout = Constants.EPG_HEALTH_TIMEOUT_MS

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val healthResponse = gson.fromJson(response, EpgHealthResponse::class.java)
                connection.disconnect()

                Timber.d("EPG health check: ${if (healthResponse.isHealthy) "OK" else "NOT OK"}")
                Result.Success(healthResponse.isHealthy)
            } else {
                Timber.w("EPG health check failed with code: $responseCode")
                connection.disconnect()
                Result.Error(Exception("Health check failed: $responseCode"))
            }
        } catch (e: Exception) {
            Timber.e(e, "EPG service health check error")
            Result.Error(e)
        }
    }

    /**
     * Fetch EPG data from server
     */
    suspend fun fetchEpgData(
        epgUrl: String,
        channels: List<Channel>,
        window: EpgWindow
    ): Result<EpgResponse> = withContext(Dispatchers.IO) {
        if (epgUrl.isBlank()) {
            Timber.w("EPG URL not configured")
            return@withContext Result.Error(Exception("EPG URL is empty"))
        }

        val channelsWithEpg = channels.filter { it.hasEpg }
        if (channelsWithEpg.isEmpty()) {
            Timber.w("No channels with EPG identifiers")
            return@withContext Result.Error(Exception("No channels with EPG identifiers"))
        }

        val batchSize = max(1, Constants.EPG_FETCH_BATCH_SIZE)
        val totalBatches = (channelsWithEpg.size + batchSize - 1) / batchSize
        Timber.d("Fetching EPG for ${channelsWithEpg.size} channels in $totalBatches batch(es)")

        val deviceTimezone = TimeZone.getDefault().id
        val timezoneOffsetHours = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (1000 * 60 * 60)
        Timber.d("EPG request timezone: $deviceTimezone (UTC${if (timezoneOffsetHours >= 0) "+" else ""}$timezoneOffsetHours)")

        val fromUtcMillis = window.fromUtcMillis
        val toUtcMillis = window.toUtcMillis
        val fromIso = Instant.ofEpochMilli(fromUtcMillis).toString()
        val toIso = Instant.ofEpochMilli(toUtcMillis).toString()
        Timber.d("EPG window request: from=$fromIso to=$toIso")

        val aggregationState = EpgAggregationState()
        val overallStartTime = System.currentTimeMillis()
        var batchIndex = 0

        for (chunk in channelsWithEpg.chunked(batchSize)) {
            batchIndex++
            Timber.d("Fetching EPG batch $batchIndex/$totalBatches (${chunk.size} channels)")
            when (
                val batchResult = fetchEpgBatch(
                    epgUrl = epgUrl,
                    channels = chunk,
                    fromUtcMillis = fromUtcMillis,
                    toUtcMillis = toUtcMillis,
                    fromIso = fromIso,
                    toIso = toIso,
                    deviceTimezone = deviceTimezone,
                    timezoneOffsetHours = timezoneOffsetHours
                )
            ) {
                is Result.Success -> {
                    val batchResponse = batchResult.data
                    updateCachesWithBatch(aggregationState, batchResponse, channelsWithEpg.size)
                    Timber.d(
                        "EPG batch $batchIndex parsed ${batchResponse.epg.size} channels (${batchResponse.totalPrograms} programs)"
                    )
                    yield()
                }
                is Result.Error -> {
                    Timber.e("EPG batch $batchIndex failed: ${batchResult.message}")
                    return@withContext batchResult
                }
                is Result.Loading -> {
                    Timber.w("EPG batch $batchIndex returned unexpected loading state")
                    return@withContext Result.Error(Exception("Unexpected loading state"))
                }
            }
        }

        val totalDuration = System.currentTimeMillis() - overallStartTime
        val aggregatedResponse = aggregationState.toResponse(channelsWithEpg.size)

        Timber.d(
            "EPG batches complete: ${aggregatedResponse.channelsFound}/${aggregatedResponse.channelsRequested} channels, ${aggregatedResponse.totalPrograms} programs collected in ${totalDuration}ms"
        )

        saveEpgData(aggregatedResponse)
        Result.Success(aggregatedResponse)
    }

    private fun fetchEpgBatch(
        epgUrl: String,
        channels: List<Channel>,
        fromUtcMillis: Long,
        toUtcMillis: Long,
        fromIso: String,
        toIso: String,
        deviceTimezone: String,
        timezoneOffsetHours: Int
    ): Result<EpgResponse> {
        var connection: HttpURLConnection? = null
        return try {
            val epgRequest = EpgRequest(
                channels = channels.map { EpgChannelRequest(xmltvId = it.tvgId) },
                timezone = deviceTimezone,
                fromDate = fromIso,
                toDate = toIso
            )

            val requestBody = gson.toJson(epgRequest)
            Timber.d(
                "EPG batch payload: ${channels.size} channels, body=${requestBody.length} bytes. " +
                    "Sample: ${channels.take(3).joinToString { "${it.title}(${it.tvgId})" }}"
            )

            val url = URL("$epgUrl/epg")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = Constants.EPG_CONNECT_TIMEOUT_MS
                readTimeout = Constants.EPG_READ_TIMEOUT_MS
                doOutput = true
            }

            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray())
            }

            Timber.d(
                "EPG batch request sent (timezone=$deviceTimezone, UTC${if (timezoneOffsetHours >= 0) "+" else ""}$timezoneOffsetHours)"
            )

            val responseStart = System.currentTimeMillis()
            val responseCode = connection.responseCode
            val fetchDuration = System.currentTimeMillis() - responseStart
            Timber.d("EPG batch HTTP $responseCode in ${fetchDuration}ms for ${channels.size} channels")

            if (responseCode == 200) {
                val parseStart = System.currentTimeMillis()
                val epgResponse = connection.inputStream.bufferedReader().use { reader ->
                    parseEpgResponseStreaming(reader)
                }
                val parseDuration = System.currentTimeMillis() - parseStart

                if (epgResponse != null) {
                    Timber.d(
                        "EPG batch parsed in ${parseDuration}ms: " +
                            "${epgResponse.channelsFound}/${epgResponse.channelsRequested} channels, " +
                            "${epgResponse.totalPrograms} programs"
                    )
                    val trimmedResponse = trimEpgToWindow(
                        epgResponse,
                        fromUtcMillis,
                        toUtcMillis
                    )
                    return Result.Success(trimmedResponse)
                } else {
                    Timber.e("EPG batch response is null")
                    return Result.Error(Exception("EPG response is null"))
                }
            } else {
                Timber.e("EPG batch failed with HTTP $responseCode")
                return Result.Error(Exception("HTTP error: $responseCode"))
            }
        } catch (e: OutOfMemoryError) {
            Timber.e(e, "Out of memory fetching EPG batch")
            return Result.Error(Exception("Out of memory: ${e.message}"))
        } catch (e: Exception) {
            Timber.e(e, "Error fetching EPG batch")
            return Result.Error(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun updateCachesWithBatch(
        aggregationState: EpgAggregationState,
        batchResponse: EpgResponse,
        channelsRequested: Int,
        now: Long = System.currentTimeMillis()
    ) {
        aggregationState.applyBatch(batchResponse)
        val aggregatedResponse = aggregationState.toResponse(channelsRequested)
        cachedEpgData = aggregatedResponse
        cachedBounds = calculateBounds(aggregatedResponse)
        updateCurrentProgramsCacheWithBatch(batchResponse, now)
    }

    private fun updateCurrentProgramsCacheWithBatch(
        batchResponse: EpgResponse,
        now: Long
    ) {
        val cache = currentProgramsCache?.toMutableMap() ?: mutableMapOf()
        batchResponse.epg.forEach { (tvgId, programs) ->
            val currentProgram = programs.firstOrNull { it.isCurrent(now) }
            cache[tvgId] = currentProgram
        }
        currentProgramsCache = cache
        currentProgramsCacheTime = now
    }

    private class EpgAggregationState {
        private val epgMap = linkedMapOf<String, List<EpgProgram>>()
        private val channelProgramCounts = mutableMapOf<String, Int>()
        private var totalProgramsCount: Int = 0
        private var firstUpdateMode: String? = null
        private var firstTimestamp: String? = null

        fun applyBatch(batch: EpgResponse) {
            if (firstUpdateMode.isNullOrEmpty() && batch.updateMode.isNotEmpty()) {
                firstUpdateMode = batch.updateMode
            }
            if (firstTimestamp.isNullOrEmpty() && batch.timestamp.isNotEmpty()) {
                firstTimestamp = batch.timestamp
            }

            batch.epg.forEach { (channelId, programs) ->
                val previousCount = channelProgramCounts[channelId]
                if (previousCount != null) {
                    totalProgramsCount -= previousCount
                }
                channelProgramCounts[channelId] = programs.size
                epgMap[channelId] = programs
                totalProgramsCount += programs.size
            }
        }

        fun toResponse(channelsRequested: Int): EpgResponse = EpgResponse(
            updateMode = firstUpdateMode.orEmpty(),
            timestamp = firstTimestamp ?: Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
            channelsRequested = channelsRequested,
            channelsFound = epgMap.size,
            totalPrograms = totalProgramsCount,
            epg = epgMap.toMap()
        )
    }

    /**
     * Parse EPG response using streaming JSON parser
     * This avoids loading the entire 255MB response into memory at once
     */
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
                        // Parse epg object incrementally - one channel at a time
                        Timber.d("Parsing EPG map with streaming parser...")
                        var channelCount = 0
                        jsonReader.beginObject()
                        while (jsonReader.hasNext()) {
                            val channelId = jsonReader.nextName()
                            val programs = parsePrograms(jsonReader)
                            epgMap[channelId] = programs
                            channelCount++

                            // Log progress every 50 channels to track memory usage
                            if (channelCount % 50 == 0) {
                                Timber.d("Parsed $channelCount channels so far...")
                            }
                        }
                        jsonReader.endObject()
                        Timber.d("Finished parsing $channelCount channels")
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

    /**
     * Parse programs array for a single channel
     */
    private fun parsePrograms(jsonReader: JsonReader): List<EpgProgram> {
        val programs = mutableListOf<EpgProgram>()
        jsonReader.beginArray()
        while (jsonReader.hasNext()) {
            programs.add(parseProgram(jsonReader))
        }
        jsonReader.endArray()
        return programs
    }

    /**
     * Parse a single EPG program
     */
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

    /**
     * Save EPG data to cache using streaming + GZIP compression
     *
     * Best practices implemented:
     * 1. Streaming JSON write - avoids loading full JSON string in memory
     * 2. GZIP compression - reduces 144MB to ~10-20MB
     * 3. Buffered IO - efficient disk writes
     */
    private fun saveEpgData(epgResponse: EpgResponse) {
        try {
            // Cache in memory first
            cachedEpgData = epgResponse
            cachedBounds = calculateBounds(epgResponse)
            val programCount = epgResponse.totalPrograms
            val channelCount = epgResponse.epg.size
            Timber.d("EPG data cached in memory: $programCount programs across $channelCount channels")

            try {
                Timber.d("Saving EPG to disk with streaming + GZIP...")
                val startTime = System.currentTimeMillis()

                // Stream JSON directly to GZIP compressed file
                epgFile.outputStream().buffered().use { fileOut ->
                    java.util.zip.GZIPOutputStream(fileOut).buffered().use { gzipOut ->
                        gzipOut.writer().use { writer ->
                            gson.toJson(epgResponse, writer)
                        }
                    }
                }

                val duration = System.currentTimeMillis() - startTime
                val fileSizeKB = epgFile.length() / 1024
                val fileSizeMB = fileSizeKB / 1024

                Timber.d("EPG saved to disk: ${fileSizeMB}MB (${fileSizeKB}KB) in ${duration}ms")
                if (fileSizeKB > 0) {
                    val compressionRatio = (programCount * 500L) / (fileSizeKB * 1024L)
                    Timber.d("  Compression ratio: ~$compressionRatio:1")
                }
            } catch (e: OutOfMemoryError) {
                Timber.w("Out of memory saving EPG to disk (${e.message}) - keeping memory cache only")
            } catch (e: Exception) {
                Timber.e(e, "Failed to write EPG to disk - keeping memory cache only")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save EPG data")
        }
    }
    /**
     * Load EPG data from cache (with GZIP decompression support)
     * Uses streaming parser to avoid OutOfMemoryError
     */
    fun loadEpgData(): EpgResponse? {
        cachedEpgData?.let { return it }

        return try {
            if (!epgFile.exists()) {
                Timber.d("No EPG data file found")
                return null
            }

            Timber.d("Loading EPG from disk with streaming parser...")
            val startTime = System.currentTimeMillis()

            val epgResponse = epgFile.inputStream().buffered().use { fileIn ->
                java.util.zip.GZIPInputStream(fileIn).buffered().use { gzipIn ->
                    gzipIn.reader().use { reader ->
                        parseEpgResponseStreaming(reader)
                    }
                }
            }

            val duration = System.currentTimeMillis() - startTime
            cachedEpgData = epgResponse
            cachedBounds = epgResponse?.let { calculateBounds(it) }
            if (epgResponse != null) {
                Timber.d("Loaded EPG data: ${epgResponse.totalPrograms} programs in ${duration}ms")
            } else {
                Timber.e("Failed to parse EPG data from disk")
            }
            epgResponse
        } catch (e: java.util.zip.ZipException) {
            Timber.w("EPG file is not GZIP compressed, attempting plain JSON load with streaming...")
            try {
                val epgResponse = epgFile.inputStream().bufferedReader().use { reader ->
                    parseEpgResponseStreaming(reader)
                }
                cachedEpgData = epgResponse
                cachedBounds = epgResponse?.let { calculateBounds(it) }
                if (epgResponse != null) {
                    Timber.d("Loaded EPG data (legacy format): ${epgResponse.totalPrograms} programs")
                } else {
                    Timber.e("Failed to parse legacy EPG data")
                }
                epgResponse
            } catch (_: Exception) {
                Timber.e("Failed to load legacy EPG data")
                null
            }
        } catch (e: OutOfMemoryError) {
            Timber.e(e, "Out of memory while loading cached EPG data (file size=${epgFile.length()} bytes). Clearing cache.")
            cachedEpgData = null
            cachedBounds = null
            if (epgFile.exists() && !epgFile.delete()) {
                Timber.w("Failed to delete EPG cache file after OOM")
            }
            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to load EPG data")
            cachedBounds = null
            null
        }
    }

    /**
     * Get current program for a channel (optimized with caching)
     * This is called frequently from channel list, so we cache results
     */
    fun getCurrentProgram(tvgId: String): EpgProgram? {
        // Check cache first (avoids loading full EPG repeatedly)
        val now = System.currentTimeMillis()
        if (currentProgramsCache != null && now - currentProgramsCacheTime < currentProgramsCacheTtl) {
            return currentProgramsCache?.get(tvgId)
        }

        // Cache miss - need to load from EPG data
        val epgData = cachedEpgData ?: loadEpgData()
        if (epgData == null) {
            return null
        }

        val programs = epgData.epg[tvgId]
        if (programs == null || programs.isEmpty()) {
            return null
        }

        return programs.firstOrNull { it.isCurrent() }
    }

    /**
     * Rebuild the current-program cache in batches to avoid long blocking work.
     * The [onBatch] callback receives incremental updates that can be applied to UI state.
     */
    suspend fun refreshCurrentProgramsCache(batchSize: Int = 100) {
        val epgData = cachedEpgData ?: loadEpgData() ?: return

        val cache = mutableMapOf<String, EpgProgram?>()
        val now = System.currentTimeMillis()

        for ((tvgId, programs) in epgData.epg) {
            val current = programs.firstOrNull { it.isCurrent(now) }
            cache[tvgId] = current
            if (cache.size % batchSize == 0) {
                kotlinx.coroutines.yield()
            }
        }

        currentProgramsCache = cache
        currentProgramsCacheTime = System.currentTimeMillis()
        Timber.d("EPG: Rebuilt current programs cache for ${cache.size} channels")
    }

    /**
     * Get all programs for a channel
     */
    fun getProgramsForChannel(tvgId: String): List<EpgProgram> {
        val epgData = cachedEpgData ?: loadEpgData() ?: return emptyList()
        return epgData.epg[tvgId] ?: emptyList()
    }

    /**
     * Snapshot of current programs for all channels.
     * Ensures the cache is populated before returning.
     */
    fun getCurrentProgramsSnapshot(): Map<String, EpgProgram?> {
        return currentProgramsCache ?: emptyMap()
    }

    fun calculateWindow(
        channels: List<Channel>,
        epgDaysAhead: Int,
        now: Instant = Instant.now()
    ): EpgWindow {
        val utcZone = ZoneOffset.UTC
        val zonedNow = now.atZone(utcZone)
        val maxCatchupDays = channels
            .filter { it.hasEpg }
            .maxOfOrNull { max(0, it.catchupDays) }
            ?: 0
        val sanitizedDaysAhead = max(0, epgDaysAhead)
        val fromInstant = zonedNow.toLocalDate()
            .minusDays(maxCatchupDays.toLong())
            .atStartOfDay()
            .toInstant(utcZone)
        val toInstant = zonedNow.toLocalDate()
            .plusDays(sanitizedDaysAhead.toLong())
            .atTime(LocalTime.of(23, 59, 59))
            .toInstant(utcZone)
        return EpgWindow(
            fromUtcMillis = fromInstant.toEpochMilli(),
            toUtcMillis = toInstant.toEpochMilli()
        )
    }

    fun getCachedEpgBounds(): EpgBounds? {
        cachedBounds?.let { return it }
        val data = cachedEpgData ?: loadEpgData() ?: return null
        val bounds = calculateBounds(data) ?: return null
        cachedBounds = bounds
        return bounds
    }

    fun coversWindow(window: EpgWindow, toleranceMillis: Long = 60_000L): Boolean {
        val bounds = getCachedEpgBounds() ?: return false
        val adjustedEarliest = bounds.earliestUtcMillis <= window.fromUtcMillis + toleranceMillis
        val adjustedLatest = bounds.latestUtcMillis >= window.toUtcMillis - toleranceMillis
        return adjustedEarliest && adjustedLatest
    }

    private fun trimEpgToWindow(
        response: EpgResponse,
        fromUtcMillis: Long,
        toUtcMillis: Long
    ): EpgResponse {
        val filteredMap = response.epg.mapNotNull { (channelId, programs) ->
            val filtered = programs.filter { program ->
                val start = program.startUtcMillis
                val stop = program.stopUtcMillis
                val afterFrom = stop >= fromUtcMillis
                val beforeTo = start <= toUtcMillis
                afterFrom && beforeTo
            }
            if (filtered.isEmpty()) null else channelId to filtered
        }.toMap()
        val totalPrograms = filteredMap.values.sumOf { it.size }
        return response.copy(
            channelsFound = filteredMap.size,
            totalPrograms = totalPrograms,
            epg = filteredMap
        )
    }

    private fun calculateBounds(response: EpgResponse): EpgBounds? {
        var earliest = Long.MAX_VALUE
        var latest = Long.MIN_VALUE
        response.epg.values.forEach { programs ->
            programs.forEach { program ->
                val start = program.startUtcMillis
                val stop = program.stopUtcMillis
                if (start > 0) {
                    earliest = min(earliest, start)
                }
                if (stop > 0) {
                    latest = max(latest, stop)
                }
            }
        }
        if (earliest == Long.MAX_VALUE || latest == Long.MIN_VALUE) return null
        return EpgBounds(earliest, latest)
    }

    fun handleSystemTimeOrTimezoneChange(
        trigger: TimeChangeTrigger,
        now: Long = System.currentTimeMillis()
    ): TimeChangeResult {
        val timezone = TimeZone.getDefault()
        val offsetMinutes = timezone.getOffset(now) / 60_000
        val timezoneIdChanged = timezone.id != lastKnownTimezoneId
        val offsetChanged = offsetMinutes != lastKnownUtcOffsetMinutes

        if (timezoneIdChanged || offsetChanged) {
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
            Timber.d("Timezone change broadcast received but timezone snapshot unchanged; no cache updates needed")
            return TimeChangeResult.NONE
        }

        if (trigger == TimeChangeTrigger.TIME_SET || trigger == TimeChangeTrigger.DATE) {
            Timber.i("System clock adjusted (${trigger.name.lowercase()}), refreshing cached programs")
            currentProgramsCache = null
            currentProgramsCacheTime = 0
            cachedBounds = null
            return TimeChangeResult.CLOCK_CHANGED
        }

        Timber.d(
            "Ignoring time change trigger $trigger (timezone=${timezone.id}, offsetMinutes=$offsetMinutes, " +
                "cachedTimezone=$lastKnownTimezoneId, cachedOffset=$lastKnownUtcOffsetMinutes)"
        )
        return TimeChangeResult.NONE
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

    /**
     * Clear EPG cache
     */
    fun clearCache() {
        cachedEpgData = null
        cachedBounds = null
        currentProgramsCache = null
        currentProgramsCacheTime = 0
        if (epgFile.exists()) {
            epgFile.delete()
        }
        Timber.d("EPG cache cleared (including current programs cache)")
    }

}

private const val MAX_FIELD_LENGTH_ID = 128
private const val MAX_FIELD_LENGTH_TIME = 64
private const val MAX_FIELD_LENGTH_TITLE = 256
private const val MAX_FIELD_LENGTH_DESCRIPTION = 1_024
private var truncationWarningLogged = false

data class EpgWindow(
    val fromUtcMillis: Long,
    val toUtcMillis: Long
) {
    val fromInstant: Instant
        get() = Instant.ofEpochMilli(fromUtcMillis)
    val toInstant: Instant
        get() = Instant.ofEpochMilli(toUtcMillis)
}

data class EpgBounds(
    val earliestUtcMillis: Long,
    val latestUtcMillis: Long
)


