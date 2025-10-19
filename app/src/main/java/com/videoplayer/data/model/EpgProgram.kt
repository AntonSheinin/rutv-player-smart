package com.videoplayer.data.model

import com.google.gson.annotations.SerializedName
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * EPG program model with pre-parsed epoch timestamps.
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
        private val utcCalendar = ThreadLocal.withInitial {
            GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { isLenient = false }
        }
        private val localCalendar = ThreadLocal.withInitial {
            GregorianCalendar(TimeZone.getDefault()).apply { isLenient = false }
        }

        private fun parseTime(time: String): Long {
            if (time.isEmpty()) return 0L
            return parseIsoOffset(time)
                ?: parseIsoLocal(time)
                ?: parseXmlTv(time)
                ?: 0L
        }

        private fun parseIsoOffset(value: String): Long? {
            if (value.length < 25 || value[10] != 'T') return null
            return try {
                val year = value.substring(0, 4).toInt()
                val month = value.substring(5, 7).toInt()
                val day = value.substring(8, 10).toInt()
                val hour = value.substring(11, 13).toInt()
                val minute = value.substring(14, 16).toInt()
                val second = value.substring(17, 19).toInt()
                val sign = if (value[19] == '-') -1 else 1
                val offsetHours = value.substring(20, 22).toInt()
                val offsetMinutes = value.substring(23, 25).toInt()
                val offsetMillis = sign * ((offsetHours * 60 + offsetMinutes) * 60_000)

                val cal = utcCalendar.get()
                cal.clear()
                cal.set(year, month - 1, day, hour, minute, second)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis - offsetMillis
            } catch (_: Exception) {
                null
            }
        }

        private fun parseIsoLocal(value: String): Long? {
            if (value.length != 19 || value[10] != 'T') return null
            return try {
                val year = value.substring(0, 4).toInt()
                val month = value.substring(5, 7).toInt()
                val day = value.substring(8, 10).toInt()
                val hour = value.substring(11, 13).toInt()
                val minute = value.substring(14, 16).toInt()
                val second = value.substring(17, 19).toInt()

                val cal = localCalendar.get()
                cal.clear()
                cal.set(year, month - 1, day, hour, minute, second)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            } catch (_: Exception) {
                null
            }
        }

        private fun parseXmlTv(value: String): Long? {
            // Format: yyyyMMddHHmmss +HHMM (e.g. 20251010193000 +0300)
            // Minimum valid length: 14 (date/time) + 1 (space) + 1 (sign) + 4 (offset) = 20
            if (value.length < 20) return null
            return try {
                val year = value.substring(0, 4).toInt()
                val month = value.substring(4, 6).toInt()
                val day = value.substring(6, 8).toInt()
                val hour = value.substring(8, 10).toInt()
                val minute = value.substring(10, 12).toInt()
                val second = value.substring(12, 14).toInt()
                val sign = if (value[15] == '-') -1 else 1
                val offsetHours = value.substring(16, 18).toInt()
                val offsetMinutes = value.substring(18, 20).toInt()
                val offsetMillis = sign * ((offsetHours * 60 + offsetMinutes) * 60_000)

                val cal = utcCalendar.get()
                cal.clear()
                cal.set(year, month - 1, day, hour, minute, second)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis - offsetMillis
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * EPG request/response models
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
