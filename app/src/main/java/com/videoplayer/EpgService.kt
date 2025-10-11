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
    
    suspend fun fetchEpgBatched(
        epgUrl: String,
        channels: List<M3U8Parser.Channel>,
        batchSize: Int = 20,
        onBatchComplete: (batchNumber: Int, totalBatches: Int, channelsProcessed: Int) -> Unit = { _, _, _ -> }
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
        
        // Process in batches
        val batches = channelsWithEpg.chunked(batchSize)
        val totalBatches = batches.size
        Log.d(TAG, "📡 Starting batched EPG fetch: ${channelsWithEpg.size} channels in $totalBatches batches of $batchSize")
        
        // Load existing EPG data or create new
        val existingEpgData = loadEpgData()
        val mergedEpgMap = existingEpgData?.epg?.toMutableMap() ?: mutableMapOf()
        var totalChannelsProcessed = 0
        var totalProgramsReceived = 0
        
        batches.forEachIndexed { batchIndex, batch ->
            try {
                val batchNumber = batchIndex + 1
                Log.d(TAG, "📦 Processing batch $batchNumber/$totalBatches (${batch.size} channels)...")
                
                val epgRequest = EpgRequest(
                    channels = batch.map {
                        EpgChannelRequest(xmltvId = it.tvgId, epgDepth = it.catchupDays)
                    },
                    update = "force"
                )
                
                val requestBody = gson.toJson(epgRequest)
                val url = URL("$epgUrl/epg")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.doOutput = true
                
                connection.outputStream.use { os ->
                    os.write(requestBody.toByteArray())
                }
                
                val responseCode = connection.responseCode
                
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val batchResponse = gson.fromJson(response, EpgResponse::class.java)
                    
                    if (batchResponse != null) {
                        // Merge batch data into existing EPG
                        mergedEpgMap.putAll(batchResponse.epg)
                        totalChannelsProcessed += batchResponse.channelsFound
                        totalProgramsReceived += batchResponse.totalPrograms
                        
                        // Save progressively after each batch
                        val mergedResponse = EpgResponse(
                            channelsRequested = totalChannelsProcessed,
                            channelsFound = totalChannelsProcessed,
                            totalPrograms = totalProgramsReceived,
                            updateMode = "force",
                            timestamp = batchResponse.timestamp,
                            epg = mergedEpgMap
                        )
                        saveEpgData(mergedResponse)
                        
                        Log.d(TAG, "✅ Batch $batchNumber complete: +${batchResponse.channelsFound} channels, +${batchResponse.totalPrograms} programs")
                        
                        // Notify progress
                        withContext(Dispatchers.Main) {
                            onBatchComplete(batchNumber, totalBatches, totalChannelsProcessed)
                        }
                    }
                    connection.disconnect()
                } else {
                    Log.e(TAG, "❌ Batch $batchNumber failed with code $responseCode")
                    connection.disconnect()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in batch $batchNumber: ${e.message}")
            }
        }
        
        Log.d(TAG, "🎉 EPG fetch complete: $totalChannelsProcessed channels, $totalProgramsReceived programs")
        return@withContext totalChannelsProcessed > 0
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
