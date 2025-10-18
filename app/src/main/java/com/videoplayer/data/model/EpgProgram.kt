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
    @SerializedName("description") val description: String = "",
    val startTimeMillis: Long = parseTime(startTime),
    val stopTimeMillis: Long = parseTime(stopTime)
) {
    fun isCurrent(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return currentTimeMillis in startTimeMillis..stopTimeMillis
    }

    companion object {
        private val isoOffsetFormat = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        }
        private val isoLocalFormat = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getDefault()
            }
        }
        private val xmltvFormat = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
        }

        private fun parseTime(timeString: String): Long {
            if (timeString.isEmpty()) return 0L
            return parseWithFormat(timeString, isoOffsetFormat.get()) {
                timber.log.Timber.v("EPG Parse: '$timeString' \u2192 $it (ISO 8601 with timezone)")
            } ?: parseWithFormat(timeString, isoLocalFormat.get()) {
                timber.log.Timber.v("EPG Parse: '$timeString' \u2192 $it (ISO 8601 local timezone)")
            } ?: parseWithFormat(timeString, xmltvFormat.get()) {
                timber.log.Timber.v("EPG Parse: '$timeString' \u2192 $it (XMLTV format)")
            } ?: run {
                timber.log.Timber.w("EPG Parse: Failed to parse '$timeString'")
                0L
            }
        }

        private inline fun parseWithFormat(
            timeString: String,
            format: java.text.SimpleDateFormat,
            logging: (Long) -> Unit
        ): Long? {
            return try {
                val result = format.parse(timeString)?.time ?: return null
                logging(result)
                result
            } catch (_: Exception) {
                null
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
