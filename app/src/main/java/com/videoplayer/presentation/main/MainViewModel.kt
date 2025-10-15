package com.videoplayer.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.ui.AspectRatioFrameLayout
import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.data.repository.ChannelRepository
import com.videoplayer.data.repository.EpgRepository
import com.videoplayer.data.repository.PreferencesRepository
import com.videoplayer.domain.usecase.FetchEpgUseCase
import com.videoplayer.domain.usecase.LoadPlaylistUseCase
import com.videoplayer.domain.usecase.ToggleFavoriteUseCase
import com.videoplayer.domain.usecase.UpdateAspectRatioUseCase
import com.videoplayer.presentation.player.DebugMessage
import com.videoplayer.presentation.player.PlayerEvent
import com.videoplayer.presentation.player.PlayerManager
import com.videoplayer.presentation.player.PlayerState
import com.videoplayer.util.Constants
import com.videoplayer.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val playerManager: PlayerManager,
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val preferencesRepository: PreferencesRepository,
    private val loadPlaylistUseCase: LoadPlaylistUseCase,
    private val fetchEpgUseCase: FetchEpgUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val updateAspectRatioUseCase: UpdateAspectRatioUseCase
) : ViewModel() {

    private val _viewState = MutableStateFlow(MainViewState())
    val viewState: StateFlow<MainViewState> = _viewState.asStateFlow()

    private val debugMessageList = mutableListOf<DebugMessage>()
    private val debugMessageMutex = Mutex()

    init {
        // Collect player state
        viewModelScope.launch {
            playerManager.playerState.collect { state ->
                _viewState.update { it.copy(playerState = state) }

                // Update current channel when player state changes
                if (state is PlayerState.Ready) {
                    _viewState.update {
                        it.copy(
                            currentChannel = state.channel,
                            currentChannelIndex = state.index
                        )
                    }
                    updateCurrentProgram(state.channel)
                }
            }
        }

        // Collect debug messages
        viewModelScope.launch {
            playerManager.debugMessages.collect { message ->
                appendDebugMessage(message)
            }
        }

        // Collect player config
        viewModelScope.launch {
            preferencesRepository.playerConfig.collect { config ->
                _viewState.update {
                    it.copy(
                        showDebugLog = config.showDebugLog,
                        showCurrentProgram = config.showCurrentProgram
                    )
                }
            }
        }

        // Load cached EPG data on startup
        loadCachedEpg()

        // Load playlist automatically
        loadPlaylist()
    }

    /**
     * Load cached EPG data from disk on app startup
     */
    private fun loadCachedEpg() {
        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("Loading cached EPG on startup")
            val cachedEpg = epgRepository.loadEpgData()
            if (cachedEpg != null) {
                Timber.d("Cached EPG loaded successfully")
                appendDebugMessage(
                    DebugMessage(
                        "EPG: Loaded cached data (${cachedEpg.totalPrograms} programs for ${cachedEpg.channelsFound} channels)"
                    )
                )
                // Build current programs cache for fast channel list display
                epgRepository.buildCurrentProgramsCache()

                // Build current programs map for UI
                val currentProgramsMap = buildCurrentProgramsMap()

                _viewState.update {
                    it.copy(
                        epgLoadedTimestamp = System.currentTimeMillis(),
                        currentProgramsMap = currentProgramsMap
                    )
                }
            } else {
                Timber.d("No cached EPG found - will fetch when playlist is loaded")
                appendDebugMessage(DebugMessage("EPG: No cached data found"))
            }
        }
    }

    /**
     * Load playlist from saved source
     */
    fun loadPlaylist(forceReload: Boolean = false) {
        viewModelScope.launch {
            _viewState.update { it.copy(isLoading = true, error = null) }

            when (val result = if (forceReload) {
                loadPlaylistUseCase.reload()
            } else {
                loadPlaylistUseCase()
            }) {
                is Result.Success -> {
                    val channels = result.data
                    _viewState.update {
                        it.copy(
                            channels = channels,
                            isLoading = false,
                            error = null
                        )
                    }

                    if (channels.isNotEmpty()) {
                        // Initialize player
                        initializePlayer()

                        // Fetch EPG (will check if URL is configured)
                        appendDebugMessage(
                            DebugMessage("EPG: Preparing to fetch data for ${channels.count { it.hasEpg }} channels")
                        )
                        fetchEpg()
                    } else {
                        Timber.d("No channels loaded, skipping EPG fetch")
                        appendDebugMessage(DebugMessage("EPG: Playlist empty, skipping fetch"))
                    }
                }
                is Result.Error -> {
                    Timber.e(result.exception, "Error loading playlist")
                    appendDebugMessage(
                        DebugMessage("EPG: Playlist load failed (${result.message ?: "unknown error"})")
                    )
                    _viewState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message ?: "Failed to load playlist"
                        )
                    }
                }
                is Result.Loading -> {
                    // Should not happen
                }
            }
        }
    }

    /**
     * Initialize player with current channels
     */
    private fun initializePlayer() {
        viewModelScope.launch {
            val config = preferencesRepository.playerConfig.first()
            val lastPlayedIndex = preferencesRepository.lastPlayedIndex.first()
            val channels = _viewState.value.channels

            if (channels.isNotEmpty()) {
                val startIndex = if (lastPlayedIndex >= 0 && lastPlayedIndex < channels.size) {
                    lastPlayedIndex
                } else {
                    0
                }

                playerManager.initialize(channels, config, startIndex)
            }
        }
    }

    /**
     * Fetch EPG data
     */
    fun fetchEpg() {
        viewModelScope.launch {
            appendDebugMessage(DebugMessage("EPG: Fetch started"))
            when (val fetchResult = fetchEpgUseCase()) {
                is Result.Success -> {
                    Timber.d("EPG fetched successfully")
                    val response = fetchResult.data
                    appendDebugMessage(
                        DebugMessage(
                            "EPG: Fetch complete (${response.totalPrograms} programs, ${response.channelsFound}/${response.channelsRequested} channels)"
                        )
                    )

                    // Build current programs cache for fast channel list display (on IO thread)
                    withContext(Dispatchers.IO) {
                        epgRepository.buildCurrentProgramsCache()
                    }

                    // Build current programs map for UI
                    val currentProgramsMap = buildCurrentProgramsMap()

                    // Update state with timestamp to trigger adapter refresh
                    _viewState.update {
                        it.copy(
                            epgLoadedTimestamp = System.currentTimeMillis(),
                            currentProgramsMap = currentProgramsMap
                        )
                    }

                    // Update current program for current channel
                    _viewState.value.currentChannel?.let { channel ->
                        updateCurrentProgram(channel)
                    }
                }
                is Result.Error -> {
                    Timber.w("EPG fetch failed")
                    val message = fetchResult.message ?: "unknown error"
                    appendDebugMessage(DebugMessage("EPG: Fetch failed ($message)"))
                }
                is Result.Loading -> {
                    // Should not happen
                }
            }
        }
    }

    /**
     * Play channel at index
     */
    fun playChannel(index: Int) {
        viewModelScope.launch {
            playerManager.playChannel(index)

            // Save last played index
            preferencesRepository.saveLastPlayedIndex(index)

            // Hide playlist and EPG
            _viewState.update {
                it.copy(
                    showPlaylist = false,
                    showEpgPanel = false
                )
            }
        }
    }

    /**
     * Toggle favorite for channel
     */
    fun toggleFavorite(channelUrl: String) {
        viewModelScope.launch {
            when (val result = toggleFavoriteUseCase(channelUrl)) {
                is Result.Success -> {
                    // Reload channels to reflect changes
                    val updatedChannels = channelRepository.getAllChannels()
                    if (updatedChannels is Result.Success) {
                        _viewState.update { it.copy(channels = updatedChannels.data) }
                    }
                }
                is Result.Error -> {
                    Timber.e(result.exception, "Error toggling favorite")
                }
                is Result.Loading -> {
                    // Should not happen
                }
            }
        }
    }

    /**
     * Toggle playlist visibility
     */
    fun togglePlaylist() {
        _viewState.update {
            it.copy(
                showPlaylist = !it.showPlaylist,
                showFavoritesOnly = false,
                showEpgPanel = false
            )
        }
    }

    /**
     * Toggle favorites view
     */
    fun toggleFavorites() {
        _viewState.update {
            it.copy(
                showPlaylist = !it.showPlaylist,
                showFavoritesOnly = true,
                showEpgPanel = false
            )
        }
    }

    /**
     * Close playlist
     */
    fun closePlaylist() {
        _viewState.update {
            it.copy(
                showPlaylist = false,
                showEpgPanel = false
            )
        }
    }

    /**
     * Show EPG for channel
     */
    fun showEpgForChannel(tvgId: String) {
        viewModelScope.launch {
            val programs = epgRepository.getProgramsForChannel(tvgId)
            val currentProgram = epgRepository.getCurrentProgram(tvgId)

            appendDebugMessage(
                DebugMessage(
                    "EPG: Showing ${programs.size} programs for $tvgId${currentProgram?.let { " (current: ${it.title})" } ?: ""}"
                )
            )

            _viewState.update {
                it.copy(
                    showEpgPanel = programs.isNotEmpty(),
                    epgPrograms = programs,
                    currentProgram = currentProgram
                )
            }
        }
    }

    /**
     * Close EPG panel
     */
    fun closeEpg() {
        _viewState.update {
            it.copy(showEpgPanel = false)
        }
    }

    /**
     * Update current program for channel
     */
    private fun updateCurrentProgram(channel: Channel) {
        if (channel.hasEpg) {
            val program = epgRepository.getCurrentProgram(channel.tvgId)
            _viewState.update { it.copy(currentProgram = program) }
        }
    }

    private suspend fun appendDebugMessage(message: DebugMessage) {
        debugMessageMutex.withLock {
            debugMessageList.add(message)
            while (debugMessageList.size > 200) {
                debugMessageList.removeAt(0)
            }
            _viewState.update { it.copy(debugMessages = debugMessageList.toList()) }
        }
    }

    /**
     * Cycle aspect ratio
     */
    fun cycleAspectRatio() {
        viewModelScope.launch {
            val currentMode = _viewState.value.currentResizeMode
            val newMode = when (currentMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT ->
                    AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectRatioFrameLayout.RESIZE_MODE_FILL ->
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else ->
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
            }

            _viewState.update { it.copy(currentResizeMode = newMode) }

            // Save to repository
            _viewState.value.currentChannel?.let { channel ->
                updateAspectRatioUseCase(channel.url, newMode)
            }
        }
    }

    /**
     * Toggle video rotation
     */
    fun toggleRotation() {
        val currentRotation = _viewState.value.videoRotation
        val newRotation = if (currentRotation == Constants.VIDEO_ROTATION_0) {
            Constants.VIDEO_ROTATION_270
        } else {
            Constants.VIDEO_ROTATION_0
        }

        _viewState.update { it.copy(videoRotation = newRotation) }
    }

    /**
     * Get player instance
     */
    fun getPlayer() = playerManager.getPlayer()

    /**
     * Build a map of current programs for all channels with EPG
     * This is called once when EPG is loaded, not for every channel in the list
     * Returns empty map if showCurrentProgram is disabled
     */
    private fun buildCurrentProgramsMap(): Map<String, EpgProgram?> {
        // Only build map if the feature is enabled
        if (!_viewState.value.showCurrentProgram) {
            Timber.d("Show current program is disabled - skipping map build")
            return emptyMap()
        }

        val channels = _viewState.value.channels
        return channels
            .filter { it.hasEpg }
            .associate { channel ->
                channel.tvgId to epgRepository.getCurrentProgram(channel.tvgId)
            }
    }

    /**
     * On activity paused
     */
    fun onPause() {
        playerManager.pause()
    }

    /**
     * On activity resumed
     */
    fun onResume() {
        // Player will auto-resume if it was playing
    }

    /**
     * Clean up
     */
    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }
}
