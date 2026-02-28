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
    private suspend fun seedFavoriteBackupIfEmpty(channels: List<Channel>) {
        if (channels.isEmpty()) return
        val storedUrls = preferencesRepository.favoriteUrls.first()
        val storedTvgIds = preferencesRepository.favoriteTvgIds.first()
        if (storedUrls.isNotEmpty() || storedTvgIds.isNotEmpty()) return
        val favorites = channels.filter { it.isFavorite }
        if (favorites.isEmpty()) return
        val urls = favorites.asSequence().map { it.url }.filter { it.isNotBlank() }.toSet()
        val tvgIds = favorites.asSequence().map { it.tvgId }.filter { it.isNotBlank() }.toSet()
        if (urls.isNotEmpty() || tvgIds.isNotEmpty()) {
            preferencesRepository.replaceFavorites(urls, tvgIds)
        }
    }
    private fun applyPersistedChannelFlags(
        channels: List<Channel>,
        existingByUrl: Map<String, Channel>
    ): List<Channel> {
        if (existingByUrl.isEmpty()) {
            return channels.mapIndexed { index, channel ->
                if (channel.position == index) channel else channel.copy(position = index)
            }
        }
        val existingByTvgId = existingByUrl.values
            .asSequence()
            .filter { it.tvgId.isNotBlank() }
            .associateBy { it.tvgId }
        return channels.mapIndexed { index, channel ->
            val existing = existingByUrl[channel.url]
                ?: channel.tvgId.takeIf { it.isNotBlank() }?.let { existingByTvgId[it] }
            if (existing == null) {
                if (channel.position == index) channel else channel.copy(position = index)
            } else {
                channel.copy(
                    aspectRatio = existing.aspectRatio,
                    position = index
                )
            }
        }
    }

    private suspend fun loadPlaylistInternal(
        forceReload: Boolean,
        skipNetworkIfCacheAvailable: Boolean,
        preservedChannels: Map<String, Channel>
    ): Result<List<Channel>> {
        val source = preferencesRepository.playlistSource.first()

        // Get stored hash and current hash
        val storedHash = preferencesRepository.playlistHash.first()
        if (!forceReload && skipNetworkIfCacheAvailable && source is PlaylistSource.Url && storedHash.isNotBlank()) {
            // Fast path: if we have a hash (meaning we successfully loaded/saved before),
            // and the DB has channels, use it immediately.
            val cachedChannels = channelRepository.getAllChannels()
            if (cachedChannels is Result.Success && cachedChannels.data.isNotEmpty()) {
                seedFavoriteBackupIfEmpty(cachedChannels.data)
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
            // Even if the playlist didn't change, DB could have been cleared by OS/data wipe,
            // so we only return cache if it's non-empty.
            val cachedChannels = channelRepository.getAllChannels()
            if (cachedChannels is Result.Success && cachedChannels.data.isNotEmpty()) {
                seedFavoriteBackupIfEmpty(cachedChannels.data)
                return cachedChannels
            }
        }

        // Parse playlist
        val parsedChannels = playlistParser.parse(content)

        if (parsedChannels.isEmpty()) {
            Timber.w("No channels found in playlist")
            return Result.Error(Exception("No channels found"))
        }

        val existingByUrl = if (preservedChannels.isNotEmpty()) {
            preservedChannels
        } else {
            when (val existing = channelRepository.getAllChannels()) {
                is Result.Success -> existing.data.associateBy { it.url }
                else -> emptyMap()
            }
        }
        val channelsToSave = applyPersistedChannelFlags(parsedChannels, existingByUrl)

        // Save to repository
        val existingFavorites = existingByUrl.values.asSequence()
            .filter { it.isFavorite }
            .toList()
        val existingFavoriteUrls = existingFavorites.asSequence()
            .map { it.url }
            .filter { it.isNotBlank() }
            .distinct()
            .toSet()
        val existingFavoriteTvgIds = existingFavorites.asSequence()
            .map { it.tvgId }
            .filter { it.isNotBlank() }
            .distinct()
            .toSet()

        val storedFavoriteUrls = preferencesRepository.favoriteUrls.first()
        val storedFavoriteTvgIds = preferencesRepository.favoriteTvgIds.first()

        val combinedFavoriteUrls = buildSet {
            addAll(existingFavoriteUrls)
            addAll(storedFavoriteUrls)
        }
        val combinedFavoriteTvgIds = buildSet {
            addAll(existingFavoriteTvgIds)
            addAll(storedFavoriteTvgIds)
        }

        val favoritesToApply = if (combinedFavoriteUrls.isEmpty() && combinedFavoriteTvgIds.isEmpty()) {
            emptyList()
        } else {
            channelsToSave.filter { channel ->
                combinedFavoriteUrls.contains(channel.url) ||
                    (channel.tvgId.isNotBlank() && combinedFavoriteTvgIds.contains(channel.tvgId))
            }
        }
        val favoriteUrlsHint = favoritesToApply.asSequence()
            .map { it.url }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val favoriteTvgIdsHint = favoritesToApply.asSequence()
            .map { it.tvgId }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        return when (val saveResult = channelRepository.saveChannelsPreservingFavorites(
            channelsToSave,
            favoriteUrls = favoriteUrlsHint,
            favoriteTvgIds = favoriteTvgIdsHint
        )) {
            is Result.Success -> {
                // Save hash
                preferencesRepository.savePlaylistHash(currentHash)
                preferencesRepository.replaceFavorites(
                    favoriteUrlsHint.toSet(),
                    favoriteTvgIdsHint.toSet()
                )
                val favorites = saveResult.data
                val channelsWithFavorites = if (favorites.isEmpty()) {
                    channelsToSave
                } else {
                    channelsToSave.map { channel ->
                        if (favorites.contains(channel.url)) channel.copy(isFavorite = true) else channel
                    }
                }
                Result.Success(channelsWithFavorites)
            }
            is Result.Error -> saveResult
            is Result.Loading -> Result.Error(Exception("Unexpected loading state"))
        }
    }

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
            return loadPlaylistInternal(
                forceReload = forceReload,
                skipNetworkIfCacheAvailable = skipNetworkIfCacheAvailable,
                preservedChannels = emptyMap()
            )
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
        val preservedChannels = when (val existing = channelRepository.getAllChannels()) {
            is Result.Success -> existing.data.associateBy { it.url }
            else -> emptyMap()
        }
        // Clear cache first
        preferencesRepository.clearPlaylistCache()
        channelRepository.clearAllChannels()

        return loadPlaylistInternal(
            forceReload = true,
            skipNetworkIfCacheAvailable = false,
            preservedChannels = preservedChannels
        )
    }

}
