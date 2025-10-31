package com.videoplayer.presentation.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videoplayer.data.model.PlayerConfig
import com.videoplayer.data.repository.ChannelRepository
import com.videoplayer.data.repository.PreferencesRepository
import com.videoplayer.domain.usecase.FetchEpgUseCase
import com.videoplayer.domain.usecase.LoadPlaylistUseCase
import com.videoplayer.util.Constants
import com.videoplayer.util.PlayerConstants
import com.videoplayer.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val channelRepository: ChannelRepository,
    private val loadPlaylistUseCase: LoadPlaylistUseCase,
    private val epgRepository: com.videoplayer.data.repository.EpgRepository,
    private val fetchEpgUseCase: FetchEpgUseCase
) : ViewModel() {

    private val _viewState = MutableStateFlow(SettingsViewState())
    val viewState: StateFlow<SettingsViewState> = _viewState.asStateFlow()

    init {
        loadSettings()
    }

    /**
     * Load current settings
     */
    private fun loadSettings() {
        viewModelScope.launch {
            // Load playlist source
            preferencesRepository.playlistSource.collect { source ->
                _viewState.update { it.copy(playlistSource = source) }
            }
        }

        viewModelScope.launch {
            // Load EPG URL
            preferencesRepository.epgUrl.collect { url ->
                _viewState.update { it.copy(epgUrl = url) }
            }
        }

        viewModelScope.launch {
            // Load EPG days ahead
            preferencesRepository.epgDaysAhead.collect { days ->
                _viewState.update { it.copy(epgDaysAhead = days) }
            }
        }

        viewModelScope.launch {
            // Load EPG days past (depth)
            preferencesRepository.epgDaysPast.collect { days ->
                _viewState.update { it.copy(epgDaysPast = days) }
            }
        }

        viewModelScope.launch {
            // Load EPG page size (days per page)
            preferencesRepository.epgPageDays.collect { days ->
                _viewState.update { it.copy(epgPageDays = days) }
            }
        }

        viewModelScope.launch {
            // Load player config
            preferencesRepository.playerConfig.collect { config ->
                _viewState.update { it.copy(playerConfig = config) }
            }
        }
    }

    /**
     * Save playlist from file content
     */
    fun savePlaylistFromFile(content: String, displayName: String?) {
        viewModelScope.launch {
            if (content.length.toLong() > Constants.MAX_PLAYLIST_SIZE_BYTES) {
                _viewState.update {
                    it.copy(error = "Playlist too large: ${content.length} bytes")
                }
                Timber.e("Playlist too large: ${content.length} bytes")
                return@launch
            }

            try {
                preferencesRepository.savePlaylistFromFile(content, displayName)
                _viewState.update {
                    it.copy(
                        successMessage = displayName?.let { name -> "Playlist \"$name\" saved" } ?: "Playlist saved from file",
                        error = null
                    )
                }
                Timber.d("Playlist saved from file")
            } catch (e: Exception) {
                _viewState.update {
                    it.copy(error = "Failed to save playlist: ${e.message}")
                }
                Timber.e(e, "Failed to save playlist from file")
            }
        }
    }

    /**
     * Save playlist from URL
     */
    fun savePlaylistFromUrl(url: String) {
        viewModelScope.launch {
            if (url.isBlank()) {
                _viewState.update { it.copy(error = "URL cannot be empty") }
                return@launch
            }

            try {
                preferencesRepository.savePlaylistFromUrl(url)
                _viewState.update {
                    it.copy(
                        successMessage = "Playlist URL saved",
                        error = null
                    )
                }
                Timber.d("Playlist URL saved: $url")
            } catch (e: Exception) {
                _viewState.update {
                    it.copy(error = "Failed to save URL: ${e.message}")
                }
                Timber.e(e, "Failed to save playlist URL")
            }
        }
    }

    /**
     * Reload current playlist
     */
    fun reloadPlaylist() {
        viewModelScope.launch {
            _viewState.update { it.copy(isLoading = true, error = null) }

            when (val result = loadPlaylistUseCase.reload()) {
                is Result.Success -> {
                    _viewState.update {
                        it.copy(
                            isLoading = false,
                            successMessage = "Playlist reloaded: ${result.data.size} channels",
                            error = null
                        )
                    }
                    Timber.d("Playlist reloaded: ${result.data.size} channels")
                }
                is Result.Error -> {
                    _viewState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to reload: ${result.message}"
                        )
                    }
                    Timber.e(result.exception, "Failed to reload playlist")
                }
                is Result.Loading -> {
                    // Already in loading state
                }
            }
        }
    }

    /**
     * Save EPG URL
     */
    fun saveEpgUrl(url: String) {
        viewModelScope.launch {
            try {
                preferencesRepository.saveEpgUrl(url.trim())
                Timber.d("EPG URL saved: $url")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save EPG URL")
            }
        }
    }

    /**
     * Update player configuration
     */
    fun updatePlayerConfig(config: PlayerConfig) {
        viewModelScope.launch {
            try {
                preferencesRepository.savePlayerConfig(config)
                Timber.d("Player config saved: $config")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save player config")
            }
        }
    }

    /**
     * Update debug log visibility
     */
    fun setDebugLogEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val currentConfig = _viewState.value.playerConfig
            val newConfig = currentConfig.copy(showDebugLog = enabled)
            updatePlayerConfig(newConfig)
        }
    }

    /**
     * Update FFmpeg audio setting
     */
    fun setFfmpegAudioEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val currentConfig = _viewState.value.playerConfig
            val newConfig = currentConfig.copy(useFfmpegAudio = enabled)
            updatePlayerConfig(newConfig)
        }
    }

    /**
     * Update FFmpeg video setting
     */
    fun setFfmpegVideoEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val currentConfig = _viewState.value.playerConfig
            val newConfig = currentConfig.copy(useFfmpegVideo = enabled)
            updatePlayerConfig(newConfig)
        }
    }

    /**
     * Update buffer seconds
     */
    fun setBufferSeconds(seconds: Int) {
        viewModelScope.launch {
            val clampedSeconds = seconds.coerceIn(
                PlayerConstants.MIN_BUFFER_SECONDS,
                PlayerConstants.MAX_BUFFER_SECONDS
            )
            val currentConfig = _viewState.value.playerConfig
            val newConfig = currentConfig.copy(bufferSeconds = clampedSeconds)
            updatePlayerConfig(newConfig)
        }
    }

    /**
     * Update EPG days ahead
     */
    fun setEpgDaysAhead(days: Int) {
        viewModelScope.launch {
            val clampedDays = days.coerceIn(1, 30)
            try {
                preferencesRepository.saveEpgDaysAhead(clampedDays)
                Timber.d("EPG days ahead saved: $clampedDays")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save EPG days ahead")
            }
        }
    }

    /**
     * Update EPG depth (past days)
     */
    fun setEpgDaysPast(days: Int) {
        viewModelScope.launch {
            val clampedDays = days.coerceIn(1, 60)
            try {
                preferencesRepository.saveEpgDaysPast(clampedDays)
                Timber.d("EPG days past saved: $clampedDays")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save EPG days past")
            }
        }
    }

    /**
     * Update EPG page size (days per page)
     */
    fun setEpgPageDays(days: Int) {
        viewModelScope.launch {
            val clampedDays = days.coerceIn(1, 14)
            try {
                preferencesRepository.saveEpgPageDays(clampedDays)
                Timber.d("EPG page days saved: $clampedDays")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save EPG page days")
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _viewState.update { it.copy(error = null) }
    }

    /**
     * Clear success message
     */
    fun clearSuccess() {
        _viewState.update { it.copy(successMessage = null) }
    }
}
