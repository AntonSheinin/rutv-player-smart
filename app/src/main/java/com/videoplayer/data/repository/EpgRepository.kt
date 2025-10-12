package com.videoplayer.data.repository

import android.content.Context
import com.google.gson.Gson
import com.videoplayer.data.model.*
import com.videoplayer.util.Constants
import com.videoplayer.util.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for EPG data
 */
@Singleton
class EpgRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {

    private val epgFile = File(context.filesDir, "epg_data.json")
    private var cachedEpgData: EpgResponse? = null

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
            Timber.d("Device timezone: $deviceTimezone")

            val epgRequest = EpgRequest(
                channels = channelsWithEpg.map {
                    EpgChannelRequest(xmltvId = it.tvgId, epgDepth = it.catchupDays)
                },
                update = "force",
                timezone = deviceTimezone
            )

            val requestBody = gson.toJson(epgRequest)
            val url = URL("$epgUrl/epg")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = Constants.EPG_CONNECT_TIMEOUT_MS
            connection.readTimeout = Constants.EPG_READ_TIMEOUT_MS
            connection.doOutput = true

            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray())
            }

            val responseCode = connection.responseCode

            if (responseCode == 200) {
                Timber.d("EPG: Received 200 OK, parsing response")
                // Stream parse JSON to avoid loading large response into memory
                val epgResponse = connection.inputStream.bufferedReader().use { reader ->
                    gson.fromJson(reader, EpgResponse::class.java)
                }

                if (epgResponse != null) {
                    Timber.d("EPG: Parsed ${epgResponse.totalPrograms} programs for ${epgResponse.channelsFound} channels")
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
     * Save EPG data to cache
     */
    private fun saveEpgData(epgResponse: EpgResponse) {
        try {
            // Cache in memory first
            cachedEpgData = epgResponse
            Timber.d("EPG data cached in memory: ${epgResponse.totalPrograms} programs")

            // Try to save to disk
            try {
                val json = gson.toJson(epgResponse)
                epgFile.writeText(json)
                Timber.d("EPG data saved to disk")
            } catch (e: OutOfMemoryError) {
                Timber.w(e, "Out of memory saving EPG to disk - keeping memory cache only")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save EPG data")
        }
    }

    /**
     * Load EPG data from cache
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

            val json = epgFile.readText()
            val epgResponse = gson.fromJson(json, EpgResponse::class.java)
            cachedEpgData = epgResponse
            Timber.d("Loaded EPG data: ${epgResponse.totalPrograms} programs")
            epgResponse
        } catch (e: Exception) {
            Timber.e(e, "Failed to load EPG data")
            null
        }
    }

    /**
     * Get current program for a channel
     */
    fun getCurrentProgram(tvgId: String): EpgProgram? {
        val epgData = loadEpgData() ?: return null
        val programs = epgData.epg[tvgId] ?: return null

        return programs.firstOrNull { it.isCurrent() }
    }

    /**
     * Get all programs for a channel
     */
    fun getProgramsForChannel(tvgId: String): List<EpgProgram> {
        val epgData = loadEpgData() ?: return emptyList()
        return epgData.epg[tvgId] ?: emptyList()
    }

    /**
     * Clear EPG cache
     */
    fun clearCache() {
        cachedEpgData = null
        if (epgFile.exists()) {
            epgFile.delete()
        }
        Timber.d("EPG cache cleared")
    }
}
