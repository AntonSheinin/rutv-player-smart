@file:Suppress("unused")

package com.videoplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.videoplayer.data.model.Channel

/**
 * Room entity for storing channels in the database
 */
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val url: String,
    val title: String,
    val logo: String,
    val group: String,
    val tvgId: String,
    val catchupDays: Int,
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
        tvgId = tvgId,
        catchupDays = catchupDays,
        isFavorite = isFavorite,
        aspectRatio = aspectRatio,
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
            tvgId = channel.tvgId,
            catchupDays = channel.catchupDays,
            isFavorite = channel.isFavorite,
            aspectRatio = channel.aspectRatio,
            position = channel.position
        )
    }
}
