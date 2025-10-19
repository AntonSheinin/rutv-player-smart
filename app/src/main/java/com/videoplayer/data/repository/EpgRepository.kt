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
import timber.log.Timber
import java.io.File
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

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

    // Cache for current programs only (lightweight)
    private var currentProgramsCache: Map<String, EpgProgram?>? = null
    private var currentProgramsCacheTime: Long = 0
    private val currentProgramsCacheTtl = 60_000L // 1 minute

    /**
     * Check if EPG service is healthy
     */
    suspend fun checkHealth(epgUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (epgUrl.isBlank()) {
            Timber.w("EPG URL not configured")
            return@withContext Result.Error(Exception("EPG URL is empty"))
        }

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
        channels: List<Channel>
    ): Result<EpgResponse> = withContext(Dispatchers.IO) {
        if (epgUrl.isBlank()) {
            Timber.w("EPG URL not configured")
            return@withContext Result.Error(Exception("EPG URL is empty"))
        }

        val channelsWithEpg = channels.filter { it.hasEpg }
        if (channelsWithEpg.isEmpty()) {
            Timber.w("No channels with EPG data")
            return@withContext Result.Error(Exception("No channels with EPG data"))
        }

        Timber.d("Fetching EPG for ${channelsWithEpg.size} channels")

        try {
            val deviceTimezone = TimeZone.getDefault().id
            val timezoneOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (1000 * 60 * 60)
            Timber.d("━━━ EPG REQUEST ━━━")
            Timber.d("Device timezone: $deviceTimezone (UTC${if (timezoneOffset >= 0) "+" else ""}$timezoneOffset)")
            Timber.d("Channels to fetch: $channelsWithEpg.size")

            val epgRequest = EpgRequest(
                channels = channelsWithEpg.map {
                    EpgChannelRequest(xmltvId = it.tvgId, epgDepth = it.catchupDays)
                },
                update = "force",
                timezone = deviceTimezone
            )

            val requestBody = gson.toJson(epgRequest)
            Timber.d("Request body size: ${requestBody.length} bytes")
            Timber.d("First 3 channels: ${channelsWithEpg.take(3).joinToString { "${it.title}(${it.tvgId})" }}")

            val url = URL("$epgUrl/epg")
            Timber.d("POST $url")

            val startTime = System.currentTimeMillis()
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = Constants.EPG_CONNECT_TIMEOUT_MS
            connection.readTimeout = Constants.EPG_READ_TIMEOUT_MS
            connection.doOutput = true

            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray())
            }
            Timber.d("Request sent, waiting for response...")

            val responseCode = connection.responseCode
            val fetchDuration = System.currentTimeMillis() - startTime
            Timber.d("Response code: $responseCode (took ${fetchDuration}ms)")

            if (responseCode == 200) {
                Timber.d("━━━ EPG PARSING (STREAMING) ━━━")
                // Stream parse JSON token-by-token to avoid loading large response into memory
                val parseStartTime = System.currentTimeMillis()
                val epgResponse = connection.inputStream.bufferedReader().use { reader ->
                    parseEpgResponseStreaming(reader)
                }
                val parseDuration = System.currentTimeMillis() - parseStartTime

                if (epgResponse != null) {
                    Timber.d("Parse time: ${parseDuration}ms")
                    Timber.d("Channels requested: ${epgResponse.channelsRequested}")
                    Timber.d("Channels found: ${epgResponse.channelsFound}")
                    Timber.d("Total programs: ${epgResponse.totalPrograms}")
                    Timber.d("Update mode: ${epgResponse.updateMode}")
                    Timber.d("Timestamp: ${epgResponse.timestamp}")

                    // Log sample program times to verify timezone
                    val firstChannelWithPrograms = epgResponse.epg.entries.firstOrNull()
                    if (firstChannelWithPrograms != null && firstChannelWithPrograms.value.isNotEmpty()) {
                        val program = firstChannelWithPrograms.value.first()
                        Timber.d("Sample program: '${program.title}' (${program.startTime} - ${program.stopTime})")
                    }

                    Timber.d("━━━ EPG SAVED ━━━")
                    saveEpgData(epgResponse)
                    connection.disconnect()
                    Result.Success(epgResponse)
                } else {
                    Timber.e("EPG response is null")
                    connection.disconnect()
                    Result.Error(Exception("EPG response is null"))
                }
            } else {
                Timber.e("EPG fetch failed with code $responseCode")
                connection.disconnect()
                Result.Error(Exception("HTTP error: $responseCode"))
            }

        } catch (e: OutOfMemoryError) {
            Timber.e(e, "Out of memory fetching EPG")
            Result.Error(Exception("Out of memory: ${e.message}"))
        } catch (e: Exception) {
            Timber.e(e, "Error fetching EPG")
            Result.Error(e)
        }
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

                Timber.d("✓ EPG saved to disk: ${fileSizeMB}MB (${fileSizeKB}KB) in ${duration}ms")
                Timber.d("  Compression ratio: ~${programCount * 500 / (fileSizeKB * 1024)}:1")
            } catch (e: OutOfMemoryError) {
                Timber.w("⚠ Out of memory saving EPG to disk (${e.message}) - keeping memory cache only")
                // Don't rethrow - memory cache is still valid
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
        // Return memory cache if available
        cachedEpgData?.let { return it }

        // Try to load from disk
        return try {
            if (!epgFile.exists()) {
                Timber.d("No EPG data file found")
                return null
            }

            Timber.d("Loading EPG from disk with streaming parser...")
            val startTime = System.currentTimeMillis()

            // Stream JSON from GZIP compressed file using streaming parser
            val epgResponse = epgFile.inputStream().buffered().use { fileIn ->
                java.util.zip.GZIPInputStream(fileIn).buffered().use { gzipIn ->
                    gzipIn.reader().use { reader ->
                        parseEpgResponseStreaming(reader)
                    }
                }
            }

            val duration = System.currentTimeMillis() - startTime
            cachedEpgData = epgResponse
            if (epgResponse != null) {
                Timber.d("✓ Loaded EPG data: ${epgResponse.totalPrograms} programs in ${duration}ms")
            } else {
                Timber.e("Failed to parse EPG data from disk")
            }
            epgResponse
        } catch (e: java.util.zip.ZipException) {
            // Handle legacy uncompressed files
            Timber.w("EPG file is not GZIP compressed, attempting plain JSON load with streaming...")
            try {
                val epgResponse = epgFile.inputStream().bufferedReader().use { reader ->
                    parseEpgResponseStreaming(reader)
                }
                cachedEpgData = epgResponse
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
            if (epgFile.exists() && !epgFile.delete()) {
                Timber.w("Failed to delete EPG cache file after OOM")
            }
            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to load EPG data")
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
    suspend fun refreshCurrentProgramsCache(
        batchSize: Int = 100,
        onBatch: (Map<String, EpgProgram?>) -> Unit = {}
    ) {
        val epgData = cachedEpgData ?: loadEpgData() ?: return

        val cache = mutableMapOf<String, EpgProgram?>()
        val batch = mutableMapOf<String, EpgProgram?>()
        val now = System.currentTimeMillis()
        var processed = 0

        for ((tvgId, programs) in epgData.epg) {
            val current = programs.firstOrNull { it.isCurrent(now) }
            cache[tvgId] = current
            batch[tvgId] = current
            processed++

            if (batch.size >= batchSize) {
                onBatch(batch.toMap())
                batch.clear()
                kotlinx.coroutines.yield()
            }
        }

        if (batch.isNotEmpty()) {
            onBatch(batch.toMap())
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
        val now = System.currentTimeMillis()
        val cache = currentProgramsCache
        return if (cache == null || now - currentProgramsCacheTime >= currentProgramsCacheTtl) {
            refreshCurrentProgramsCache()
            currentProgramsCache ?: emptyMap()
        } else {
            cache
        }
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
