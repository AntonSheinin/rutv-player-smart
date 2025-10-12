package com.videoplayer.domain.usecase

import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.PlaylistSource
import com.videoplayer.data.remote.PlaylistLoader
import com.videoplayer.data.remote.PlaylistParser
import com.videoplayer.data.repository.ChannelRepository
import com.videoplayer.data.repository.PreferencesRepository
import com.videoplayer.util.Result
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * Use case for loading playlist from file or URL
 */
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
    suspend operator fun invoke(forceReload: Boolean = false): Result<List<Channel>> {
        try {
            val source = preferencesRepository.playlistSource.first()

            // If no source configured, return empty
            if (source is PlaylistSource.None) {
                Timber.d("No playlist source configured")
                return Result.Success(emptyList())
            }

            // Get stored hash and current hash
            val storedHash = preferencesRepository.playlistHash.first()
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
                Timber.d("Loading channels from cache (hash match)")
                val cachedChannels = channelRepository.getAllChannels()
                if (cachedChannels is Result.Success && cachedChannels.data.isNotEmpty()) {
                    Timber.d("Loaded ${cachedChannels.data.size} channels from cache")
                    return cachedChannels
                }
            }

            // Parse playlist
            Timber.d("Parsing playlist (${if (forceReload) "forced" else "new content"})")
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
                    Timber.d("Successfully loaded ${channels.size} channels")
                    Result.Success(channels)
                }
                is Result.Error -> saveResult
                is Result.Loading -> Result.Error(Exception("Unexpected loading state"))
            }

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
