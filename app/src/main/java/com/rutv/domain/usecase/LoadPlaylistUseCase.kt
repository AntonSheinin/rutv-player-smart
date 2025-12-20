package com.rutv.domain.usecase

import androidx.media3.common.util.UnstableApi
import com.rutv.data.model.Channel
import com.rutv.data.model.PlaylistSource
import com.rutv.data.remote.PlaylistLoader
import com.rutv.data.remote.PlaylistParser
import com.rutv.data.repository.ChannelRepository
import com.rutv.data.repository.PreferencesRepository
import com.rutv.util.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * Loads the currently configured playlist into the local channel database.
 *
 * Policy summary:
 * - Playlist source is resolved from [PreferencesRepository.playlistSource]
 *   - `File`: content is already local (no network)
 *   - `Url`: content is downloaded via [PlaylistLoader]
 * - A simple hash is stored to detect when the playlist has not changed.
 * - Channels are persisted via [ChannelRepository] (Room).
 *
 * Cold start optimization:
 * - When `skipNetworkIfCacheAvailable=true` and a URL playlist is configured, we can return the
 *   cached channels immediately if we have a stored hash + non-empty DB. This makes startup fast,
 *   while a background refresh can update later (see `InitializeAppUseCase` usage).
 */
@UnstableApi
class LoadPlaylistUseCase @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val preferencesRepository: PreferencesRepository,
    private val playlistLoader: PlaylistLoader,
    private val playlistParser: PlaylistParser
) {

    /**
     * Load playlist based on current configuration
     * Checks cache first, then loads from source if needed
     */
    suspend operator fun invoke(
        forceReload: Boolean = false,
        /**
         * If true, and a URL playlist is configured, return cached channels immediately (if available)
         * without making a network request. This is used to speed up cold start.
         */
        skipNetworkIfCacheAvailable: Boolean = false
    ): Result<List<Channel>> {
        try {
            val source = preferencesRepository.playlistSource.first()

            // If no source configured, return empty
            if (source is PlaylistSource.None) {
                return Result.Success(emptyList())
            }

            // Get stored hash and current hash
            val storedHash = preferencesRepository.playlistHash.first()
            if (!forceReload && skipNetworkIfCacheAvailable && source is PlaylistSource.Url && storedHash.isNotBlank()) {
                // Fast path: if we have a hash (meaning we successfully loaded/saved before),
                // and the DB has channels, use it immediately.
                val cachedChannels = channelRepository.getAllChannels()
                if (cachedChannels is Result.Success && cachedChannels.data.isNotEmpty()) {
                    return cachedChannels
                }
            }
            val content = when (source) {
                is PlaylistSource.File -> source.content
                is PlaylistSource.Url -> {
                    when (val result = playlistLoader.loadFromUrl(source.url)) {
                        is Result.Success -> result.data
                        is Result.Error -> return result
                        is Result.Loading -> return Result.Error(Exception("Unexpected loading state"))
                    }
                }
                is PlaylistSource.None -> return Result.Success(emptyList())
            }

            // Validate content size
            if (!playlistLoader.validateSize(content)) {
                Timber.e("Playlist too large: ${content.length} bytes")
                return Result.Error(Exception("Playlist too large"))
            }

            val currentHash = playlistParser.calculateHash(content)

            // If hash matches and not force reload, load from cache
            if (!forceReload && currentHash == storedHash) {
                // Even if the playlist didn’t change, DB could have been cleared by OS/data wipe,
                // so we only return cache if it’s non-empty.
                val cachedChannels = channelRepository.getAllChannels()
                if (cachedChannels is Result.Success && cachedChannels.data.isNotEmpty()) {
                    return cachedChannels
                }
            }

            // Parse playlist
            val channels = playlistParser.parse(content)

            if (channels.isEmpty()) {
                Timber.w("No channels found in playlist")
                return Result.Error(Exception("No channels found"))
            }

            // Save to repository
            return when (val saveResult = channelRepository.saveChannels(channels)) {
                is Result.Success -> {
                    // Save hash
                    preferencesRepository.savePlaylistHash(currentHash)
                    Result.Success(channels)
                }
                is Result.Error -> saveResult
                is Result.Loading -> Result.Error(Exception("Unexpected loading state"))
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error in LoadPlaylistUseCase")
            return Result.Error(e)
        }
    }

    /**
     * Force reload playlist from source
     */
    suspend fun reload(): Result<List<Channel>> {
        // Clear cache first
        preferencesRepository.clearPlaylistCache()
        channelRepository.clearAllChannels()

        return invoke(forceReload = true)
    }
}
