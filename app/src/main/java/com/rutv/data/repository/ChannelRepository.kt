package com.rutv.data.repository

import androidx.media3.common.util.UnstableApi
import com.rutv.data.local.dao.ChannelDao
import com.rutv.data.local.entity.ChannelEntity
import com.rutv.data.model.Channel
import com.rutv.util.Result
import com.rutv.util.logDebug
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for channel data
 * Provides a clean API for channel operations
 */
@Suppress("unused")
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
            logDebug { "Saved ${channels.size} channels to database" }
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
                logDebug { "Toggled favorite for: $url to $newStatus" }
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
            logDebug { "Updated aspect ratio for: $url to $aspectRatio" }
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
            logDebug { "Cleared all channels" }
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error clearing channels")
            Result.Error(e)
        }
    }

}
