package com.videoplayer.data.model

import com.google.gson.annotations.SerializedName

/**
 * EPG Program data model
 */
data class EpgProgram(
    @SerializedName("id") val id: String = "",
    @SerializedName("start_time") val startTime: String,
    @SerializedName("stop_time") val stopTime: String,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String = ""
) {
    fun isCurrent(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return try {
            val start = parseTime(startTime)
            val stop = parseTime(stopTime)
            currentTimeMillis in start..stop
        } catch (e: Exception) {
            false
        }
    }

    fun isEnded(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return try {
            val stop = parseTime(stopTime)
            currentTimeMillis > stop
        } catch (e: Exception) {
            false
        }
    }

    private fun parseTime(timeString: String): Long {
        return try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            format.parse(timeString)?.time ?: 0L
        } catch (e: Exception) {
            try {
                val format = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
                format.parse(timeString)?.time ?: 0L
            } catch (e2: Exception) {
                0L
            }
        }
    }
}

/**
 * EPG Request models
 */
data class EpgChannelRequest(
    @SerializedName("xmltv_id") val xmltvId: String,
    @SerializedName("epg_depth") val epgDepth: Int
)

data class EpgRequest(
    @SerializedName("channels") val channels: List<EpgChannelRequest>,
    @SerializedName("update") val update: String = "force",
    @SerializedName("timezone") val timezone: String
)

/**
 * EPG Response models
 */
data class EpgResponse(
    @SerializedName("update_mode") val updateMode: String,
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("channels_requested") val channelsRequested: Int,
    @SerializedName("channels_found") val channelsFound: Int,
    @SerializedName("total_programs") val totalPrograms: Int,
    @SerializedName("epg") val epg: Map<String, List<EpgProgram>>
)

data class EpgHealthResponse(
    @SerializedName("status") val status: String
) {
    val isHealthy: Boolean
        get() = status.equals("ok", ignoreCase = true)
}
