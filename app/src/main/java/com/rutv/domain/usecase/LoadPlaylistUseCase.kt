package com.rutv.domain.usecase

import com.rutv.data.model.Channel
import com.rutv.data.model.PlaylistSource
import com.rutv.data.remote.PlaylistLoader
import com.rutv.data.remote.PlaylistParser
import com.rutv.data.repository.PlaylistAccess
import com.rutv.data.repository.PreferencesRepository
import com.rutv.domain.repository.ChannelRepository
import com.rutv.util.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Source selection and commit ordering are shared by startup and settings. */
@Singleton
class LoadPlaylistUseCase @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val preferencesRepository: PreferencesRepository,
    private val playlistLoader: PlaylistLoader,
    private val playlistParser: PlaylistParser,
    private val access: PlaylistAccess
) {
    suspend operator fun invoke(
        forceReload: Boolean = false,
        skipNetworkIfCacheAvailable: Boolean = false
    ): Result<List<Channel>> = withContext(Dispatchers.IO) {
        val (revision, source) = access.begin { preferencesRepository.playlistSource.first() }
        try {
            val identity = sourceIdentity(source)
            val snapshot = channelRepository.getSnapshot()
            val cached = (snapshot as? Result.Success)?.data
            if (!forceReload && skipNetworkIfCacheAvailable && source is PlaylistSource.Url &&
                cached?.sourceIdentity == identity && cached.channels.isNotEmpty()) {
                return@withContext access.commit(revision) { Result.Success(cached.channels) }
            }
            val content = when (source) {
                is PlaylistSource.File -> source.content
                is PlaylistSource.Url -> when (val loaded = playlistLoader.loadFromUrl(source.url)) {
                    is Result.Success -> loaded.data
                    is Result.Error -> return@withContext access.commit(revision) { loaded }
                }
                PlaylistSource.None -> return@withContext access.commit(revision) { Result.Success(emptyList()) }
            }
            if (!playlistLoader.validateSize(content)) {
                return@withContext access.commit(revision) { Result.Error(IllegalArgumentException("Playlist is too large")) }
            }
            val hash = playlistParser.calculateHash(content)
            if (!forceReload && cached?.sourceIdentity == identity && cached.contentHash == hash && cached.channels.isNotEmpty()) {
                // Re-read under commit ordering to avoid returning favorite values captured before download.
                return@withContext access.commit(revision) { channelRepository.getAllChannels() }
            }
            val parsed = playlistParser.parse(content)
            if (parsed.isEmpty()) {
                return@withContext access.commit(revision) { Result.Error(IllegalArgumentException("No channels found")) }
            }
            access.commit(revision) { channelRepository.saveSnapshot(parsed, identity, hash) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            access.commit(revision) { Result.Error(e) }
        }
    }

    suspend fun reload(): Result<List<Channel>> = invoke(forceReload = true)

    internal fun sourceIdentity(source: PlaylistSource): String {
        val value = when (source) {
            is PlaylistSource.Url -> "url:" + source.url
            is PlaylistSource.File -> "file:" + source.content
            PlaylistSource.None -> "none"
        }
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
