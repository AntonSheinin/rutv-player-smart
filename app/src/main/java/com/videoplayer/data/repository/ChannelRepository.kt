package com.videoplayer.data.repository

import androidx.media3.common.util.UnstableApi
import com.videoplayer.data.local.dao.ChannelDao
import com.videoplayer.data.local.entity.ChannelEntity
import com.videoplayer.data.model.Channel
import com.videoplayer.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for channel data
 * Provides a clean API for channel operations
 */
@UnstableApi
@Singleton
class ChannelRepository @Inject constructor(
    private val channelDao: ChannelDao
) {

    /**
     * Get all channels (one-time)
     */
    suspend fun getAllChannels(): Result<List<Channel>> {
        return try {
            val entities = channelDao.getAllChannels()
            val channels = entities.map { it.toChannel() }
            Result.Success(channels)
        } catch (e: Exception) {
            Timber.e(e, "Error getting all channels")
            Result.Error(e)
        }
    }


    /**
     * Save channels to database
     */
    suspend fun saveChannels(channels: List<Channel>): Result<Unit> {
        return try {
            val entities = channels.mapIndexed { index, channel ->
                ChannelEntity.fromChannel(channel.copy(position = index))
            }
            channelDao.insertChannels(entities)
            Timber.d("Saved ${channels.size} channels to database")
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error saving channels")
            Result.Error(e)
        }
    }


    /**
     * Toggle favorite status for a channel
     */
    suspend fun toggleFavorite(url: String): Result<Boolean> {
        return try {
            val channel = channelDao.getChannelByUrl(url)
            if (channel != null) {
                val newStatus = !channel.isFavorite
                channelDao.updateFavoriteStatus(url, newStatus)
                Timber.d("Toggled favorite for: $url to $newStatus")
                Result.Success(newStatus)
            } else {
                Timber.w("Channel not found for URL: $url")
                Result.Error(Exception("Channel not found"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error toggling favorite")
            Result.Error(e)
        }
    }

    /**
     * Update aspect ratio for a channel
     */
    suspend fun updateAspectRatio(url: String, aspectRatio: Int): Result<Unit> {
        return try {
            channelDao.updateAspectRatio(url, aspectRatio)
            Timber.d("Updated aspect ratio for: $url to $aspectRatio")
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error updating aspect ratio")
            Result.Error(e)
        }
    }

    /**
     * Delete all channels
     */
    suspend fun clearAllChannels(): Result<Unit> {
        return try {
            channelDao.deleteAllChannels()
            Timber.d("Cleared all channels")
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error clearing channels")
            Result.Error(e)
        }
    }

}
