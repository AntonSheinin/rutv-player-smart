package com.rutv.domain.repository

import com.rutv.data.model.Channel
import com.rutv.util.Result

interface ChannelRepository {
    suspend fun getAllChannels(): Result<List<Channel>>
    suspend fun saveChannels(channels: List<Channel>): Result<Unit>
    suspend fun saveChannelsPreservingFavorites(
        channels: List<Channel>,
        favoriteUrls: List<String>? = null,
        favoriteTvgIds: List<String>? = null
    ): Result<Set<String>>
    suspend fun toggleFavorite(url: String): Result<Boolean>
    suspend fun updateAspectRatio(url: String, aspectRatio: Int): Result<Unit>
    suspend fun clearAllChannels(): Result<Unit>
}
