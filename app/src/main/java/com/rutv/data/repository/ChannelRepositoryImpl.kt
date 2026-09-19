package com.rutv.data.repository

import com.rutv.data.local.dao.ChannelDao
import com.rutv.data.local.entity.ChannelEntity
import com.rutv.data.model.Channel
import com.rutv.domain.repository.ChannelRepository
import com.rutv.domain.repository.PlaylistSnapshot
import com.rutv.data.local.entity.PlaylistSnapshotEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val channelDao: ChannelDao,
    private val preferences: PreferencesRepository
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

    private val writes = Mutex()

    override suspend fun getSnapshot(): Result<PlaylistSnapshot> = safeDaoCall("Error reading playlist snapshot") {
        val snapshot = channelDao.readSnapshot()
        PlaylistSnapshot(snapshot.identity?.sourceIdentity, snapshot.identity?.contentHash, snapshot.channels.map { it.toChannel() })
    }

    override suspend fun saveSnapshot(
        channels: List<Channel>, sourceIdentity: String, contentHash: String
    ): Result<List<Channel>> = safeDaoCall("Error saving channels") {
        writes.withLock {
            val saved = channelDao.replaceSnapshot(
                channels.mapIndexed { index, channel -> ChannelEntity.fromChannel(channel.copy(position = index)) },
                PlaylistSnapshotEntity(sourceIdentity = sourceIdentity, contentHash = contentHash),
                preferences.favoriteUrls.first(), preferences.favoriteTvgIds.first()
            )
            backupFavorites()
            saved.map { it.toChannel() }
        }
    }

    override suspend fun toggleFavorite(url: String): Result<Boolean> = safeDaoCall("Error toggling favorite") {
        writes.withLock {
            val favorite = channelDao.toggleFavorite(url)
            backupFavorites()
            favorite
        }
    }

    // A completed database edit remains successful even if its secondary backup cannot be written.
    private suspend fun backupFavorites() = withContext(NonCancellable) {
        try {
            val favorites = channelDao.getAllChannels().filter { it.isFavorite }
            preferences.replaceFavorites(favorites.map { it.url }.toSet(), favorites.map { it.tvgId }.filter { it.isNotBlank() }.toSet())
        } catch (e: Exception) {
            Timber.w(e, "Could not update favorite recovery backup")
        }
    }

    override suspend fun updateAspectRatio(url: String, aspectRatio: Int): Result<Unit> = safeDaoCall("Error updating aspect ratio") {
        writes.withLock { channelDao.updateAspectRatio(url, aspectRatio) }
    }

    override suspend fun updatePreferredAudioLanguage(
        url: String,
        language: String?
    ): Result<Unit> = safeDaoCall("Error updating preferred audio language") {
        writes.withLock { channelDao.updatePreferredAudioLanguage(url, language) }
    }
}
