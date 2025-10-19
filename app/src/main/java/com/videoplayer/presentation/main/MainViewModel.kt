@file:Suppress("unused")

package com.videoplayer.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
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
import com.videoplayer.presentation.player.ArchiveEndReason
import com.videoplayer.presentation.player.DebugMessage
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

@UnstableApi
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

                when (state) {
                    is PlayerState.Ready -> {
                        _viewState.update {
                            it.copy(
                                currentChannel = state.channel,
                                currentChannelIndex = state.index,
                                isArchivePlayback = false,
                                archiveProgram = null,
                                archivePrompt = null
                            )
                        }
                        updateCurrentProgram(state.channel)
                    }
                    is PlayerState.Archive -> {
                        if (state.endReason == null) {
                            _viewState.update {
                                it.copy(
                                    currentChannel = state.channel,
                                    currentProgram = state.program,
                                    isArchivePlayback = true,
                                    archiveProgram = state.program,
                                    archivePrompt = null
                                )
                            }
                        } else {
                            viewModelScope.launch {
                                handleArchiveCompletion(state.channel, state.program)
                            }
                        }
                    }
                    else -> Unit
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
                _viewState.update { it.copy(showDebugLog = config.showDebugLog) }
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
                epgRepository.refreshCurrentProgramsCache()
                _viewState.update {
                    it.copy(
                        epgLoadedTimestamp = System.currentTimeMillis(),
                        currentProgramsMap = epgRepository.getCurrentProgramsSnapshot()
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
                            currentProgramsMap = epgRepository.getCurrentProgramsSnapshot(),
                            isLoading = false,
                            error = null
                        )
                    }

                    if (channels.isNotEmpty()) {
                        viewModelScope.launch(Dispatchers.Default) {
                            epgRepository.refreshCurrentProgramsCache()
                            _viewState.update { state ->
                                state.copy(currentProgramsMap = epgRepository.getCurrentProgramsSnapshot())
                            }
                        }

                        val catchupSupported = channels.count { it.supportsCatchup() }
                        appendDebugMessage(
                            DebugMessage("DVR: Playlist loaded (${channels.size} channels, catch-up: $catchupSupported)")
                        )

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

                    withContext(Dispatchers.Default) {
                        epgRepository.refreshCurrentProgramsCache()
                    }

                    _viewState.update {
                        it.copy(
                            epgLoadedTimestamp = System.currentTimeMillis(),
                            currentProgramsMap = epgRepository.getCurrentProgramsSnapshot()
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
                    showEpgPanel = false,
                    isArchivePlayback = false,
                    archiveProgram = null
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
        _viewState.update { current ->
            val showPlaylist = !current.showPlaylist
            current.copy(
                showPlaylist = showPlaylist,
                showFavoritesOnly = false,
                showEpgPanel = false,
                selectedProgramDetails = if (showPlaylist) current.selectedProgramDetails else null
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
        _viewState.update { current ->
            current.copy(
                showPlaylist = false,
                showEpgPanel = false,
                selectedProgramDetails = null
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

            _viewState.update { state ->
                val updatedMap = state.currentProgramsMap.toMutableMap()
                updatedMap[tvgId] = currentProgram
                state.copy(currentProgramsMap = updatedMap)
            }

            appendDebugMessage(
                DebugMessage(
                    "EPG: Showing ${programs.size} programs for $tvgId${currentProgram?.let { " (current: ${it.title})" } ?: ""}"
                )
            )

            _viewState.update {
                it.copy(
                    showEpgPanel = programs.isNotEmpty(),
                    epgChannelTvgId = tvgId,
                    epgPrograms = programs,
                    currentProgram = currentProgram
                )
            }
        }
    }

    fun returnToLive() {
        viewModelScope.launch {
            playerManager.returnToLive()
            _viewState.update {
                it.copy(
                    isArchivePlayback = false,
                    archiveProgram = null,
                    archivePrompt = null
                )
            }
        }
    }

    private suspend fun startArchivePlayback(channel: Channel, program: EpgProgram) {
        val durationMinutes = ((program.stopTimeMillis - program.startTimeMillis) / 60000L).coerceAtLeast(1)
        val ageMinutes = ((System.currentTimeMillis() - program.startTimeMillis) / 60000L).coerceAtLeast(0)
        appendDebugMessage(
            DebugMessage(
                "DVR: Request ${channel.title} • ${program.title} (start=${program.startTime}, duration=${durationMinutes}m, age=${ageMinutes}m, template=${if (channel.catchupSource.isBlank()) "<default>" else channel.catchupSource})"
            )
        )
        val started = playerManager.playArchive(channel, program)
        if (!started) return
        val channelIndex = _viewState.value.channels.indexOfFirst { it.url == channel.url }.coerceAtLeast(0)

        _viewState.update {
            it.copy(
                isArchivePlayback = true,
                archiveProgram = program,
                currentChannel = channel,
                currentChannelIndex = channelIndex,
                currentProgram = program,
                showPlaylist = false,
                showEpgPanel = false,
                archivePrompt = null
            )
        }
    }

    fun playArchiveProgram(program: EpgProgram) {
        viewModelScope.launch {
            val state = _viewState.value
            val channel = state.channels.firstOrNull { it.tvgId == state.epgChannelTvgId }
            if (channel == null) {
                appendDebugMessage(DebugMessage("DVR: Channel not found for program ${program.title}"))
                return@launch
            }
            if (!channel.supportsCatchup()) {
                appendDebugMessage(DebugMessage("DVR: Channel ${channel.title} does not support catch-up"))
                return@launch
            }

            val currentTime = System.currentTimeMillis()

            // Check if program has ended
            if (program.stopTimeMillis > currentTime) {
                appendDebugMessage(
                    DebugMessage("DVR: ${program.title} is still airing (ends in ${(program.stopTimeMillis - currentTime) / 60000} minutes)")
                )
                return@launch
            }

            // Check if program is within archive window
            val maxArchiveMillis = channel.catchupDays * 24L * 60 * 60 * 1000
            val age = currentTime - program.startTimeMillis
            if (maxArchiveMillis > 0 && age > maxArchiveMillis) {
                appendDebugMessage(
                    DebugMessage("DVR: ${program.title} is outside of ${channel.catchupDays} day archive")
                )
                return@launch
            }

            startArchivePlayback(channel, program)
        }
    }

    fun continueArchiveFromPrompt() {
        viewModelScope.launch {
            val prompt = _viewState.value.archivePrompt ?: return@launch
            val nextProgram = prompt.nextProgram
            if (nextProgram == null) {
                returnToLive()
                _viewState.update { it.copy(archivePrompt = null) }
                return@launch
            }
            startArchivePlayback(prompt.channel, nextProgram)
        }
    }

    fun dismissArchivePrompt() {
        returnToLive()
    }

    /**
     * Show program details
     */
    fun showProgramDetails(program: EpgProgram) {
        _viewState.update {
            it.copy(selectedProgramDetails = program)
        }
    }

    /**
     * Close program details
     */
    fun closeProgramDetails() {
        _viewState.update {
            it.copy(selectedProgramDetails = null)
        }
    }

    /**
     * Update current program for channel
     */
    private fun updateCurrentProgram(channel: Channel) {
        if (channel.hasEpg) {
            val program = epgRepository.getCurrentProgram(channel.tvgId)
            _viewState.update {
                val updatedMap = it.currentProgramsMap.toMutableMap()
                updatedMap[channel.tvgId] = program
                it.copy(
                    currentProgram = program,
                    currentProgramsMap = updatedMap
                )
            }
        } else {
            _viewState.update {
                val updatedMap = it.currentProgramsMap.toMutableMap()
                updatedMap.remove(channel.tvgId)
                it.copy(
                    currentProgram = null,
                    currentProgramsMap = updatedMap
                )
            }
        }
    }

    private suspend fun handleArchiveCompletion(channel: Channel, program: EpgProgram) {
        val programs = epgRepository.getProgramsForChannel(channel.tvgId)
        val nextProgram = programs
            .filter { it.startTimeMillis >= program.stopTimeMillis }
            .minByOrNull { it.startTimeMillis }

        appendDebugMessage(
            DebugMessage(
                "DVR: Completed ${program.title}${nextProgram?.let { " -> next ${it.title}" } ?: " (no next program)"}"
            )
        )

        _viewState.update {
            it.copy(
                isArchivePlayback = false,
                archiveProgram = null,
                archivePrompt = ArchivePrompt(channel, program, nextProgram)
            )
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
     * On activity paused
     */
    fun onPause() {
        playerManager.pause()
    }

    /**
     * On activity resumed
     */
    fun onResume() {
        playerManager.resume()
    }

    /**
     * Clean up
     */
    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }
}







