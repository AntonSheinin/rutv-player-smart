package com.videoplayer

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class EpgService(private val context: Context) {
    
    private val gson = Gson()
    private val epgFile = File(context.filesDir, "epg_data.json")
    private val TAG = "EpgService"
    
    suspend fun checkHealth(epgUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (epgUrl.isBlank()) {
            Log.d(TAG, "❌ EPG URL not configured")
            return@withContext false
        }
        
        try {
            Log.d(TAG, "🔍 Checking EPG service health: $epgUrl/health")
            val healthUrl = URL("$epgUrl/health")
            val connection = healthUrl.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val healthResponse = gson.fromJson(response, EpgHealthResponse::class.java)
                val isHealthy = healthResponse.status.equals("ok", ignoreCase = true)
                Log.d(TAG, "✅ EPG service health check: ${if (isHealthy) "OK" else "NOT OK"}")
                connection.disconnect()
                return@withContext isHealthy
            } else {
                Log.d(TAG, "❌ EPG service health check failed with code: $responseCode")
                connection.disconnect()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ EPG service health check error: ${e.message}", e)
            return@withContext false
        }
    }
    
    suspend fun fetchEpg(epgUrl: String, channels: List<M3U8Parser.Channel>): Boolean = withContext(Dispatchers.IO) {
        if (epgUrl.isBlank()) {
            Log.d(TAG, "❌ EPG URL not configured, skipping fetch")
            return@withContext false
        }
        
        val channelsWithEpg = channels.filter { it.tvgId.isNotBlank() && it.catchupDays > 0 }
        if (channelsWithEpg.isEmpty()) {
            Log.d(TAG, "⚠️ No channels with EPG data (tvg-id and catchup-days required)")
            return@withContext false
        }
        
        // DEBUG: Limit to first 10 channels only
        val limitedChannels = channelsWithEpg.take(10)
        Log.d(TAG, "🔧 DEBUG MODE: Limiting EPG fetch to ${limitedChannels.size} channels (out of ${channelsWithEpg.size} total)")
        
        try {
            Log.d(TAG, "📡 Fetching EPG for ${limitedChannels.size} channels...")
            Log.d(TAG, "📍 EPG URL: $epgUrl/epg")
            
            // Log channel details
            limitedChannels.forEachIndexed { index, channel ->
                Log.d(TAG, "   Channel ${index + 1}: tvg-id='${channel.tvgId}', catchup-days=${channel.catchupDays}")
            }
            
            val epgRequest = EpgRequest(
                channels = limitedChannels.map {
                    EpgChannelRequest(xmltvId = it.tvgId, epgDepth = it.catchupDays)
                },
                update = "force"
            )
            
            val requestBody = gson.toJson(epgRequest)
            Log.d(TAG, "📤 EPG Request JSON: $requestBody")
            
            val url = URL("$epgUrl/epg")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.doOutput = true
            
            Log.d(TAG, "🔗 Opening connection to ${url}...")
            
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray())
                Log.d(TAG, "📨 Request body sent (${requestBody.toByteArray().size} bytes)")
            }
            
            val responseCode = connection.responseCode
            Log.d(TAG, "📥 Response code: $responseCode")
            
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "📥 Response body received (${response.length} chars)")
                Log.d(TAG, "📥 Response preview: ${response.take(200)}...")
                
                try {
                    val epgResponse = gson.fromJson(response, EpgResponse::class.java)
                    
                    if (epgResponse == null) {
                        Log.e(TAG, "❌ EPG response is null after parsing")
                        connection.disconnect()
                        return@withContext false
                    }
                    
                    Log.d(TAG, "✅ EPG fetch successful:")
                    Log.d(TAG, "   • Channels requested: ${epgResponse.channelsRequested}")
                    Log.d(TAG, "   • Channels found: ${epgResponse.channelsFound}")
                    Log.d(TAG, "   • Total programs: ${epgResponse.totalPrograms}")
                    Log.d(TAG, "   • Update mode: ${epgResponse.updateMode}")
                    Log.d(TAG, "   • Timestamp: ${epgResponse.timestamp}")
                    Log.d(TAG, "   • EPG keys: ${epgResponse.epg.keys.joinToString(", ")}")
                    
                    saveEpgData(epgResponse)
                    connection.disconnect()
                    return@withContext true
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to parse EPG response: ${e.message}")
                    Log.e(TAG, "❌ Response was: $response")
                    connection.disconnect()
                    return@withContext false
                }
            } else {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"
                } catch (e: Exception) {
                    "Error reading error body: ${e.message}"
                }
                Log.e(TAG, "❌ EPG fetch failed with code $responseCode")
                Log.e(TAG, "❌ Error body: $errorBody")
                connection.disconnect()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ EPG fetch error: ${e.javaClass.simpleName} - ${e.message}")
            Log.e(TAG, "❌ Stack trace: ", e)
            return@withContext false
        }
    }
    
    private fun saveEpgData(epgResponse: EpgResponse) {
        try {
            val json = gson.toJson(epgResponse)
            epgFile.writeText(json)
            Log.d(TAG, "💾 EPG data saved to ${epgFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save EPG data: ${e.message}", e)
        }
    }
    
    fun loadEpgData(): EpgResponse? {
        return try {
            if (!epgFile.exists()) {
                Log.d(TAG, "⚠️ No EPG data file found")
                return null
            }
            
            val json = epgFile.readText()
            val epgResponse = gson.fromJson(json, EpgResponse::class.java)
            Log.d(TAG, "📂 Loaded EPG data: ${epgResponse.totalPrograms} programs for ${epgResponse.channelsFound} channels")
            epgResponse
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load EPG data: ${e.message}", e)
            null
        }
    }
    
    fun getCurrentProgram(tvgId: String): EpgProgram? {
        val epgData = loadEpgData() ?: return null
        val programs = epgData.epg[tvgId] ?: return null
        val now = System.currentTimeMillis()
        
        return programs.firstOrNull { program ->
            try {
                val startTime = parseTimeString(program.startTime)
                val stopTime = parseTimeString(program.stopTime)
                now in startTime..stopTime
            } catch (e: Exception) {
                false
            }
        }
    }
    
    fun getProgramsForChannel(tvgId: String): List<EpgProgram> {
        Log.d(TAG, "getProgramsForChannel called for tvgId: '$tvgId'")
        val epgData = loadEpgData()
        if (epgData == null) {
            Log.d(TAG, "No EPG data loaded")
            return emptyList()
        }
        
        Log.d(TAG, "EPG data has ${epgData.epg.keys.size} channels: ${epgData.epg.keys.joinToString(", ")}")
        val programs = epgData.epg[tvgId]
        if (programs == null) {
            Log.d(TAG, "No programs found for tvgId: '$tvgId'")
        } else {
            Log.d(TAG, "Found ${programs.size} programs for tvgId: '$tvgId'")
        }
        return programs ?: emptyList()
    }
    
    private fun parseTimeString(timeString: String): Long {
        return try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            format.parse(timeString)?.time ?: 0L
        } catch (e: Exception) {
            try {
                val format = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
                format.parse(timeString)?.time ?: 0L
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to parse time: $timeString")
                0L
            }
        }
    }
}
