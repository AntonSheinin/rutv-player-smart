package com.videoplayer

import com.google.gson.annotations.SerializedName

data class EpgProgram(
    @SerializedName("id") val id: String = "",
    @SerializedName("start_time") val startTime: String,
    @SerializedName("stop_time") val stopTime: String,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String = ""
)

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
)
