package com.videoplayer

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class EpgService(private val context: Context) {
    
    private val gson = Gson()
    private val epgFile = File(context.filesDir, "epg_data.json")
    private val TAG = "EpgService"
    private var cachedEpgData: EpgResponse? = null
    
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
    
    suspend fun fetchEpgData(
        epgUrl: String,
        channels: List<M3U8Parser.Channel>,
        onComplete: () -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (epgUrl.isBlank()) {
            Log.d(TAG, "❌ EPG URL not configured, skipping fetch")
            return@withContext false
        }
        
        val channelsWithEpg = channels.filter { it.tvgId.isNotBlank() && it.catchupDays > 0 }
        if (channelsWithEpg.isEmpty()) {
            Log.d(TAG, "⚠️ No channels with EPG data (tvg-id and catchup-days required)")
            return@withContext false
        }
        
        Log.d(TAG, "📡 Fetching EPG for ${channelsWithEpg.size} channels in ONE request (background thread)...")
        
        try {
            val epgRequest = EpgRequest(
                channels = channelsWithEpg.map {
                    EpgChannelRequest(xmltvId = it.tvgId, epgDepth = it.catchupDays)
                },
                update = "force"
            )
            
            val requestBody = gson.toJson(epgRequest)
            val url = URL("$epgUrl/epg")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 60000
            connection.readTimeout = 60000
            connection.doOutput = true
            
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray())
            }
            
            val responseCode = connection.responseCode
            
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val epgResponse = gson.fromJson(response, EpgResponse::class.java)
                
                if (epgResponse != null) {
                    saveEpgData(epgResponse)
                    Log.d(TAG, "✅ EPG fetch complete: ${epgResponse.channelsFound} channels, ${epgResponse.totalPrograms} programs")
                    
                    withContext(Dispatchers.Main) {
                        onComplete()
                    }
                    
                    connection.disconnect()
                    return@withContext true
                } else {
                    Log.e(TAG, "❌ EPG response is null")
                    connection.disconnect()
                    return@withContext false
                }
            } else {
                Log.e(TAG, "❌ EPG fetch failed with code $responseCode")
                connection.disconnect()
                return@withContext false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error fetching EPG: ${e.message}", e)
            e.printStackTrace()
            return@withContext false
        }
    }
    
    private fun saveEpgData(epgResponse: EpgResponse) {
        try {
            val json = gson.toJson(epgResponse)
            epgFile.writeText(json)
            cachedEpgData = epgResponse
            Log.d(TAG, "💾 EPG data saved to ${epgFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save EPG data: ${e.message}", e)
        }
    }
    
    fun loadEpgData(): EpgResponse? {
        if (cachedEpgData != null) {
            return cachedEpgData
        }
        
        return try {
            if (!epgFile.exists()) {
                Log.d(TAG, "⚠️ No EPG data file found")
                return null
            }
            
            val json = epgFile.readText()
            val epgResponse = gson.fromJson(json, EpgResponse::class.java)
            cachedEpgData = epgResponse
            Log.d(TAG, "📂 Loaded EPG data: ${epgResponse.totalPrograms} programs for ${epgResponse.channelsFound} channels")
            epgResponse
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load EPG data: ${e.message}", e)
            null
        }
    }
    
    fun clearCache() {
        cachedEpgData = null
        Log.d(TAG, "🗑️ EPG cache cleared")
    }
    
    fun getCurrentProgram(tvgId: String): EpgProgram? {
        return try {
            val epgData = loadEpgData() ?: return null
            val programs = epgData.epg[tvgId] ?: return null
            val now = System.currentTimeMillis()
            
            programs.firstOrNull { program ->
                try {
                    val startTime = parseTimeString(program.startTime)
                    val stopTime = parseTimeString(program.stopTime)
                    now in startTime..stopTime
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getCurrentProgram for $tvgId: ${e.message}", e)
            null
        }
    }
    
    fun getProgramsForChannel(tvgId: String): List<EpgProgram> {
        return try {
            Log.d(TAG, "getProgramsForChannel called for tvgId: '$tvgId'")
            val epgData = loadEpgData()
            if (epgData == null) {
                Log.d(TAG, "No EPG data loaded")
                return emptyList()
            }
            
            Log.d(TAG, "EPG data has ${epgData.epg.keys.size} channels")
            val programs = epgData.epg[tvgId]
            if (programs == null) {
                Log.d(TAG, "No programs found for tvgId: '$tvgId'")
            } else {
                Log.d(TAG, "Found ${programs.size} programs for tvgId: '$tvgId'")
            }
            programs ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error in getProgramsForChannel for $tvgId: ${e.message}", e)
            emptyList()
        }
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
