package com.rutv.data.repository

import com.rutv.data.local.dao.ChannelDao
import com.rutv.data.local.entity.ChannelEntity
import com.rutv.data.model.Channel
import com.rutv.domain.repository.ChannelRepository
import com.rutv.util.Result
import com.rutv.util.logDebug
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for channel data
 * Provides a clean API for channel operations
 */
@Singleton
class ChannelRepositoryImpl @Inject constructor(
    private val channelDao: ChannelDao
) : ChannelRepository {

    private suspend fun <T> safeDaoCall(
        errorMessage: String,
        block: suspend () -> T
    ): Result<T> {
        return try {
            Result.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, errorMessage)
            Result.Error(e)
        }
    }

    override suspend fun getAllChannels(): Result<List<Channel>> = safeDaoCall("Error getting all channels") {
        channelDao.getAllChannels().map { it.toChannel() }
    }

    override suspend fun saveChannels(channels: List<Channel>): Result<Unit> {
        return when (val result = saveChannelsPreservingFavorites(channels)) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(result.exception)
        }
    }

    override suspend fun saveChannelsPreservingFavorites(
        channels: List<Channel>,
        favoriteUrls: List<String>?,
        favoriteTvgIds: List<String>?
    ): Result<Set<String>> = safeDaoCall("Error saving channels") {
        val entities = channels.mapIndexed { index, channel ->
            ChannelEntity.fromChannel(channel.copy(position = index, isFavorite = false))
        }
        val appliedFavorites = channelDao.replaceChannelsPreservingFavorites(
            entities,
            favoriteUrls,
            favoriteTvgIds
        )
        logDebug { "Saved ${channels.size} channels to database" }
        appliedFavorites.toSet()
    }

    override suspend fun toggleFavorite(url: String): Result<Boolean> = safeDaoCall("Error toggling favorite") {
        val channel = channelDao.getChannelByUrl(url)
            ?: throw IllegalStateException("Channel not found for URL: $url")
        val newStatus = !channel.isFavorite
        channelDao.updateFavoriteStatus(url, newStatus)
        logDebug { "Toggled favorite for: $url to $newStatus" }
        newStatus
    }

    override suspend fun updateAspectRatio(url: String, aspectRatio: Int): Result<Unit> = safeDaoCall("Error updating aspect ratio") {
        channelDao.updateAspectRatio(url, aspectRatio)
        logDebug { "Updated aspect ratio for: $url to $aspectRatio" }
    }

    override suspend fun clearAllChannels(): Result<Unit> = safeDaoCall("Error clearing channels") {
        channelDao.deleteAllChannels()
        logDebug { "Cleared all channels" }
    }
}
