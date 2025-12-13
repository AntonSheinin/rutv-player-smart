package com.rutv.presentation.main.usecase

import com.rutv.data.model.Channel
import com.rutv.data.model.PlaylistSource
import com.rutv.data.repository.PreferencesRepository
import com.rutv.domain.usecase.LoadPlaylistUseCase
import com.rutv.util.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Startup orchestration (data-only): resolve configured playlist source and load channels.
 *
 * Player initialization and UI state updates remain outside, so this stays easy to reason about
 * and safe to call from any ViewModel.
 */
class InitializeAppUseCase @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val loadPlaylistUseCase: LoadPlaylistUseCase
) {
    data class StartupPlaylist(
        val source: PlaylistSource,
        val channels: List<Channel>
    )

    suspend fun loadStartupPlaylist(): Result<StartupPlaylist> {
        try {
            val source = preferencesRepository.playlistSource.first()
            val result = when (source) {
                is PlaylistSource.Url -> loadPlaylistUseCase(skipNetworkIfCacheAvailable = true)
                else -> loadPlaylistUseCase()
            }
            return when (result) {
                is Result.Success -> Result.Success(StartupPlaylist(source = source, channels = result.data))
                is Result.Error -> result
                is Result.Loading -> Result.Error(IllegalStateException("Unexpected loading state"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Error(e)
        }
    }

    suspend fun refreshUrlPlaylistInBackground(): Result<List<Channel>> {
        try {
            val source = preferencesRepository.playlistSource.first()
            if (source !is PlaylistSource.Url) return Result.Success(emptyList())
            return loadPlaylistUseCase()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Error(e)
        }
    }
}


