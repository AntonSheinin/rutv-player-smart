package com.rutv.data.model

import com.google.gson.annotations.SerializedName

/**
 * EPG program model with pre-parsed epoch timestamps.
 *
 * Time formats seen in the wild:
 * - ISO-8601 with offset: `2025-10-10T19:30:00+03:00`
 * - ISO-8601 local (no offset): `2025-10-10T19:30:00` (interpreted as device local time)
 * - XMLTV style: `yyyyMMddHHmmss ±HHMM` (e.g. `20251010193000 +0300`)
 *
 * We precompute millis at construction time so UI and playback code can do fast comparisons
 * (e.g. `isCurrent()`) without re-parsing strings.
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
    /**
     * Start time in UTC millis, if the raw string includes timezone/offset info.
     * Falls back to [startTimeMillis] if the raw string has no offset (best effort).
     */
    val startUtcMillis: Long
        get() = startTimeMillis

    /**
     * Stop time in UTC millis, if the raw string includes timezone/offset info.
     * Falls back to [stopTimeMillis] if the raw string has no offset (best effort).
     */
    val stopUtcMillis: Long
        get() = stopTimeMillis

    val durationUtcSeconds: Long
        get() = ((stopUtcMillis - startUtcMillis) / 1000L).coerceAtLeast(1)

    fun isCurrent(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return stopTimeMillis > startTimeMillis && currentTimeMillis >= startTimeMillis && currentTimeMillis < stopTimeMillis
    }

    companion object {
        private val xmlTvFormatter = java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss xx")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)

        private fun parseTime(value: String): Long {
            if (value.isBlank()) return 0L
            return try {
                java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: java.time.format.DateTimeParseException) {
                try {
                    java.time.OffsetDateTime.parse(value, xmlTvFormatter).toInstant().toEpochMilli()
                } catch (_: java.time.format.DateTimeParseException) {
                    try {
                        java.time.LocalDateTime.parse(value).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (_: java.time.format.DateTimeParseException) {
                        0L
                    }
                }
            }
        }
    }
}

/**
 * EPG request/response models
 */
data class EpgChannelRequest(
    @SerializedName("xmltv_id") val xmltvId: String
)

data class EpgRequest(
    @SerializedName("channels") val channels: List<EpgChannelRequest>,
    @SerializedName("timezone") val timezone: String,
    @SerializedName("from_date") val fromDate: String? = null,
    @SerializedName("to_date") val toDate: String? = null
)

data class EpgResponse(
    @SerializedName("update_mode") val updateMode: String,
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("last_epg_update_at") val lastEpgUpdateAt: String = "",
    @SerializedName("channels_requested") val channelsRequested: Int,
    @SerializedName("channels_found") val channelsFound: Int,
    @SerializedName("total_programs") val totalPrograms: Int,
    @SerializedName("epg") val epg: Map<String, List<EpgProgram>>
)
