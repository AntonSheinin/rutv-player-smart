package com.videoplayer.data.repository

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
@Singleton
class ChannelRepository @Inject constructor(
    private val channelDao: ChannelDao
) {

    /**
     * Observe all channels as a Flow
     */
    fun observeAllChannels(): Flow<List<Channel>> {
        return channelDao.observeAllChannels()
            .map { entities -> entities.map { it.toChannel() } }
    }

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
     * Observe favorite channels
     */
    fun observeFavorites(): Flow<List<Channel>> {
        return channelDao.observeFavorites()
            .map { entities -> entities.map { it.toChannel() } }
    }

    /**
     * Get favorite channels (one-time)
     */
    suspend fun getFavorites(): Result<List<Channel>> {
        return try {
            val entities = channelDao.getFavorites()
            val channels = entities.map { it.toChannel() }
            Result.Success(channels)
        } catch (e: Exception) {
            Timber.e(e, "Error getting favorites")
            Result.Error(e)
        }
    }

    /**
     * Get a specific channel by URL
     */
    suspend fun getChannelByUrl(url: String): Result<Channel?> {
        return try {
            val entity = channelDao.getChannelByUrl(url)
            Result.Success(entity?.toChannel())
        } catch (e: Exception) {
            Timber.e(e, "Error getting channel by URL: $url")
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
     * Update a single channel
     */
    suspend fun updateChannel(channel: Channel): Result<Unit> {
        return try {
            val entity = ChannelEntity.fromChannel(channel)
            channelDao.updateChannel(entity)
            Timber.d("Updated channel: ${channel.title}")
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error updating channel: ${channel.title}")
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

    /**
     * Get channel count
     */
    suspend fun getChannelCount(): Result<Int> {
        return try {
            val count = channelDao.getChannelCount()
            Result.Success(count)
        } catch (e: Exception) {
            Timber.e(e, "Error getting channel count")
            Result.Error(e)
        }
    }

    /**
     * Get favorite count
     */
    suspend fun getFavoriteCount(): Result<Int> {
        return try {
            val count = channelDao.getFavoriteCount()
            Result.Success(count)
        } catch (e: Exception) {
            Timber.e(e, "Error getting favorite count")
            Result.Error(e)
        }
    }
}
