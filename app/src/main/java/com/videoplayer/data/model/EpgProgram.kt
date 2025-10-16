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
    val startTimeMillis: Long
        get() = parseTime(startTime)

    val stopTimeMillis: Long
        get() = parseTime(stopTime)

    fun isCurrent(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return try {
            val start = parseTime(startTime)
            val stop = parseTime(stopTime)
            currentTimeMillis in start..stop
        } catch (_: Exception) {
            false
        }
    }

    private fun parseTime(timeString: String): Long {
        return try {
            // Try ISO 8601 format first (with timezone)
            val format1 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            val result = format1.parse(timeString)?.time ?: 0L
            if (result > 0) {
                timber.log.Timber.v("EPG Parse: '$timeString' → $result (ISO 8601 with timezone)")
            }
            result
        } catch (_: Exception) {
            try {
                // Try ISO 8601 format without timezone (assume local timezone)
                val format2 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                format2.timeZone = java.util.TimeZone.getDefault()
                val result = format2.parse(timeString)?.time ?: 0L
                if (result > 0) {
                    timber.log.Timber.v("EPG Parse: '$timeString' → $result (ISO 8601 local timezone)")
                }
                result
            } catch (_: Exception) {
                try {
                    // Try XMLTV format (yyyyMMddHHmmss Z)
                    val format3 = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
                    val result = format3.parse(timeString)?.time ?: 0L
                    if (result > 0) {
                        timber.log.Timber.v("EPG Parse: '$timeString' → $result (XMLTV format)")
                    }
                    result
                } catch (_: Exception) {
                    timber.log.Timber.w("EPG Parse: Failed to parse '$timeString'")
                    0L
                }
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
