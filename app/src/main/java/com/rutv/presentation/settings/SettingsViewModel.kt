package com.rutv.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rutv.data.model.PlayerConfig
import com.rutv.data.model.PlaylistSource
import com.rutv.data.repository.PreferencesRepository
import com.rutv.domain.usecase.LoadPlaylistUseCase
import com.rutv.domain.repository.EpgRepository
import com.rutv.util.Constants
import com.rutv.util.PlayerConstants
import com.rutv.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.rutv.util.logDebug
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val loadPlaylistUseCase: LoadPlaylistUseCase,
    private val epgRepository: EpgRepository
) : ViewModel() {

    private val _viewState = MutableStateFlow(SettingsViewState())
    val viewState: StateFlow<SettingsViewState> = _viewState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            combine(
                preferencesRepository.playlistSource,
                preferencesRepository.epgUrl,
                preferencesRepository.epgDaysAhead,
                preferencesRepository.epgDaysPast,
                preferencesRepository.epgPageDays
            ) { source, epgUrl, daysAhead, daysPast, pageDays ->
                SettingsSnapshot(source, epgUrl, daysAhead, daysPast, pageDays)
            }.collect { snap ->
                _viewState.update {
                    it.copy(
                        playlistSource = snap.source,
                        epgUrl = snap.epgUrl,
                        epgDaysAhead = snap.daysAhead,
                        epgDaysPast = snap.daysPast,
                        epgPageDays = snap.pageDays
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                preferencesRepository.playerConfig,
                preferencesRepository.showCurrentProgramInChannelList,
                preferencesRepository.autoRetryEnabled,
                preferencesRepository.autoRetryMaxAttempts,
                preferencesRepository.autoRetryPeriodSeconds
            ) { config, showCurrent, retryEnabled, retryMax, retryPeriod ->
                PlayerSettingsSnapshot(config, showCurrent, retryEnabled, retryMax, retryPeriod)
            }.collect { snap ->
                _viewState.update {
                    it.copy(
                        playerConfig = snap.config,
                        showCurrentProgramInChannelList = snap.showCurrent,
                        autoRetryEnabled = snap.retryEnabled,
                        autoRetryMaxAttempts = snap.retryMax,
                        autoRetryPeriodSeconds = snap.retryPeriod
                    )
                }
            }
        }

        viewModelScope.launch {
            preferencesRepository.appLanguage.collect { language ->
                _viewState.update { it.copy(selectedLanguage = language) }
            }
        }
    }

    private data class SettingsSnapshot(
        val source: PlaylistSource,
        val epgUrl: String,
        val daysAhead: Int,
        val daysPast: Int,
        val pageDays: Int
    )

    private data class PlayerSettingsSnapshot(
        val config: PlayerConfig,
        val showCurrent: Boolean,
        val retryEnabled: Boolean,
        val retryMax: Int,
        val retryPeriod: Int
    )

    /**
     * Save playlist from file content and return whether it succeeded.
     */
    suspend fun savePlaylistFromFile(content: String, displayName: String?): Boolean {
        if (content.length.toLong() > Constants.MAX_PLAYLIST_SIZE_BYTES) {
            _viewState.update { it.copy(error = "Playlist too large: ${content.length} bytes") }
            Timber.e("Playlist too large: ${content.length} bytes")
            return false
        }
        return try {
            preferencesRepository.savePlaylistFromFile(content, displayName)
            _viewState.update {
                it.copy(
                    successMessage = displayName?.let { name -> "Playlist \"$name\" saved" } ?: "Playlist saved from file",
                    error = null
                )
            }
            logDebug { "Playlist saved from file" }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _viewState.update { it.copy(error = "Failed to save playlist: ${e.message}") }
            Timber.e(e, "Failed to save playlist from file")
            false
        }
    }

    /**
     * Save playlist from URL and return whether it succeeded.
     */
    suspend fun savePlaylistFromUrl(url: String): Boolean {
        if (url.isBlank()) {
            _viewState.update { it.copy(error = "URL cannot be empty") }
            return false
        }
        return try {
            preferencesRepository.savePlaylistFromUrl(url)
            _viewState.update { it.copy(successMessage = "Playlist URL saved", error = null) }
            logDebug { "Playlist URL saved: $url" }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _viewState.update { it.copy(error = "Failed to save URL: ${e.message}") }
            Timber.e(e, "Failed to save playlist URL")
            false
        }
    }

    /**
     * Reload current playlist
     */
    fun reloadPlaylist() {
        viewModelScope.launch(Dispatchers.IO) {
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
                    logDebug { "Playlist reloaded: ${result.data.size} channels" }
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
                logDebug { "EPG URL saved: $url" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to save EPG URL")
            }
        }
    }

    fun clearEpgCache() {
        viewModelScope.launch(Dispatchers.Default) {
            epgRepository.clearCache()
            _viewState.update {
                it.copy(
                    successMessage = "EPG cache cleared",
                    error = null
                )
            }
            logDebug { "EPG cache cleared from settings" }
        }
    }

    /**
     * Update player configuration
     */
    fun updatePlayerConfig(config: PlayerConfig) {
        viewModelScope.launch {
            try {
                preferencesRepository.savePlayerConfig(config)
                logDebug { "Player config saved: $config" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to save player config")
            }
        }
    }

    /**
     * Update debug log visibility
     */
    fun setDebugLogEnabled(enabled: Boolean) {
        val currentConfig = _viewState.value.playerConfig
        val newConfig = currentConfig.copy(showDebugLog = enabled)
        updatePlayerConfig(newConfig)
    }

    /**
     * Update FFmpeg audio setting
     */
    fun setFfmpegAudioEnabled(enabled: Boolean) {
        val currentConfig = _viewState.value.playerConfig
        val newConfig = currentConfig.copy(useFfmpegAudio = enabled)
        updatePlayerConfig(newConfig)
    }

    /**
     * Update FFmpeg video setting
     */
    fun setFfmpegVideoEnabled(enabled: Boolean) {
        val currentConfig = _viewState.value.playerConfig
        val newConfig = currentConfig.copy(useFfmpegVideo = enabled)
        updatePlayerConfig(newConfig)
    }

    /**
     * Update buffer seconds
     */
    fun setBufferSeconds(seconds: Int) {
        val clampedSeconds = seconds.coerceIn(
            PlayerConstants.MIN_BUFFER_SECONDS,
            PlayerConstants.MAX_BUFFER_SECONDS
        )
        val currentConfig = _viewState.value.playerConfig
        val newConfig = currentConfig.copy(bufferSeconds = clampedSeconds)
        updatePlayerConfig(newConfig)
    }

    fun setShowCurrentProgramInChannelList(enabled: Boolean) {
        viewModelScope.launch {
            try {
                preferencesRepository.saveShowCurrentProgramInChannelList(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to save showCurrentProgramInChannelList")
            }
        }
    }

    fun setAutoRetryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                preferencesRepository.saveAutoRetryEnabled(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to save auto-retry enabled")
            }
        }
    }

    fun setAutoRetryMaxAttempts(attempts: Int) {
        viewModelScope.launch {
            val clamped = attempts.coerceIn(
                PlayerConstants.MIN_AUTO_RETRY_MAX_ATTEMPTS,
                PlayerConstants.MAX_AUTO_RETRY_MAX_ATTEMPTS
            )
            try {
                preferencesRepository.saveAutoRetryMaxAttempts(clamped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to save auto-retry max attempts")
            }
        }
    }

    fun setAutoRetryPeriodSeconds(seconds: Int) {
        viewModelScope.launch {
            val clamped = seconds.coerceIn(
                PlayerConstants.MIN_AUTO_RETRY_PERIOD_SECONDS,
                PlayerConstants.MAX_AUTO_RETRY_PERIOD_SECONDS
            )
            try {
                preferencesRepository.saveAutoRetryPeriodSeconds(clamped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to save auto-retry period seconds")
            }
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
                logDebug { "EPG days ahead saved: $clampedDays" }
            } catch (e: CancellationException) {
                throw e
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
                logDebug { "EPG days past saved: $clampedDays" }
            } catch (e: CancellationException) {
                throw e
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
                logDebug { "EPG page days saved: $clampedDays" }
            } catch (e: CancellationException) {
                throw e
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

    /**
     * Set app language
     */
    fun setAppLanguage(localeCode: String) {
        viewModelScope.launch {
            try {
                preferencesRepository.saveAppLanguage(localeCode)
                logDebug { "App language saved: $localeCode" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to save app language")
                _viewState.update { it.copy(error = "Failed to save language preference: ${e.message}") }
            }
        }
    }
}
