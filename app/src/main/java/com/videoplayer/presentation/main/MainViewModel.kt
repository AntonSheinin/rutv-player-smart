@file:Suppress("unused")

package com.videoplayer.presentation.main

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.data.model.EpgResponse
import com.videoplayer.data.repository.ChannelRepository
import com.videoplayer.data.repository.EpgRepository
import com.videoplayer.data.repository.PreferencesRepository
import com.videoplayer.domain.usecase.FetchEpgUseCase
import com.videoplayer.domain.usecase.LoadEpgForChannelUseCase
import com.videoplayer.domain.usecase.LoadPlaylistUseCase
import com.videoplayer.domain.usecase.PlayArchiveProgramUseCase
import com.videoplayer.domain.usecase.ToggleFavoriteUseCase
import com.videoplayer.domain.usecase.UpdateAspectRatioUseCase
import com.videoplayer.domain.usecase.WatchFromBeginningUseCase
import com.videoplayer.presentation.player.DebugMessage
import com.videoplayer.presentation.player.PlayerManager
import com.videoplayer.presentation.player.PlayerState
import com.videoplayer.util.Constants
import com.videoplayer.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val updateAspectRatioUseCase: UpdateAspectRatioUseCase,
    private val playArchiveProgramUseCase: PlayArchiveProgramUseCase,
    private val watchFromBeginningUseCase: WatchFromBeginningUseCase,
    private val loadEpgForChannelUseCase: LoadEpgForChannelUseCase
) : ViewModel() {

    private val _viewState = MutableStateFlow(MainViewState())
    val viewState: StateFlow<MainViewState> = _viewState.asStateFlow()

    private val debugMessageList = mutableListOf<DebugMessage>()
    private val debugMessageMutex = Mutex()
    private var pendingCurrentProgramsSnapshot: Map<String, EpgProgram?>? = null
    private var pendingEpgTimestamp: Long? = null
    private var epgPreloadJob: Job? = null

    private fun postEpgNotification(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            if (_viewState.value.epgNotificationMessage == message) return@launch
            _viewState.update { it.copy(epgNotificationMessage = message) }
        }
    }

    fun clearEpgNotification() {
        viewModelScope.launch(Dispatchers.Main) {
            _viewState.update { it.copy(epgNotificationMessage = null) }
        }
    }

    init {
        // Collect player state
        viewModelScope.launch {
            playerManager.playerState.collect { state ->
                _viewState.update { it.copy(playerState = state) }

                when (state) {
                    is PlayerState.Ready -> {
                        _viewState.update {
                            val channelChanged = it.currentChannelIndex != state.index ||
                                it.currentChannel?.url != state.channel.url
                            it.copy(
                                currentChannel = state.channel,
                                currentChannelIndex = state.index,
                                isArchivePlayback = false,
                                isTimeshiftPlayback = if (channelChanged) false else it.isTimeshiftPlayback,
                                archiveProgram = null,
                                archivePrompt = null
                            )
                        }
                        // Update current program (will wait if EPG not loaded yet)
                        viewModelScope.launch(Dispatchers.Default) {
                            updateCurrentProgram(state.channel)
                        }
                    }
                    is PlayerState.Archive -> {
                        if (state.endReason == null) {
                            _viewState.update {
                                it.copy(
                                    currentChannel = state.channel,
                                    currentProgram = state.program,
                                    isArchivePlayback = true,
                                    isTimeshiftPlayback = false,
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

        // Initialize app: load EPG cache FIRST, then playlist
        // This ensures EPG data is ready before player starts
        initializeApp()
    }

    /**
     * Initialize app in proper order: EPG cache -> Playlist -> Player
     * This prevents race conditions and ensures EPG is ready
     */
    private fun initializeApp() {
        // Kick off cached EPG loading on a background thread so it never blocks playlist/player startup.
        epgPreloadJob = viewModelScope.launch(Dispatchers.IO) {
            preloadCachedEpg()
        }

        // Load playlist and initialise the player in parallel with the EPG preload.
        viewModelScope.launch(Dispatchers.IO) {
            loadPlaylistAndPlayer()
        }
    }

    private suspend fun preloadCachedEpg() {
        try {
            Timber.d("App Init: Background EPG preload - Loading cached EPG")
            val cachedEpg = epgRepository.loadEpgData()
            if (cachedEpg != null) {
                Timber.d("App Init: Cached EPG loaded (${cachedEpg.totalPrograms} programs)")
                appendDebugMessage(
                    DebugMessage("EPG: Loaded cached data (${cachedEpg.totalPrograms} programs for ${cachedEpg.channelsFound} channels)")
                )

                val loadedAt = System.currentTimeMillis()
                epgRepository.refreshCurrentProgramsCache()
                deliverCurrentProgramsSnapshot(
                    epgRepository.getCurrentProgramsSnapshot(),
                    loadedAt
                )
            } else {
                Timber.d("App Init: No cached EPG found")
                appendDebugMessage(DebugMessage("EPG: No cached data found"))
            }
        } catch (e: Exception) {
            Timber.e(e, "App Init: Error loading cached EPG")
            appendDebugMessage(
                DebugMessage("EPG: Failed to load cached data (${e.message ?: "unknown error"})")
            )
        }
    }

    private suspend fun loadPlaylistAndPlayer() {
        try {
            Timber.d("App Init: Step 2 - Loading playlist")
            withContext(Dispatchers.Main) {
                _viewState.update { it.copy(isLoading = true, error = null) }
            }

            when (val result = loadPlaylistUseCase()) {
                is Result.Success -> {
                    val channels = result.data
                    Timber.d("App Init: Playlist loaded (${channels.size} channels)")

                    withContext(Dispatchers.Main) {
                        _viewState.update {
                            it.copy(
                                channels = channels,
                                isLoading = false,
                                error = null
                            )
                        }
                    }

                    if (channels.isNotEmpty()) {
                        val catchupSupported = channels.count { it.supportsCatchup() }
                        appendDebugMessage(
                            DebugMessage("DVR: Playlist loaded (${channels.size} channels, catch-up: $catchupSupported)")
                        )
                    } else {
                        Timber.d("App Init: No channels loaded")
                        appendDebugMessage(DebugMessage("EPG: Playlist empty"))
                    }

                    flushPendingCurrentProgramsIfReady()

                    if (channels.isNotEmpty()) {
                        Timber.d("App Init: Step 3 - Initializing player")
                        initializePlayer(channels)

                        Timber.d("App Init: Step 4 - Checking EPG freshness")
                        fetchEpgIfNeeded()
                    }
                }
                is Result.Error -> {
                    Timber.e(result.exception, "App Init: Failed to load playlist")
                    appendDebugMessage(
                        DebugMessage("EPG: Playlist load failed (${result.message ?: "unknown error"})")
                    )
                    withContext(Dispatchers.Main) {
                        _viewState.update {
                            it.copy(
                                isLoading = false,
                                error = result.message ?: "Failed to load playlist"
                            )
                        }
                    }
                }
                is Result.Loading -> {
                    // Should not happen
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "App Init: Error during initialization")
            withContext(Dispatchers.Main) {
                _viewState.update {
                    it.copy(
                        isLoading = false,
                        error = "Initialization failed: ${e.message}"
                    )
                }
            }
        }
    }

    private fun deliverCurrentProgramsSnapshot(
        snapshot: Map<String, EpgProgram?>,
        loadedAt: Long
    ) {
        viewModelScope.launch(Dispatchers.Main) {
            if (_viewState.value.channels.isEmpty()) {
                pendingCurrentProgramsSnapshot = snapshot
                pendingEpgTimestamp = loadedAt
            } else {
                pendingCurrentProgramsSnapshot = null
                pendingEpgTimestamp = null
                _viewState.update {
                    val currentChannel = it.currentChannel
                    val updatedCurrentProgram = currentChannel?.tvgId?.let(snapshot::get)
                    it.copy(
                        currentProgramsMap = snapshot,
                        currentProgram = updatedCurrentProgram,
                        epgLoadedTimestamp = loadedAt
                    )
                }
                if (snapshot.isNotEmpty()) {
                    postEpgNotification(EPG_LOADED_MESSAGE)
                }
            }
        }
    }

    private fun flushPendingCurrentProgramsIfReady() {
        val pendingSnapshot = pendingCurrentProgramsSnapshot ?: return
        val timestamp = pendingEpgTimestamp ?: System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.Main) {
            if (_viewState.value.channels.isEmpty()) return@launch
            pendingCurrentProgramsSnapshot = null
            pendingEpgTimestamp = null
            // Refresh snapshot with latest data from repository to ensure current programs are up-to-date
            val freshSnapshot = epgRepository.getCurrentProgramsSnapshot()
            val snapshot = if (freshSnapshot.isNotEmpty()) freshSnapshot else pendingSnapshot
            _viewState.update {
                val currentChannel = it.currentChannel
                val updatedCurrentProgram = currentChannel?.tvgId?.let(snapshot::get)
                it.copy(
                    currentProgramsMap = snapshot,
                    currentProgram = updatedCurrentProgram,
                    epgLoadedTimestamp = timestamp
                )
            }
            if (snapshot.isNotEmpty()) {
                postEpgNotification(EPG_LOADED_MESSAGE)
            }
        }
    }

    /**
     * Load playlist from saved source
     * Called when user returns from settings or manually reloads
     */
    fun loadPlaylist(forceReload: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val wasArchivePlayback = _viewState.value.isArchivePlayback
                val archiveProgramToResume = _viewState.value.archiveProgram
                val archiveChannelUrl = _viewState.value.currentChannel?.url
                val archiveChannelTvgId = _viewState.value.currentChannel?.tvgId

                withContext(Dispatchers.Main) {
                    _viewState.update { it.copy(isLoading = true, error = null) }
                }

                val result = if (forceReload) {
                    loadPlaylistUseCase.reload()
                } else {
                    loadPlaylistUseCase()
                }

                when (result) {
                    is Result.Success -> {
                        val channels = result.data

                        val programsMapToUse = if (forceReload) {
                            epgRepository.clearCache()
                            preferencesRepository.saveLastEpgFetchTimestamp(0L)
                            emptyMap()
                        } else {
                            // Refresh current programs cache if EPG data exists
                            // Note: After "Force EPG Fetch", cache will be empty until fetch completes
                            // So we preserve the existing currentProgramsMap to avoid showing empty state
                            val existingProgramsMap = _viewState.value.currentProgramsMap
                            val cachedEpg = epgRepository.loadEpgData()
                            if (cachedEpg != null) {
                                epgRepository.refreshCurrentProgramsCache()
                            }
                            val newProgramsMap = epgRepository.getCurrentProgramsSnapshot()
                            newProgramsMap.ifEmpty { existingProgramsMap }
                        }

                        withContext(Dispatchers.Main) {
                            _viewState.update {
                                val updatedCurrentProgram = it.currentChannel?.tvgId?.let(programsMapToUse::get)
                                it.copy(
                                    channels = channels,
                                    currentProgramsMap = programsMapToUse,
                                    currentProgram = updatedCurrentProgram ?: it.currentProgram,
                                    isLoading = false,
                                    error = null
                                )
                            }
                            if (programsMapToUse.isNotEmpty()) {
                                postEpgNotification(EPG_LOADED_MESSAGE)
                            }

                            if (channels.isNotEmpty()) {
                                val catchupSupported = channels.count { it.supportsCatchup() }
                                appendDebugMessage(
                                    DebugMessage("DVR: Playlist loaded (${channels.size} channels, catch-up: $catchupSupported)")
                                )

                            } else {
                                Timber.d("No channels loaded")
                                appendDebugMessage(DebugMessage("EPG: Playlist empty"))
                            }
                        }

                        if (channels.isNotEmpty()) {
                            initializePlayer(channels)

                            val resumeChannel = when {
                                wasArchivePlayback && archiveProgramToResume != null -> {
                                    channels.firstOrNull { it.url == archiveChannelUrl }
                                        ?: channels.firstOrNull { it.tvgId.isNotBlank() && it.tvgId == archiveChannelTvgId }
                                }
                                else -> null
                            }
                            if (resumeChannel != null && archiveProgramToResume != null) {
                                withContext(Dispatchers.Main) {
                                    startArchivePlayback(resumeChannel, archiveProgramToResume)
                                }
                            } else if (wasArchivePlayback) {
                                _viewState.update { state ->
                                    state.copy(isArchivePlayback = false, isTimeshiftPlayback = false, archiveProgram = null)
                                }
                            }

                            if (forceReload) {
                                fetchEpg(forceUpdate = true)
                            } else {
                                fetchEpgIfNeeded()
                            }
                        }
                    }
                    is Result.Error -> {
                        Timber.e(result.exception, "Error loading playlist")
                        appendDebugMessage(
                            DebugMessage("EPG: Playlist load failed (${result.message ?: "unknown error"})")
                        )
                        withContext(Dispatchers.Main) {
                            _viewState.update {
                                it.copy(
                                    isLoading = false,
                                    error = result.message ?: "Failed to load playlist"
                                )
                            }
                        }
                    }
                    is Result.Loading -> {
                        // Should not happen
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error in loadPlaylist")
                withContext(Dispatchers.Main) {
                    _viewState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to load playlist: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    /**
     * Initialize player with current channels
     */
    private suspend fun initializePlayer(channels: List<Channel>) {
        if (channels.isEmpty()) return

        // Read preferences on IO thread
        val config = preferencesRepository.playerConfig.first()
        val lastPlayedIndex = preferencesRepository.lastPlayedIndex.first()

        val startIndex = if (lastPlayedIndex >= 0 && lastPlayedIndex < channels.size) {
            lastPlayedIndex
        } else {
            0
        }

        playerManager.initialize(channels, config, startIndex)
    }

    /**
     * Fetch EPG data only if needed (not more than once per day)
     */
    private fun fetchEpgIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            // Ensure any background preload has completed so we reuse the cached data it loaded.
            epgPreloadJob?.join()
            val channels = _viewState.value.channels
            if (channels.isEmpty()) {
                appendDebugMessage(DebugMessage("EPG: No channels loaded yet, skipping fetch"))
                return@launch
            }

            val channelsWithEpg = channels.filter { it.hasEpg }
            if (channelsWithEpg.isEmpty()) {
                appendDebugMessage(DebugMessage("EPG: No channels with EPG support, skipping fetch"))
                return@launch
            }

            val epgDaysAhead = preferencesRepository.epgDaysAhead.first()
            val window = epgRepository.calculateWindow(channelsWithEpg, epgDaysAhead)

            val cachedEpg = epgRepository.loadEpgData()
            val lastFetchTimestamp = preferencesRepository.lastEpgFetchTimestamp.first()
            val currentTime = System.currentTimeMillis()
            val hoursSinceLastFetch = (currentTime - lastFetchTimestamp) / (1000 * 60 * 60)
            val coverageSufficient = epgRepository.coversWindow(window)

            if (cachedEpg != null && coverageSufficient && lastFetchTimestamp > 0 && hoursSinceLastFetch < 24) {
                appendDebugMessage(
                    DebugMessage("EPG: Using cached data (fetched ${hoursSinceLastFetch}h ago, ${cachedEpg.totalPrograms} programs)")
                )
                Timber.d("EPG: Cached data covers desired window; skipping fetch")

                _viewState.value.currentChannel?.let { channel ->
                    updateCurrentProgram(channel)
                }
                return@launch
            }

            if (!coverageSufficient) {
                appendDebugMessage(DebugMessage("EPG: Cached data does not cover desired window, fetching missing data"))
            } else if (cachedEpg == null) {
                appendDebugMessage(DebugMessage("EPG: No cached data, fetching from service"))
            } else {
                appendDebugMessage(DebugMessage("EPG: Cached data is ${hoursSinceLastFetch}h old, refreshing"))
            }

            fetchEpg(forceUpdate = true)
        }
    }

    /**
     * Fetch EPG data from service
     */
    fun fetchEpg(forceUpdate: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!forceUpdate) {
                appendDebugMessage(DebugMessage("EPG: Manual fetch requested"))
            }
            appendDebugMessage(DebugMessage("EPG: Fetch started"))

            val fetchResult: Result<EpgResponse> = try {
                fetchEpgUseCase()
            } catch (e: Exception) {
                Timber.e(e, "EPG fetch threw an exception")
                Result.Error(exception = e)
            }

            when (fetchResult) {
                is Result.Success -> {
                    Timber.d("EPG fetched successfully")
                    val response = fetchResult.data
                    appendDebugMessage(
                        DebugMessage(
                            "EPG: Fetch complete (${response.totalPrograms} programs, ${response.channelsFound}/${response.channelsRequested} channels)"
                        )
                    )

                    val timestamp = System.currentTimeMillis()
                    preferencesRepository.saveLastEpgFetchTimestamp(timestamp)

                    val snapshot = withContext(Dispatchers.Default) {
                        epgRepository.refreshCurrentProgramsCache()
                        epgRepository.getCurrentProgramsSnapshot()
                    }
                    deliverCurrentProgramsSnapshot(snapshot, timestamp)

                    _viewState.value.currentChannel?.let { channel ->
                        updateCurrentProgram(channel)
                    }
                }
                is Result.Error -> {
                    Timber.w("EPG fetch failed: ${fetchResult.message}")
                    val message = fetchResult.message ?: fetchResult.exception.message ?: "unknown error"
                    appendDebugMessage(DebugMessage("EPG: Fetch failed ($message)"))
                }
                Result.Loading -> Unit
            }
        }
    }

    fun onSystemTimeOrTimezoneChanged(action: String?) {
        viewModelScope.launch {
            val trigger = mapTimeChangeTrigger(action)
            val result = withContext(Dispatchers.Default) {
                epgRepository.handleSystemTimeOrTimezoneChange(trigger)
            }

            when (result) {
                EpgRepository.TimeChangeResult.TIMEZONE_CHANGED -> {
                    Timber.i("System timezone change detected (action=$action); clearing EPG cache and refetching")
                    appendDebugMessage(DebugMessage("EPG: System timezone changed, refreshing data"))
                    withContext(Dispatchers.IO) {
                        preferencesRepository.saveLastEpgFetchTimestamp(0L)
                    }
                    _viewState.update {
                        it.copy(
                            currentProgram = null,
                            currentProgramsMap = emptyMap(),
                            epgLoadedTimestamp = 0L
                        )
                    }
                    fetchEpg(forceUpdate = true)
                }
                EpgRepository.TimeChangeResult.CLOCK_CHANGED -> {
                    Timber.i("System clock changed (action=$action); refreshing current program cache")
                    appendDebugMessage(DebugMessage("EPG: System clock changed, refreshing current programs"))
                    withContext(Dispatchers.Default) {
                        epgRepository.refreshCurrentProgramsCache()
                    }
                    _viewState.update {
                        it.copy(
                            currentProgramsMap = epgRepository.getCurrentProgramsSnapshot()
                        )
                    }
                    _viewState.value.currentChannel?.let { channel ->
                        updateCurrentProgram(channel)
                    }
                }
                EpgRepository.TimeChangeResult.NONE -> {
                    Timber.d("Ignoring system time change broadcast (action=$action, trigger=$trigger)")
                }
            }
        }
    }

    private fun mapTimeChangeTrigger(action: String?): EpgRepository.TimeChangeTrigger {
        return when (action) {
            Intent.ACTION_TIMEZONE_CHANGED -> EpgRepository.TimeChangeTrigger.TIMEZONE
            Intent.ACTION_TIME_CHANGED -> EpgRepository.TimeChangeTrigger.TIME_SET
            Intent.ACTION_DATE_CHANGED -> EpgRepository.TimeChangeTrigger.DATE
            else -> EpgRepository.TimeChangeTrigger.UNKNOWN
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
                    isTimeshiftPlayback = false,
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val epgUrl = preferencesRepository.epgUrl.first().ifBlank { "" }
                if (epgUrl.isBlank()) {
                    appendDebugMessage(DebugMessage("EPG: URL not configured"))
                    return@launch
                }

                val channel = _viewState.value.channels.firstOrNull { it.tvgId == tvgId }
                val nowZoned = java.time.ZonedDateTime.now()
                val todayStart = nowZoned.toLocalDate().atStartOfDay(nowZoned.zone)
                val todayEnd = nowZoned.toLocalDate().atTime(java.time.LocalTime.of(23, 59, 59)).atZone(nowZoned.zone)

                val programs = epgRepository.getWindowedProgramsForChannel(
                    epgUrl = epgUrl,
                    tvgId = tvgId,
                    fromUtcMillis = todayStart.toInstant().toEpochMilli(),
                    toUtcMillis = todayEnd.toInstant().toEpochMilli()
                )
                val current = programs.firstOrNull { it.isCurrent() }

                withContext(Dispatchers.Main) {
                    _viewState.update { state ->
                        val updatedMap = state.currentProgramsMap.toMutableMap()
                        updatedMap[tvgId] = current
                        state.copy(
                            currentProgramsMap = updatedMap,
                            epgLoadedFromUtc = todayStart.toInstant().toEpochMilli(),
                            epgLoadedToUtc = todayEnd.toInstant().toEpochMilli()
                        )
                    }
                    appendDebugMessage(
                        DebugMessage("EPG: Showing ${programs.size} programs for $tvgId${current?.let { " (current: ${it.title})" } ?: ""}")
                    )
                    _viewState.update {
                        it.copy(
                            showEpgPanel = true,
                            epgChannelTvgId = tvgId,
                            epgPrograms = programs,
                            currentProgram = current
                        )
                    }
                    postEpgNotification(EPG_LOADED_MESSAGE)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load EPG for channel $tvgId")
                appendDebugMessage(DebugMessage("EPG: Failed to load for $tvgId - ${e.message}"))
            }
        }
    }

    fun loadMoreEpgPast() {
        viewModelScope.launch(Dispatchers.IO) {
            val tvgId = _viewState.value.epgChannelTvgId.ifBlank { return@launch }
            val epgUrl = preferencesRepository.epgUrl.first().ifBlank { return@launch }
            val pastDays = preferencesRepository.epgDaysPast.first().coerceAtLeast(0)
            val stepDays = preferencesRepository.epgPageDays.first().coerceAtLeast(1)

            val zone = java.time.ZonedDateTime.now().zone
            val globalFrom = java.time.ZonedDateTime.now()
                .toLocalDate()
                .minusDays(pastDays.toLong())
                .atStartOfDay(zone)
                .toInstant().toEpochMilli()

            val currentFrom = _viewState.value.epgLoadedFromUtc
            if (currentFrom <= globalFrom) return@launch

            val newFromZoned = java.time.Instant.ofEpochMilli(currentFrom)
                .atZone(zone)
                .toLocalDate()
                .minusDays(stepDays.toLong())
                .atStartOfDay(zone)
            val newFrom = maxOf(globalFrom, newFromZoned.toInstant().toEpochMilli())
            val newTo = currentFrom - 1

            val added = epgRepository.getWindowedProgramsForChannel(epgUrl, tvgId, newFrom, newTo)

            withContext(Dispatchers.Main) {
                if (added.isEmpty()) return@withContext
                val merged = mergePrograms(_viewState.value.epgPrograms, added)
                _viewState.update {
                    it.copy(
                        epgPrograms = merged,
                        epgLoadedFromUtc = newFrom
                    )
                }
            }
        }
    }

    fun loadMoreEpgFuture() {
        viewModelScope.launch(Dispatchers.IO) {
            val tvgId = _viewState.value.epgChannelTvgId.ifBlank { return@launch }
            val epgUrl = preferencesRepository.epgUrl.first().ifBlank { return@launch }
            val daysAhead = preferencesRepository.epgDaysAhead.first().coerceAtLeast(0)
            val stepDays = preferencesRepository.epgPageDays.first().coerceAtLeast(1)

            val zone = java.time.ZonedDateTime.now().zone
            val globalTo = java.time.ZonedDateTime.now()
                .toLocalDate()
                .plusDays(daysAhead.toLong())
                .atTime(java.time.LocalTime.of(23, 59, 59))
                .atZone(zone)
                .toInstant().toEpochMilli()

            val currentTo = _viewState.value.epgLoadedToUtc
            if (currentTo >= globalTo) return@launch

            val nextDayStart = java.time.Instant.ofEpochMilli(currentTo)
                .atZone(zone)
                .toLocalDate()
                .plusDays(stepDays.toLong())
                .atStartOfDay(zone)
                .toInstant().toEpochMilli()
            val newFrom = nextDayStart
            val newTo = globalTo.coerceAtMost(
                java.time.Instant.ofEpochMilli(currentTo)
                    .atZone(zone)
                    .toLocalDate()
                    .plusDays(stepDays.toLong())
                    .atTime(java.time.LocalTime.of(23, 59, 59))
                    .atZone(zone)
                    .toInstant().toEpochMilli()
            )

            val added = epgRepository.getWindowedProgramsForChannel(epgUrl, tvgId, newFrom, newTo)

            withContext(Dispatchers.Main) {
                if (added.isEmpty()) return@withContext
                val merged = mergePrograms(_viewState.value.epgPrograms, added)
                _viewState.update {
                    it.copy(
                        epgPrograms = merged,
                        epgLoadedToUtc = maxOf(it.epgLoadedToUtc, newTo)
                    )
                }
            }
        }
    }

    private fun mergePrograms(existing: List<EpgProgram>, added: List<EpgProgram>): List<EpgProgram> {
        if (added.isEmpty()) return existing
        val map = LinkedHashMap<String, EpgProgram>()
        fun key(p: EpgProgram): String = if (p.id.isNotBlank()) p.id else "${p.startTimeMillis}:${p.title}"
        existing.forEach { map[key(it)] = it }
        added.forEach { map[key(it)] = it }
        return map.values.sortedBy { it.startTimeMillis }
    }

    fun returnToLive() {
        viewModelScope.launch {
            playerManager.returnToLive()
            _viewState.update {
                it.copy(
                    isArchivePlayback = false,
                    isTimeshiftPlayback = false,
                    archiveProgram = null,
                    archivePrompt = null
                )
            }
        }
    }

    /**
     * Watch current program from beginning (timeshift/restart)
     * Allows users to restart the currently airing program
     */
    fun watchFromBeginning() {
        viewModelScope.launch {
            val state = _viewState.value
            val currentChannel = state.currentChannel
            val currentProgram = state.currentProgram

            if (currentChannel == null || currentProgram == null) {
                appendDebugMessage(DebugMessage("DVR: No current program to restart"))
                return@launch
            }

            // Use WatchFromBeginningUseCase for validation
            when (val result = watchFromBeginningUseCase(currentChannel, currentProgram)) {
                is Result.Success -> {
                    val info = result.data
                    appendDebugMessage(DebugMessage("DVR: Restarting ${currentProgram.title} from beginning"))
                    startArchivePlayback(info.channel, info.program)
                }
                is Result.Error -> {
                    appendDebugMessage(DebugMessage("DVR: ${result.message}"))
                    Timber.w("Timeshift validation failed: ${result.message}")
                }
                is Result.Loading -> {
                    // Should not happen
                }
            }
        }
    }

    fun restartCurrentPlayback() {
        if (_viewState.value.isArchivePlayback) {
            playerManager.restartArchive()
        } else {
            watchFromBeginning()
        }
    }

    fun seekBackTenSeconds() {
        val wasArchive = _viewState.value.isArchivePlayback
        if (playerManager.seekBy(-Constants.SEEK_INCREMENT_MS)) {
            if (!wasArchive) {
                _viewState.update { it.copy(isTimeshiftPlayback = true) }
            }
        }
    }

    fun seekForwardTenSeconds() {
        val wasArchive = _viewState.value.isArchivePlayback
        if (playerManager.seekBy(Constants.SEEK_INCREMENT_MS)) {
            if (!wasArchive) {
                _viewState.update { it.copy(isTimeshiftPlayback = true) }
            }
        }
    }

    fun pausePlayback() {
        playerManager.pause()
        if (!_viewState.value.isArchivePlayback) {
            _viewState.update { it.copy(isTimeshiftPlayback = true) }
        }
    }

    fun resumePlayback() {
        playerManager.resume()
        if (!_viewState.value.isArchivePlayback) {
            _viewState.update { it.copy(isTimeshiftPlayback = true) }
        }
    }

    private suspend fun startArchivePlayback(channel: Channel, program: EpgProgram) {
        val durationMinutes = ((program.stopTimeMillis - program.startTimeMillis) / 60000L).coerceAtLeast(1)
        val ageMinutes = ((System.currentTimeMillis() - program.startTimeMillis) / 60000L).coerceAtLeast(0)
        appendDebugMessage(
            DebugMessage(
                "DVR: Request ${channel.title} • ${program.title} (start=${program.startTime}, duration=${durationMinutes}m, age=${ageMinutes}m, template=${channel.catchupSource.ifBlank { "<default>" }})"
            )
        )
        val started = playerManager.playArchive(channel, program)
        if (!started) return
        val channelIndex = _viewState.value.channels.indexOfFirst { it.url == channel.url }.coerceAtLeast(0)

        _viewState.update {
            it.copy(
                isArchivePlayback = true,
                isTimeshiftPlayback = false,
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

            // Use PlayArchiveProgramUseCase for validation
            when (val result = playArchiveProgramUseCase(channel, program)) {
                is Result.Success -> {
                    val info = result.data
                    startArchivePlayback(info.channel, info.program)
                }
                is Result.Error -> {
                    appendDebugMessage(DebugMessage("DVR: ${result.message}"))
                    Timber.w("Archive playback validation failed: ${result.message}")
                }
                is Result.Loading -> {
                    // Should not happen
                }
            }
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
     * Safely retrieves current program from EPG and updates view state
     */
    private fun updateCurrentProgram(channel: Channel) {
        if (!channel.hasEpg || channel.tvgId.isBlank()) {
            _viewState.update {
                it.copy(currentProgram = null)
            }
            return
        }

        try {
            val program = epgRepository.getCurrentProgram(channel.tvgId)
            _viewState.update {
                val updatedMap = it.currentProgramsMap.toMutableMap()
                updatedMap[channel.tvgId] = program
                it.copy(
                    currentProgram = program,
                    currentProgramsMap = updatedMap
                )
            }
            Timber.d("Current program updated for ${channel.title}: ${program?.title ?: "none"}")
        } catch (e: Exception) {
            Timber.e(e, "Error updating current program for ${channel.title}")
            _viewState.update {
                it.copy(currentProgram = null)
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
                isTimeshiftPlayback = false,
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

    // Rotation control removed (button UI is disabled/faded)

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

    private companion object {
        const val EPG_LOADED_MESSAGE = "EPG loaded"
    }
}







