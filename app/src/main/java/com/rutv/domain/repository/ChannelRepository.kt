package com.rutv.domain.repository

import com.rutv.data.model.Channel
import com.rutv.util.Result

data class PlaylistSnapshot(val sourceIdentity: String?, val contentHash: String?, val channels: List<Channel>)

interface ChannelRepository {
    suspend fun getAllChannels(): Result<List<Channel>>
    suspend fun getSnapshot(): Result<PlaylistSnapshot>
    suspend fun saveSnapshot(channels: List<Channel>, sourceIdentity: String, contentHash: String): Result<List<Channel>>
    suspend fun toggleFavorite(url: String): Result<Boolean>
    suspend fun updateAspectRatio(url: String, aspectRatio: Int): Result<Unit>
}
