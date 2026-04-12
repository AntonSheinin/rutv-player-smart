package com.rutv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.rutv.data.model.Channel
import com.rutv.data.model.ResizeMode
import org.json.JSONArray

/**
 * Room entity for storing channels in the database
 */
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val url: String,
    val title: String,
    val logo: String,
    val group: String,
    /**
     * JSON array string with multi-group support.
     * Example: ["News","Kids"]
     */
    val groupsJson: String,
    val tvgId: String,
    val catchupDays: Int,
    val catchupSource: String,
    val isFavorite: Boolean,
    val aspectRatio: Int,
    val position: Int
) {
    /**
     * Convert entity to domain model
     */
    fun toChannel(): Channel = Channel(
        url = url,
        title = title,
        logo = logo,
        group = group,
        groups = decodeGroups(groupsJson, fallbackGroup = group),
        tvgId = tvgId,
        catchupDays = catchupDays,
        catchupSource = catchupSource,
        isFavorite = isFavorite,
        resizeMode = ResizeMode.fromInt(aspectRatio),
        position = position
    )

    companion object {
        /**
         * Convert domain model to entity
         */
        fun fromChannel(channel: Channel): ChannelEntity = ChannelEntity(
            url = channel.url,
            title = channel.title,
            logo = channel.logo,
            group = channel.group,
            groupsJson = encodeGroups(channel.groups, channel.group),
            tvgId = channel.tvgId,
            catchupDays = channel.catchupDays,
            catchupSource = channel.catchupSource,
            isFavorite = channel.isFavorite,
            aspectRatio = channel.resizeMode.intValue,
            position = channel.position
        )

        private fun encodeGroups(groups: List<String>, fallbackGroup: String): String {
            val normalized = buildList {
                groups.map { it.trim() }.filter { it.isNotBlank() }.forEach { g ->
                    if (!contains(g)) add(g)
                }
                val primary = fallbackGroup.trim()
                if (primary.isNotBlank() && !contains(primary)) add(0, primary)
            }
            return JSONArray(normalized).toString()
        }

        private fun decodeGroups(groupsJson: String, fallbackGroup: String): List<String> {
            val primary = fallbackGroup.trim()
            val parsed = runCatching {
                val arr = JSONArray(groupsJson)
                buildList {
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i, "").trim()
                        if (v.isNotBlank() && !contains(v)) add(v)
                    }
                }
            }.getOrNull().orEmpty()
            if (parsed.isNotEmpty()) return parsed
            return if (primary.isNotBlank()) listOf(primary) else emptyList()
        }
    }
}
