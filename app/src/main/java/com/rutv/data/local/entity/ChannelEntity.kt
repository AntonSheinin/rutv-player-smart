@file:Suppress("unused")

package com.rutv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.rutv.data.model.Channel

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
        tvgId = tvgId,
        catchupDays = catchupDays,
        catchupSource = catchupSource,
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
            catchupSource = channel.catchupSource,
            isFavorite = channel.isFavorite,
            aspectRatio = channel.aspectRatio,
            position = channel.position
        )
    }
}
