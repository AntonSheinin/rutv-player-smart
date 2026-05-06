package com.rutv.presentation.main
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rutv.data.model.ResizeMode
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.data.model.PlaylistSource
import com.rutv.domain.repository.ChannelRepository
import com.rutv.domain.repository.EpgRepository
import com.rutv.data.repository.PreferencesRepository
import com.rutv.domain.usecase.filterChannels
import com.rutv.domain.usecase.FetchEpgProgramsUseCase
import com.rutv.domain.usecase.FetchVisibleCurrentProgramsUseCase
import com.rutv.domain.usecase.LoadPlaylistUseCase
import com.rutv.domain.usecase.PlayArchiveProgramUseCase
import com.rutv.domain.usecase.WatchFromBeginningUseCase
import com.rutv.presentation.player.DebugMessage
import com.rutv.presentation.player.PlayerManager
import com.rutv.presentation.player.PlayerState
import com.rutv.util.PlayerConstants
import com.rutv.util.Result
import com.rutv.util.StringFormatter
import com.rutv.util.logDebug
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@HiltViewModel
/**
 * Main screen ViewModel (Compose host).
 *
 * High-level responsibilities:
 * - **Startup orchestration**: load playlist (fast path on cold start), then initialize player.
 * - **Player → UI binding**: translate [PlayerManager.playerState] into [MainViewState] fields.
 * - **EPG orchestration**:
 *   - preloading “good enough” EPG windows for the active channel
 *   - showing today’s window in the EPG panel
 *   - paging more past/future programs on demand
 * - **Remote-first UX helpers**: panel toggles, focus/navigation state, and debug overlay messages.
 *
 * Concurrency model / invariants:
 * - UI state is a single [MutableStateFlow]; updates use `.update { ... }` to stay atomic.
 * - Network/IO work runs on `Dispatchers.IO`; expensive filtering runs on `Dispatchers.Default`.
 * - EPG is cached inside [EpgRepository] (window + per-channel caches)
 *
 * This file is intentionally “fat” because it is the app’s primary coordinator; the “policy”
 * pieces are extracted into `domain/usecase/` where appropriate.
 */
class MainViewModel @Inject constructor(
    private val playerManager: PlayerManager,
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val preferencesRepository: PreferencesRepository,
    private val loadPlaylistUseCase: LoadPlaylistUseCase,
    private val fetchEpgProgramsUseCase: FetchEpgProgramsUseCase,
    private val fetchVisibleCurrentProgramsUseCase: FetchVisibleCurrentProgramsUseCase,
    private val playArchiveProgramUseCase: PlayArchiveProgramUseCase,
    private val watchFromBeginningUseCase: WatchFromBeginningUseCase
) : ViewModel() {

    private val _viewState = MutableStateFlow(MainViewState())
    val viewState: StateFlow<MainViewState> = _viewState.asStateFlow()

    // We keep a bounded list for the on-screen debug overlay. Writes are mutex-protected because
    // messages can come from multiple coroutines (player + network + UI actions).
    private val debugMessageList = mutableListOf<DebugMessage>()
    private val debugMessageMutex = Mutex()
    private val epgPastLoadMutex = Mutex()
    private val epgFutureLoadMutex = Mutex()
    private val playlistLoadRequestId = AtomicLong(0)
    private var epgPanelLoadJob: Job? = null
    private var startupPlayerInitJob: Job? = null
    private val startupInitiated = AtomicBoolean(false)
    private var lastEpgRequestTvgId: String = ""
    private var lastEpgRequestAtMs: Long = 0L
    // tvgIds currently on-screen in the playlist panel; written by the UI (debounced),
    // read by the day-roll / pref-toggle handlers to repopulate `currentProgramsMap`.
    private val visibleChannelTvgIds = MutableStateFlow<List<String>>(emptyList())
    private var visibilityPreloadJob: Job? = null
    // Single-writer (main thread via StateFlow.update) with background readers —
    // @Volatile ensures readers see the latest snapshot without synchronization.
    @Volatile
    private var channelIndexByUrl: Map<String, Int> = emptyMap()
    @Volatile
    private var filteredIndexByUrl: Map<String, Int> = emptyMap()
    private var lastPersistedPlayedIndex: Int = -1
    private val perfStartMs = SystemClock.elapsedRealtime()

    private fun logPerf(mark: String) {
        logDebug { "PERF ${SystemClock.elapsedRealtime() - perfStartMs}ms $mark" }
    }

    private fun postEpgNotification() {
        if (_viewState.value.epgNotificationMessage == EPG_LOADED_MESSAGE) return
        _viewState.update { it.copy(epgNotificationMessage = EPG_LOADED_MESSAGE) }
    }

    private fun postNotificationMessage(message: String) {
        _viewState.update { it.copy(epgNotificationMessage = message) }
    }

    private fun nextPlaylistLoadId(): Long = playlistLoadRequestId.incrementAndGet()

    private fun isLatestPlaylistLoad(loadId: Long): Boolean = playlistLoadRequestId.get() == loadId

    fun onStartupUiReady() {
        if (!startupInitiated.compareAndSet(false, true)) return
        logPerf("startup_ui_ready")
        initializeApp()
    }

    private fun launchStartupPlayerInitialization(loadId: Long, channels: List<Channel>) {
        startupPlayerInitJob?.cancel()
        startupPlayerInitJob = viewModelScope.launch(Dispatchers.IO) {
            if (!isLatestPlaylistLoad(loadId)) return@launch
            if (channels.isEmpty()) return@launch

            delay(STARTUP_PLAYER_INIT_DELAY_MS)
            if (!isLatestPlaylistLoad(loadId)) return@launch
            logPerf("player_init_requested")
            val startChannel = initializePlayer(channels)
            if (!isLatestPlaylistLoad(loadId)) return@launch

            val startIndex = startChannel?.url?.let { url ->
                findMainChannelIndex(url).takeIf { idx -> idx >= 0 }
            } ?: 0
            ensureChannelVisibility(startIndex)

            // EPG preload is useful, but expensive on low-end STBs. Push it out of the
            // "first seconds after startup" window to avoid competing with initial rendering.
            startChannel?.let { channel ->
                viewModelScope.launch(Dispatchers.IO) {
                    waitForStartupPlayerReady(channel.url)
                    delay(STARTUP_EPG_PRELOAD_DELAY_MS)
                    if (!isLatestPlaylistLoad(loadId)) return@launch
                    logPerf("startup_epg_preload_start")
                    preloadChannelEpg(channel)
                }
            }
        }
    }

    private suspend fun waitForStartupPlayerReady(channelUrl: String) {
        withTimeoutOrNull(STARTUP_PLAYER_READY_WAIT_MS) {
            playerManager.playerState
                .filterIsInstance<PlayerState.Ready>()
                .filter { it.channel.url == channelUrl }
                .first()
        }
    }

    private fun buildUrlIndex(channels: List<Channel>): Map<String, Int> {
        if (channels.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Int>(channels.size)
        channels.forEachIndexed { index, channel ->
            if (!result.containsKey(channel.url)) {
                result[channel.url] = index
            }
        }
        return result
    }

    private fun updateChannelIndexMap(channels: List<Channel>) {
        channelIndexByUrl = buildUrlIndex(channels)
    }

    private fun updateFilteredIndexMap(channels: List<Channel>) {
        filteredIndexByUrl = buildUrlIndex(channels)
    }

    private fun findMainChannelIndex(url: String?): Int {
        if (url.isNullOrBlank()) return -1
        return channelIndexByUrl[url] ?: -1
    }

    private fun findFilteredChannelIndex(url: String?): Int {
        if (url.isNullOrBlank()) return -1
        return filteredIndexByUrl[url] ?: -1
    }

    private fun updateEpgPanelState(
        tvgId: String,
        programs: List<EpgProgram>,
        currentProgram: EpgProgram?
    ) {
        _viewState.update {
            it.copy(
                showEpgPanel = true,
                isEpgLoading = false,
                epgChannelTvgId = tvgId,
                epgPrograms = programs.toImmutableList(),
                currentProgram = currentProgram
            )
        }
    }

    fun clearEpgNotification() {
        _viewState.update { it.copy(epgNotificationMessage = null) }
    }

    init {
        // Ensure the singleton PlayerManager has its command processor and preferences
        // collector running for this ViewModel instance.
        playerManager.prepare()

        // Keep startup in loading state until `onStartupUiReady()` kicks off initial load.
        _viewState.update { it.copy(isLoading = true) }

        // --- Player state collection ---
        // The player is the source of truth for what is currently playing.
        viewModelScope.launch {
            playerManager.playerState.collect { state ->
                _viewState.update { it.copy(playerState = state) }

                when (state) {
                    is PlayerState.Ready -> {
                        logPerf("player_ready channel=${state.index}")
                        val filteredIndex = findFilteredChannelIndex(state.channel.url)
                        _viewState.update {
                            val channelChanged = it.currentChannelIndex != state.index ||
                                it.currentChannel?.url != state.channel.url
                            it.copy(
                                currentChannel = state.channel,
                                currentChannelIndex = state.index,
                                currentChannelFilteredIndex = filteredIndex,
                                isArchivePlayback = false,
                                isTimeshiftPlayback = if (channelChanged) false else it.isTimeshiftPlayback,
                                archiveProgram = null,
                                archivePrompt = null
                            )
                        }
                        persistLastPlayedIndexIfNeeded(state.index)
                        ensureChannelVisibility(filteredIndex)
                        // Update current program (will wait if EPG not loaded yet)
                        viewModelScope.launch(Dispatchers.Default) {
                            updateCurrentProgram(state.channel)
                        }
                    }
                    is PlayerState.Archive -> {
                        if (state.endReason == null) {
                            val filteredIndex = findFilteredChannelIndex(state.channel.url)
                            _viewState.update {
                                it.copy(
                                    currentChannel = state.channel,
                                    currentProgram = state.program,
                                    isArchivePlayback = true,
                                    isTimeshiftPlayback = false,
                                    archiveProgram = state.program,
                                    archivePrompt = null,
                                    currentChannelFilteredIndex = filteredIndex
                                )
                            }
                            ensureChannelVisibility(filteredIndex)
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

        val playerConfigFlow = preferencesRepository.playerConfig
            .distinctUntilChanged()

        val debugEnabledFlow = playerConfigFlow
            .map { it.showDebugLog }
            .distinctUntilChanged()

        viewModelScope.launch {
            playerConfigFlow.collect { config ->
                _viewState.update { it.copy(playerConfig = config) }
            }
        }

        // Playlist performance toggle: whether to populate per-channel "current program" cache
        // for list items. When disabled we keep `currentProgramsMap` empty to reduce recompositions.
        viewModelScope.launch {
            preferencesRepository.showCurrentProgramInChannelList
                .distinctUntilChanged()
                .collect { enabled ->
                    val wasEnabled = _viewState.value.showCurrentProgramInChannelList
                    _viewState.update { current ->
                        val clearedMap = if (enabled) current.currentProgramsMap else persistentMapOf()
                        if (current.showCurrentProgramInChannelList == enabled &&
                            current.currentProgramsMap == clearedMap
                        ) {
                            current
                        } else {
                            current.copy(
                                showCurrentProgramInChannelList = enabled,
                                currentProgramsMap = clearedMap
                            )
                        }
                    }
                    if (enabled && !wasEnabled) {
                        visibilityPreloadJob?.cancel()
                        visibilityPreloadJob = viewModelScope.launch(Dispatchers.IO) {
                            refreshVisibleCurrentPrograms()
                        }
                    }
                }
        }

        viewModelScope.launch {
            preferencesRepository.channelPreviewEnabled
                .distinctUntilChanged()
                .collect { enabled ->
                    _viewState.update { it.copy(channelPreviewEnabled = enabled) }
                }
        }

        // Reflect the toggle in view state, and clear accumulated debug messages when disabled.
        viewModelScope.launch {
            debugEnabledFlow.collect { enabled ->
                _viewState.update { it.copy(showDebugLog = enabled) }
                if (!enabled) {
                    debugMessageMutex.withLock {
                        debugMessageList.clear()
                        _viewState.update { it.copy(debugMessages = persistentListOf()) }
                    }
                }
            }
        }

        // Collect debug messages only when enabled (avoid churn and state updates when hidden).
        viewModelScope.launch {
            debugEnabledFlow
                .flatMapLatest { enabled -> if (enabled) playerManager.debugMessages else emptyFlow() }
                .collect { message ->
                    appendDebugMessage(message)
                }
        }

        observeFilteredChannels()

        // Collect preferences that map directly to view state fields.
        viewModelScope.launch {
            combine(
                preferencesRepository.playlistSource,
                preferencesRepository.epgDaysPast,
                preferencesRepository.epgDaysAhead
            ) { source, daysPast, daysAhead ->
                Triple(source, daysPast, daysAhead)
            }.collect { (source, daysPast, daysAhead) ->
                _viewState.update {
                    it.copy(
                        playlistSource = source,
                        epgDaysPast = daysPast.coerceAtLeast(0),
                        epgDaysAhead = daysAhead.coerceAtLeast(0)
                    )
                }
            }
        }

        // Startup optimization:
        // defer initial playlist+player initialization until first UI frame is drawn.
        // See [onStartupUiReady].
    }

    /**
     * Initialize app in proper order: EPG cache -> Playlist -> Player
     * This prevents race conditions and ensures EPG is ready
     */
    private fun initializeApp() {
        logPerf("initialize_app")
        val loadId = nextPlaylistLoadId()
        startupPlayerInitJob?.cancel()
        startupPlayerInitJob = null
        viewModelScope.launch(Dispatchers.IO) {
            loadPlaylistAndPlayer(loadId)
        }
    }

    private suspend fun initializePlayer(channels: List<Channel>): Channel? {
        if (channels.isEmpty()) return null
        return try {
            val config = preferencesRepository.playerConfig.first()
            val lastPlayedIndex = preferencesRepository.lastPlayedIndex.first()
            val startIndex = if (lastPlayedIndex in channels.indices) lastPlayedIndex else 0
            playerManager.initialize(channels, config, startIndex)
            logPerf("player_initialize_submitted channels=${channels.size}")
            channels.getOrNull(startIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            channels.firstOrNull()
        }
    }

    private fun observeFilteredChannels() {
        viewModelScope.launch {
            combine(
                _viewState.map { it.channels }.distinctUntilChanged(),
                _viewState.map { it.showFavoritesOnly }.distinctUntilChanged(),
                _viewState.map { it.selectedGroup }.distinctUntilChanged()
            ) { channels, showFavoritesOnly, selectedGroup ->
                filterChannels(channels, showFavoritesOnly, selectedGroup)
            }
                .flowOn(Dispatchers.Default)
                .collect { filtered ->
                    updateFilteredChannels(filtered)
                }
        }
    }

    private fun updateFilteredChannels(filtered: List<Channel>) {
        val visibleCount = filtered.size.coerceAtMost(DEFAULT_VISIBLE_CHANNELS)
        val currentChannelUrl = _viewState.value.currentChannel?.url
        updateFilteredIndexMap(filtered)
        val playingIndex = findFilteredChannelIndex(currentChannelUrl)
        _viewState.update { current ->
            if (current.filteredChannels === filtered && current.visibleChannelCount == visibleCount) {
                current
            } else {
                current.copy(
                    filteredChannels = filtered.toImmutableList(),
                    visibleChannelCount = visibleCount,
                    currentChannelFilteredIndex = playingIndex
                )
            }
        }
        ensureChannelVisibility(playingIndex)
    }

    private fun persistLastPlayedIndexIfNeeded(index: Int) {
        if (index < 0) return
        if (index == lastPersistedPlayedIndex) return
        lastPersistedPlayedIndex = index
        viewModelScope.launch(Dispatchers.IO) {
            preferencesRepository.saveLastPlayedIndex(index)
        }
    }

    fun requestMoreChannels(targetIndex: Int) {
        ensureChannelVisibility(targetIndex)
    }

    private suspend fun loadPlaylistAndPlayer(loadId: Long) {
        try {
            if (!isLatestPlaylistLoad(loadId)) return
            _viewState.update { it.copy(isLoading = true, error = null) }
            // Cold start: use cached channels for URL playlists to avoid blocking on network.
            val source = preferencesRepository.playlistSource.first()
            val playlistResult = when (source) {
                is PlaylistSource.Url -> loadPlaylistUseCase(skipNetworkIfCacheAvailable = true)
                else -> loadPlaylistUseCase()
            }
            if (!isLatestPlaylistLoad(loadId)) return

            when (playlistResult) {
                is Result.Success -> {
                    val channels = playlistResult.data
                    logPerf("playlist_loaded channels=${channels.size}")

                    // Update state first so UI can render quickly (and show playlist / channel title).
                    _viewState.update {
                        it.copy(
                            channels = channels.toImmutableList(),
                            isLoading = false,
                            error = null
                        )
                    }
                    updateChannelIndexMap(channels)
                    if (!isLatestPlaylistLoad(loadId)) return
                    if (channels.isNotEmpty()) {
                        val catchupSupported = channels.count { it.supportsCatchup() }
                        appendDebugMessage(
                            DebugMessage(StringFormatter.formatEpgPlaylistLoaded(channels.size, catchupSupported.toString()))
                        )
                    } else {
                        appendDebugMessage(DebugMessage(StringFormatter.formatEpgPlaylistEmpty()))
                    }


                    if (channels.isNotEmpty()) {
                        // Startup optimization: initialize player only after the first UI frame.
                        // This reduces heavy main-thread work during Activity first draw.
                        launchStartupPlayerInitialization(loadId, channels)
                    }

                    // Background refresh for URL playlists to get fresh content without blocking cold start.
                    if (source is PlaylistSource.Url) {
                        viewModelScope.launch(Dispatchers.IO) {
                            delay(STARTUP_URL_REFRESH_DELAY_MS)
                            if (!isLatestPlaylistLoad(loadId)) return@launch
                            when (val refreshed = loadPlaylistUseCase()) {
                                is Result.Success -> {
                                    if (!isLatestPlaylistLoad(loadId)) return@launch
                                    val newChannels = refreshed.data
                                    val oldChannels = _viewState.value.channels
                                    if (newChannels.isNotEmpty() && newChannels != oldChannels) {
                                        val newIndexByUrl = buildUrlIndex(newChannels)
                                        // Preserve currently playing channel by URL, fallback to previous index.
                                        val currentState = _viewState.value
                                        val currentUrl = currentState.currentChannel?.url
                                        val newIndex = currentUrl?.let { url -> newIndexByUrl[url] ?: -1 } ?: -1
                                        val fallbackIndex = currentState.currentChannelIndex
                                            .takeIf { it in newChannels.indices }
                                            ?: 0
                                        val resolvedIndex = if (newIndex >= 0) newIndex else fallbackIndex
                                        val resolvedChannel = currentUrl?.let { url ->
                                            newChannels.firstOrNull { it.url == url }
                                        } ?: newChannels.getOrNull(resolvedIndex)

                                        _viewState.update { current ->
                                            current.copy(
                                                channels = newChannels.toImmutableList(),
                                                currentChannelIndex = resolvedIndex,
                                                currentChannel = resolvedChannel ?: current.currentChannel
                                            )
                                        }
                                        updateChannelIndexMap(newChannels)
                                        playerManager.refreshChannels(newChannels, resolvedIndex)
                                    }
                                }
                                else -> Unit // ignore background refresh errors on startup
                            }
                        }
                    }
                }
                is Result.Error -> {
                    if (!isLatestPlaylistLoad(loadId)) return
                    Timber.e(playlistResult.exception, "App Init: Failed to load playlist")
                    val errorMessage = playlistResult.message ?: StringFormatter.formatErrorFailedLoadPlaylist()
                    appendDebugMessage(
                        DebugMessage(StringFormatter.formatEpgPlaylistFailed(errorMessage))
                    )
                    // Post notification message (toast)
                    postNotificationMessage(errorMessage)
                    _viewState.update {
                        it.copy(
                            isLoading = false,
                            error = errorMessage
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "App Init: Error during initialization")
            _viewState.update {
                it.copy(
                    isLoading = false,
                    error = StringFormatter.formatErrorInitFailed(e.message ?: StringFormatter.formatErrorUnknown())
                )
            }
        }
    }


    /**
     * Load playlist from saved source
     * Called when user returns from settings or manually reloads
     */
    fun loadPlaylist(forceReload: Boolean = false) {
        val loadId = nextPlaylistLoadId()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!isLatestPlaylistLoad(loadId)) return@launch
                val wasArchivePlayback = _viewState.value.isArchivePlayback
                val archiveProgramToResume = _viewState.value.archiveProgram
                val archiveChannelUrl = _viewState.value.currentChannel?.url
                val archiveChannelTvgId = _viewState.value.currentChannel?.tvgId

                _viewState.update { it.copy(isLoading = true, error = null) }

                val result = if (forceReload) {
                    loadPlaylistUseCase.reload()
                } else {
                    loadPlaylistUseCase()
                }
                if (!isLatestPlaylistLoad(loadId)) return@launch

                when (result) {
                    is Result.Success -> {
                        val channels = result.data
                        logPerf("playlist_reload_loaded channels=${channels.size}")

                        val programsMapToUse = if (forceReload) {
                            epgRepository.clearCache()
                            emptyMap()
                        } else {
                            _viewState.value.currentProgramsMap
                        }

                        _viewState.update {
                            val updatedCurrentProgram = it.currentChannel?.tvgId?.let(programsMapToUse::get)
                            it.copy(
                                channels = channels.toImmutableList(),
                                currentProgramsMap = programsMapToUse.toImmutableMap(),
                                currentProgram = updatedCurrentProgram ?: it.currentProgram,
                                isLoading = false,
                                error = null
                            )
                        }
                        updateChannelIndexMap(channels)
                        if (programsMapToUse.isNotEmpty()) {
                            postEpgNotification()
                        }

                        if (channels.isNotEmpty()) {
                            val catchupSupported = channels.count { it.supportsCatchup() }
                            appendDebugMessage(
                                DebugMessage("DVR: Playlist loaded (${channels.size} channels, catch-up: $catchupSupported)")
                            )

                        } else {
                            logDebug { "No channels loaded" }
                            appendDebugMessage(DebugMessage(StringFormatter.formatEpgPlaylistEmpty()))
                        }
                        if (!isLatestPlaylistLoad(loadId)) return@launch
                        if (channels.isNotEmpty()) {
                            val startChannel = initializePlayer(channels)

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

                            val channelForPreload = resumeChannel ?: startChannel ?: channels.first()
                            delay(ACTIVE_EPG_REFRESH_DELAY_MS)
                            preloadChannelEpg(channelForPreload)
                            val preloadIndex = findMainChannelIndex(channelForPreload.url).takeIf { it >= 0 } ?: 0
                            ensureChannelVisibility(preloadIndex)
                        }
                    }
                    is Result.Error -> {
                        Timber.e(result.exception, "Error loading playlist")
                        val errorMessage = result.message ?: StringFormatter.formatErrorFailedLoadPlaylist()
                        appendDebugMessage(
                            DebugMessage(StringFormatter.formatEpgPlaylistFailed(errorMessage))
                        )
                        // Post notification message (toast)
                        postNotificationMessage(errorMessage)
                        _viewState.update {
                            it.copy(
                                isLoading = false,
                                error = errorMessage
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error in loadPlaylist")
                val errorMessage = "Failed to load playlist: ${e.message}"
                // Post notification message (toast)
                postNotificationMessage(errorMessage)
                _viewState.update {
                    it.copy(
                        isLoading = false,
                        error = errorMessage
                    )
                }
            }
        }
    }

    /**
     * Fetch EPG data only if needed (not more than once per day)
     */

    fun onSystemTimeOrTimezoneChanged(action: String?) {
        viewModelScope.launch {
            val trigger = mapTimeChangeTrigger(action)
            val result = withContext(Dispatchers.Default) {
                epgRepository.handleSystemTimeOrTimezoneChange(trigger)
            }

            when (result) {
                EpgRepository.TimeChangeResult.TIMEZONE_CHANGED -> {
                    Timber.i("System timezone change detected (action=$action); clearing EPG cache")
                    appendDebugMessage(DebugMessage(StringFormatter.formatEpgTimezoneChanged()))
                    epgRepository.clearCache()
                    _viewState.update {
                        it.copy(
                            currentProgram = null,
                            currentProgramsMap = persistentMapOf(),
                            epgPrograms = persistentListOf(),
                            epgLoadedFromUtc = 0L,
                            epgLoadedToUtc = 0L
                        )
                    }
                    _viewState.value.currentChannel?.let { preloadChannelEpg(it) }
                    refreshVisibleCurrentPrograms()
                }
                EpgRepository.TimeChangeResult.CLOCK_CHANGED -> {
                    Timber.i("System clock changed (action=$action); refreshing current program cache")
                    appendDebugMessage(DebugMessage(StringFormatter.formatEpgClockChanged()))
                    epgRepository.clearCache()
                    _viewState.update {
                        it.copy(
                            currentProgramsMap = persistentMapOf(),
                            epgPrograms = persistentListOf(),
                            epgLoadedFromUtc = 0L,
                            epgLoadedToUtc = 0L
                        )
                    }
                    _viewState.value.currentChannel?.let { preloadChannelEpg(it) }
                    refreshVisibleCurrentPrograms()
                }
                EpgRepository.TimeChangeResult.NONE -> {
                    logDebug { "Ignoring system time change broadcast (action=$action, trigger=$trigger)" }
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
     * Play channel at index.
     * Index is resolved against the current filtered list (favorites/group).
     */
    fun playChannel(index: Int) {
        viewModelScope.launch {
            val currentState = _viewState.value
            val channelList = currentState.filteredChannels
            if (index !in channelList.indices) return@launch

            val channel = channelList[index]
            val mainIndex = resolveMainIndex(channel, currentState.channels)
            if (mainIndex < 0) return@launch

            playChannelInternal(mainIndex)
        }
    }

    fun switchChannelRelative(delta: Int) {
        if (delta == 0) return
        viewModelScope.launch {
            val currentState = _viewState.value
            val channelList = currentState.filteredChannels
            if (channelList.isEmpty()) return@launch

            val currentIndex = currentState.currentChannelFilteredIndex
                .takeIf { it in channelList.indices }
                ?: currentState.currentChannel
                    ?.let { channel -> channelList.indexOfFirst { it == channel }.takeIf { idx -> idx >= 0 } }
                ?: 0

            val normalized = ((currentIndex + delta) % channelList.size + channelList.size) % channelList.size
            val channel = channelList[normalized]
            val mainIndex = resolveMainIndex(channel, currentState.channels)
            if (mainIndex < 0) return@launch

            playChannelInternal(mainIndex)
        }
    }

    private fun resolveMainIndex(channel: Channel, allChannels: List<Channel>): Int {
        val exactIndex = allChannels.indexOfFirst { it == channel }
        if (exactIndex >= 0) return exactIndex
        val positionIndex = allChannels.indexOfFirst {
            it.position == channel.position && it.url == channel.url && it.title == channel.title
        }
        if (positionIndex >= 0) return positionIndex
        return findMainChannelIndex(channel.url)
    }

    private fun playChannelInternal(mainIndex: Int) {
        playerManager.setAutoRetrySuppressed(false)
        playerManager.playChannel(mainIndex)

        // Hide playlist and EPG
        _viewState.update {
            it.copy(
                showPlaylist = false,
                showEpgPanel = false,
                isEpgLoading = false,
                isArchivePlayback = false,
                isTimeshiftPlayback = false,
                archiveProgram = null
            )
        }
    }

    /**
     * Toggle favorite for channel
     */
    fun toggleFavorite(channelUrl: String) {
        viewModelScope.launch {
            when (val result = channelRepository.toggleFavorite(channelUrl)) {
                is Result.Success -> {
                    val newStatus = result.data
                    val currentChannels = _viewState.value.channels
                    val channelToUpdate = currentChannels.firstOrNull { it.url == channelUrl }
                    val updatedChannels = currentChannels.map { channel ->
                        if (channel.url == channelUrl) channel.copy(isFavorite = newStatus) else channel
                    }
                    val didUpdate = currentChannels.any { it.url == channelUrl }
                    if (didUpdate) {
                        _viewState.update { current ->
                            val updatedCurrent = current.currentChannel?.let { ch ->
                                if (ch.url == channelUrl) ch.copy(isFavorite = newStatus) else ch
                            }
                            current.copy(
                                channels = updatedChannels.toImmutableList(),
                                currentChannel = updatedCurrent ?: current.currentChannel
                            )
                        }
                        updateChannelIndexMap(updatedChannels)
                        preferencesRepository.updateFavorite(channelUrl, channelToUpdate?.tvgId, newStatus)
                    } else {
                        // Fallback to full reload if the channel isn't in memory (unexpected).
                        val reloaded = channelRepository.getAllChannels()
                        if (reloaded is Result.Success) {
                            _viewState.update { it.copy(channels = reloaded.data.toImmutableList()) }
                            updateChannelIndexMap(reloaded.data)
                            val reloadedChannel = reloaded.data.firstOrNull { it.url == channelUrl }
                            preferencesRepository.updateFavorite(channelUrl, reloadedChannel?.tvgId, newStatus)
                        }
                    }
                }
                is Result.Error -> {
                    Timber.e(result.exception, "Error toggling favorite")
                }
            }
        }
    }

    /**
     * Toggle playlist visibility
     */
    fun togglePlaylist() {
        val showPlaylist = !_viewState.value.showPlaylist
        if (showPlaylist) {
            playerManager.cancelAutoRetry()
        }
        playerManager.setAutoRetrySuppressed(showPlaylist)
        _viewState.update { current ->
            current.copy(
                showPlaylist = showPlaylist,
                showFavoritesOnly = false,
                showEpgPanel = false,
                isEpgLoading = false,
                selectedProgramDetails = if (showPlaylist) current.selectedProgramDetails else null
            )
        }
    }

    /**
     * Open playlist explicitly with optional favorites filter
     */
    fun openPlaylist(showFavoritesOnly: Boolean = false) {
        playerManager.cancelAutoRetry()
        playerManager.setAutoRetrySuppressed(true)
        _viewState.update { current ->
            current.copy(
                showPlaylist = true,
                showFavoritesOnly = showFavoritesOnly,
                showEpgPanel = false,
                isEpgLoading = false,
                selectedProgramDetails = null
            )
        }
    }

    /**
     * Toggle favorites view
     */
    fun toggleFavorites() {
        val showPlaylist = !_viewState.value.showPlaylist
        if (showPlaylist) {
            playerManager.cancelAutoRetry()
        }
        playerManager.setAutoRetrySuppressed(showPlaylist)
        _viewState.update {
            it.copy(
                showPlaylist = showPlaylist,
                showFavoritesOnly = true,
                showEpgPanel = false,
                isEpgLoading = false
            )
        }
    }

    private fun updatedCurrentProgramsMap(
        current: ImmutableMap<String, EpgProgram?>,
        tvgId: String,
        program: EpgProgram?
    ): ImmutableMap<String, EpgProgram?> {
        if (current.containsKey(tvgId) && current[tvgId] == program) {
            return current
        }
        return current.toMutableMap()
            .apply { this[tvgId] = program }
            .toImmutableMap()
    }

    fun setChannelGroupFilter(group: String?) {
        val normalized = group?.trim().takeIf { !it.isNullOrBlank() }
        _viewState.update { current ->
            if (current.selectedGroup == normalized) {
                current
            } else {
                current.copy(selectedGroup = normalized)
            }
        }
    }

    fun resetChannelGroupFilter() {
        setChannelGroupFilter(null)
    }

    fun updatePlaylistScrollIndex(index: Int) {
        val normalized = index.coerceAtLeast(0)
        _viewState.update { current ->
            if (current.lastPlaylistScrollIndex == normalized) current else current.copy(lastPlaylistScrollIndex = normalized)
        }
    }

    /**
     * Close playlist
     */
    fun closePlaylist() {
        playerManager.setAutoRetrySuppressed(false)
        _viewState.update { current ->
            current.copy(
                showPlaylist = false,
                showEpgPanel = false,
                isEpgLoading = false,
                selectedProgramDetails = null
            )
        }
    }

    /**
     * Close EPG panel only (keep playlist open)
     */
    fun closeEpgPanel() {
        _viewState.update { current ->
            current.copy(
                showEpgPanel = false,
                isEpgLoading = false,
                selectedProgramDetails = null
            )
        }
    }

    fun setControlsVisible(visible: Boolean) {
        _viewState.update { it.copy(areControlsVisible = visible) }
    }

    fun setShowCloseAppDialog(show: Boolean) {
        _viewState.update { it.copy(showCloseAppDialog = show) }
    }

    private suspend fun preloadChannelEpg(channel: Channel) {
        if (!channel.hasEpg || channel.tvgId.isBlank()) {
            return
        }

        try {
            // “PreferredForChannel” window is typically larger than “Today” because:
            // - current-program detection works better with some past buffer
            // - catch-up features require past programs to exist
            val result = fetchEpgProgramsUseCase(
                tvgId = channel.tvgId,
                mode = com.rutv.domain.usecase.ComputeEpgWindowUseCase.Mode.PreferredForChannel,
                channel = channel
            )
            if (result is Result.Error) {
                // Preserve previous behavior: just skip silently (with a warning) if EPG URL is not configured.
                if (result.exception is IllegalStateException) {
                    Timber.w("Skipping EPG preload for ${channel.title}: EPG URL not configured")
                    return
                }
                throw result.exception
            }
            val window = (result as Result.Success).data
            val programs = window.programs
            val currentProgram = programs.firstOrNull { it.isCurrent() }
            if (programs.isNotEmpty()) {
                postEpgNotification()
            }

            _viewState.update { state ->
                val shouldUpdateCurrent = state.currentChannel?.tvgId == channel.tvgId
                val updatedMap = if (state.showCurrentProgramInChannelList) {
                    updatedCurrentProgramsMap(state.currentProgramsMap, channel.tvgId, currentProgram)
                } else {
                    state.currentProgramsMap
                }
                if (updatedMap === state.currentProgramsMap &&
                    (!shouldUpdateCurrent || currentProgram == null || state.currentProgram == currentProgram)
                ) {
                    return@update state
                }
                state.copy(
                    currentProgramsMap = updatedMap,
                    currentProgram = if (shouldUpdateCurrent) currentProgram ?: state.currentProgram else state.currentProgram
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to preload EPG for ${channel.title}")
            appendDebugMessage(
                DebugMessage(
                    StringFormatter.formatEpgLoadFailed(
                        channel.tvgId,
                        e.message ?: StringFormatter.formatErrorUnknown()
                    )
                )
            )
        }
    }

    /**
     * Called by the playlist panel (debounced) with the tvgIds currently visible on-screen.
     * Batches a single EPG fetch for channels that don't yet have a cached "now playing" entry,
     * and merges the results into [MainViewState.currentProgramsMap].
     *
     * The active channel is excluded because [preloadChannelEpg] already drives its preload
     * with a richer (past + future) window.
     */
    fun onVisibleChannelsChanged(tvgIds: List<String>) {
        if (visibleChannelTvgIds.value == tvgIds) return
        visibleChannelTvgIds.value = tvgIds
        if (!_viewState.value.showCurrentProgramInChannelList) return
        visibilityPreloadJob?.cancel()
        visibilityPreloadJob = viewModelScope.launch(Dispatchers.IO) {
            delay(VISIBLE_PROGRAM_REFRESH_DELAY_MS)
            refreshVisibleCurrentPrograms()
        }
    }

    private suspend fun refreshVisibleCurrentPrograms() {
        val tvgIds = visibleChannelTvgIds.value
        if (tvgIds.isEmpty()) return
        val state = _viewState.value
        if (!state.showCurrentProgramInChannelList) return

        val channelsByTvgId = state.channels.associateBy { it.tvgId }
        val activeTvgId = state.currentChannel?.tvgId
        val alreadyLoaded = state.currentProgramsMap
        val now = System.currentTimeMillis()

        val toFetch = tvgIds.filter { tvgId ->
            tvgId.isNotBlank() &&
                tvgId != activeTvgId &&
                channelsByTvgId[tvgId]?.hasEpg == true &&
                // Fetch unless we already have a program that still covers "now".
                // Either no entry, a null entry, or an ended program → re-fetch.
                alreadyLoaded[tvgId]?.isCurrent(now) != true
        }.distinct()

        if (toFetch.isEmpty()) return

        val result = fetchVisibleCurrentProgramsUseCase(tvgIds = toFetch, nowUtcMillis = now)
        if (result is Result.Error) {
            Timber.w(result.exception, "Failed to preload visible current programs")
            return
        }
        val current = (result as Result.Success).data
        val nonNullCount = current.count { it.value != null }
        logDebug {
            "refreshVisibleCurrentPrograms: fetched ${toFetch.size}, got ${current.size} entries, $nonNullCount current"
        }
        if (current.isEmpty()) return

        _viewState.update { s ->
            if (!s.showCurrentProgramInChannelList) return@update s
            var changed = false
            val merged = s.currentProgramsMap.toMutableMap()
            current.forEach { (tvgId, program) ->
                if (!merged.containsKey(tvgId) || merged[tvgId] != program) {
                    merged[tvgId] = program
                    changed = true
                }
            }
            if (!changed) return@update s
            s.copy(currentProgramsMap = merged.toImmutableMap())
        }
    }

    /**
     * Show EPG for channel
     */
    fun showEpgForChannel(tvgId: String) {
        val now = System.currentTimeMillis()
        if (tvgId == lastEpgRequestTvgId && (now - lastEpgRequestAtMs) < 1200L) {
            return
        }
        lastEpgRequestTvgId = tvgId
        lastEpgRequestAtMs = now

        if (_viewState.value.epgChannelTvgId == tvgId && epgPanelLoadJob?.isActive == true) {
            return
        }
        epgPanelLoadJob?.cancel()
        epgPanelLoadJob = viewModelScope.launch(Dispatchers.IO) {
            // UI responsiveness: open panel immediately using repository-cached programs if available,
            // then refresh in the background (IO) and replace the list.
            val cachedPrograms = epgRepository.getProgramsForChannel(tvgId)
            if (cachedPrograms.isNotEmpty()) {
                val cachedCurrent = cachedPrograms.firstOrNull { it.isCurrent() }
                val state = _viewState.value
                val shouldUpdatePanel =
                    !state.showEpgPanel ||
                    state.epgChannelTvgId != tvgId ||
                    (state.epgPrograms.isEmpty() && cachedPrograms.isNotEmpty())
                if (shouldUpdatePanel) {
                    updateEpgPanelState(tvgId, cachedPrograms, cachedCurrent)
                    _viewState.update { state ->
                        if (state.epgChannelTvgId == tvgId) state.copy(isEpgLoading = false) else state
                    }
                }
            } else {
                _viewState.update { state ->
                    if (state.showEpgPanel && state.epgChannelTvgId == tvgId && state.epgPrograms.isEmpty()) {
                        state.copy(isEpgLoading = true)
                    } else {
                        state.copy(
                            showEpgPanel = true,
                            isEpgLoading = true,
                            epgChannelTvgId = tvgId,
                            epgPrograms = persistentListOf(),
                            epgLoadedFromUtc = 0L,
                            epgLoadedToUtc = 0L,
                            currentProgram = null
                        )
                    }
                }
            }
            try {
                val result = fetchEpgProgramsUseCase(
                    tvgId = tvgId,
                    mode = com.rutv.domain.usecase.ComputeEpgWindowUseCase.Mode.Today
                )
                if (result is Result.Error) {
                    // Preserve existing UX: if URL isn't configured, show a friendly debug message and return.
                    appendDebugMessage(DebugMessage(StringFormatter.formatEpgUrlNotConfigured()))
                    _viewState.update { state ->
                        if (state.epgChannelTvgId == tvgId) state.copy(isEpgLoading = false) else state
                    }
                    return@launch
                }
                if (_viewState.value.epgChannelTvgId != tvgId) return@launch
                val window = (result as Result.Success).data
                val programs = window.programs
                val current = programs.firstOrNull { it.isCurrent() }

                _viewState.update { state ->
                    val updatedMap = if (state.showCurrentProgramInChannelList) {
                        updatedCurrentProgramsMap(state.currentProgramsMap, tvgId, current)
                    } else {
                        state.currentProgramsMap
                    }
                    if (updatedMap === state.currentProgramsMap &&
                        state.epgLoadedFromUtc == window.fromUtcMillis &&
                        state.epgLoadedToUtc == window.toUtcMillis &&
                        state.showEpgPanel &&
                        !state.isEpgLoading &&
                        state.epgChannelTvgId == tvgId &&
                        state.epgPrograms == programs &&
                        state.currentProgram == current
                    ) {
                        return@update state
                    }
                    state.copy(
                        currentProgramsMap = updatedMap,
                        epgLoadedFromUtc = window.fromUtcMillis,
                        epgLoadedToUtc = window.toUtcMillis,
                        showEpgPanel = true,
                        isEpgLoading = false,
                        epgChannelTvgId = tvgId,
                        epgPrograms = programs.toImmutableList(),
                        currentProgram = current
                    )
                }
                appendDebugMessage(
                    DebugMessage(StringFormatter.formatEpgShowingPrograms(programs.size, tvgId, current?.title))
                )
                postEpgNotification()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to load EPG for channel $tvgId")
                appendDebugMessage(DebugMessage(StringFormatter.formatEpgLoadFailed(tvgId, e.message ?: StringFormatter.formatErrorUnknown())))
                _viewState.update { state ->
                    if (state.epgChannelTvgId == tvgId) state.copy(isEpgLoading = false) else state
                }
            } finally {
                if (epgPanelLoadJob == this.coroutineContext[Job]) {
                    epgPanelLoadJob = null
                }
            }
        }
    }

    fun loadMoreEpgPast() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!epgPastLoadMutex.tryLock()) return@launch
            try {
                val tvgId = _viewState.value.epgChannelTvgId.ifBlank { return@launch }
                val epgUrl = preferencesRepository.epgUrl.first().ifBlank { return@launch }
                val prefPastDays = preferencesRepository.epgDaysPast.first().coerceAtLeast(0)
                val channelCatchupDays = _viewState.value.channels
                    .firstOrNull { it.tvgId == tvgId }
                    ?.catchupDays
                    ?.coerceAtLeast(0)
                    ?: 0
                val pastDays = maxOf(prefPastDays, channelCatchupDays)
                val stepDays = preferencesRepository.epgPageDays.first().coerceAtLeast(1)
                val extensionDays = stepDays + 1

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
                    .minusDays(extensionDays.toLong())
                    .atStartOfDay(zone)
                val newFrom = maxOf(globalFrom, newFromZoned.toInstant().toEpochMilli())
                val newTo = currentFrom - 1

                val added = epgRepository.getWindowedProgramsForChannel(epgUrl, tvgId, newFrom, newTo)

                if (added.isEmpty()) return@launch
                if (_viewState.value.epgChannelTvgId != tvgId) return@launch
                // Merge with stable sort + de-dupe to avoid duplicates when windows overlap by a day.
                val merged = mergePrograms(_viewState.value.epgPrograms, added)
                _viewState.update {
                    it.copy(
                        epgPrograms = merged.toImmutableList(),
                        epgLoadedFromUtc = newFrom
                    )
                }
            } finally {
                epgPastLoadMutex.unlock()
            }
        }
    }

    fun loadMoreEpgFuture() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!epgFutureLoadMutex.tryLock()) return@launch
            try {
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
                if (currentTo <= 0L) return@launch
                if (currentTo >= globalTo) return@launch

                val nextDayStart = java.time.Instant.ofEpochMilli(currentTo)
                    .atZone(zone)
                    .toLocalDate()
                    .plusDays(1L)
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

                if (added.isEmpty()) return@launch
                if (_viewState.value.epgChannelTvgId != tvgId) return@launch
                // Merge with stable sort + de-dupe to avoid duplicates when windows overlap by a day.
                val merged = mergePrograms(_viewState.value.epgPrograms, added)
                _viewState.update {
                    it.copy(
                        epgPrograms = merged.toImmutableList(),
                        epgLoadedToUtc = maxOf(it.epgLoadedToUtc, newTo)
                    )
                }
            } finally {
                epgFutureLoadMutex.unlock()
            }
        }
    }

    private fun mergePrograms(existing: List<EpgProgram>, added: List<EpgProgram>): List<EpgProgram> {
        if (added.isEmpty()) return existing
        val map = LinkedHashMap<String, EpgProgram>()
        fun key(p: EpgProgram): String = p.id.ifBlank { "${p.startTimeMillis}:${p.title}" }
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
                appendDebugMessage(DebugMessage(StringFormatter.formatDvrNoCurrentProgram()))
                return@launch
            }

            // Use WatchFromBeginningUseCase for validation
            when (val result = watchFromBeginningUseCase(currentChannel, currentProgram)) {
                is Result.Success -> {
                    val info = result.data
                    appendDebugMessage(DebugMessage(StringFormatter.formatDvrRestarting(currentProgram.title)))
                    startArchivePlayback(info.channel, info.program)
                }
                is Result.Error -> {
                    appendDebugMessage(DebugMessage(StringFormatter.formatDvrValidationFailed(result.message ?: StringFormatter.formatErrorUnknown())))
                    Timber.w("Timeshift validation failed: ${result.message}")
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
        if (playerManager.seekBy(-PlayerConstants.SEEK_INCREMENT_MS)) {
            if (!wasArchive) {
                _viewState.update { it.copy(isTimeshiftPlayback = true) }
            }
        }
    }

    fun seekForwardTenSeconds() {
        val wasArchive = _viewState.value.isArchivePlayback
        if (playerManager.seekBy(PlayerConstants.SEEK_INCREMENT_MS)) {
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
        // Debug overlay is intentionally verbose here because DVR issues are hard to diagnose remotely.
        appendDebugMessage(
            DebugMessage(
                StringFormatter.formatDvrRequest(
                    channel.title,
                    program.title,
                    program.startTime,
                    durationMinutes.toInt(),
                    ageMinutes.toInt(),
                    channel.catchupSource.ifBlank { "<default>" }
                )
            )
        )
        val started = playerManager.playArchive(channel, program)
        if (!started) return
        val channelIndex = findMainChannelIndex(channel.url).coerceAtLeast(0)
        val filteredIndex = findFilteredChannelIndex(channel.url)

        _viewState.update {
            it.copy(
                isArchivePlayback = true,
                isTimeshiftPlayback = false,
                archiveProgram = program,
                currentChannel = channel,
                currentChannelIndex = channelIndex,
                currentChannelFilteredIndex = filteredIndex,
                currentProgram = program,
                showPlaylist = false,
                showEpgPanel = false,
                isEpgLoading = false,
                archivePrompt = null
            )
        }
    }

    fun playArchiveProgram(program: EpgProgram) {
        viewModelScope.launch {
            val state = _viewState.value
            val channel = state.channels.firstOrNull { it.tvgId == state.epgChannelTvgId }
            if (channel == null) {
                appendDebugMessage(DebugMessage(StringFormatter.formatDvrChannelNotFound(program.title)))
                return@launch
            }

            // Use PlayArchiveProgramUseCase for validation
            when (val result = playArchiveProgramUseCase(channel, program)) {
                is Result.Success -> {
                    val info = result.data
                    startArchivePlayback(info.channel, info.program)
                }
                is Result.Error -> {
                    appendDebugMessage(DebugMessage(StringFormatter.formatDvrValidationFailed(result.message ?: StringFormatter.formatErrorUnknown())))
                    Timber.w("Archive playback validation failed: ${result.message}")
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
     * Update current program for channel.
     * Checks the in-memory cache first; if empty, triggers a server fetch via preloadChannelEpg.
     */
    private suspend fun updateCurrentProgram(channel: Channel) {
        val currentChannel = _viewState.value.currentChannel
        if (currentChannel?.url != channel.url) return

        if (!channel.hasEpg || channel.tvgId.isBlank()) {
            _viewState.update { state ->
                if (state.currentChannel?.url != channel.url) state else state.copy(currentProgram = null)
            }
            return
        }

        try {
            val program = epgRepository.getCurrentProgram(channel.tvgId)
            _viewState.update { state ->
                if (state.currentChannel?.url != channel.url) return@update state
                val updatedMap = if (state.showCurrentProgramInChannelList) {
                    updatedCurrentProgramsMap(state.currentProgramsMap, channel.tvgId, program)
                } else {
                    state.currentProgramsMap
                }
                if (state.currentProgram == program && updatedMap === state.currentProgramsMap) {
                    return@update state
                }
                state.copy(currentProgram = program, currentProgramsMap = updatedMap)
            }
            // Always refresh from the server: the EPG backend updates nightly, so the
            // cached program (if any) may be stale. The repository bypasses its cache
            // for windows overlapping now, and windowInFlight dedupes concurrent calls.
            delay(ACTIVE_EPG_REFRESH_DELAY_MS)
            if (_viewState.value.currentChannel?.url == channel.url) {
                preloadChannelEpg(channel)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating current program for ${channel.title}")
            _viewState.update { state ->
                if (state.currentChannel?.url != channel.url) state else state.copy(currentProgram = null)
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
        if (!_viewState.value.showDebugLog) return
        debugMessageMutex.withLock {
            debugMessageList.add(message)
            while (debugMessageList.size > 200) {
                debugMessageList.removeAt(0)
            }
            _viewState.update { it.copy(debugMessages = debugMessageList.toImmutableList()) }
        }
    }

    /**
     * Cycle aspect ratio
     */
    fun cycleAspectRatio() {
        viewModelScope.launch {
            val newMode = _viewState.value.currentResizeMode.next()

            _viewState.update { it.copy(currentResizeMode = newMode) }

            // Save to repository
            _viewState.value.currentChannel?.let { channel ->
                channelRepository.updateAspectRatio(channel.url, newMode.intValue)
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

    private fun ensureChannelVisibility(targetIndex: Int) {
        if (targetIndex < 0) return
        val filtered = _viewState.value.filteredChannels
        if (targetIndex >= filtered.size) return
        val currentVisible = _viewState.value.visibleChannelCount
        val desiredVisible = (targetIndex + 1 + CHANNEL_PREFETCH_MARGIN).coerceAtMost(filtered.size)
        if (desiredVisible <= currentVisible) return
        val newVisible = (((desiredVisible + CHANNEL_PAGE_SIZE - 1) / CHANNEL_PAGE_SIZE) * CHANNEL_PAGE_SIZE)
            .coerceAtMost(filtered.size)
        if (newVisible != currentVisible) {
            _viewState.update { it.copy(visibleChannelCount = newVisible) }
        }
    }

    fun ensureEpgForDateRange(startUtcMillis: Long, endUtcMillis: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val tvgId = _viewState.value.epgChannelTvgId.ifBlank { return@launch }
            val epgUrl = preferencesRepository.epgUrl.first().ifBlank { return@launch }

            val currentFrom = _viewState.value.epgLoadedFromUtc
            val currentTo = _viewState.value.epgLoadedToUtc
            val alreadyCovered = currentFrom != 0L && currentTo != 0L &&
                startUtcMillis >= currentFrom && endUtcMillis <= currentTo
            if (alreadyCovered) return@launch

            val programs = epgRepository.getWindowedProgramsForChannel(
                epgUrl = epgUrl,
                tvgId = tvgId,
                fromUtcMillis = startUtcMillis,
                toUtcMillis = endUtcMillis
            )
            if (_viewState.value.epgChannelTvgId != tvgId) return@launch

            val existing = _viewState.value
            val newFrom = when {
                existing.epgLoadedFromUtc == 0L -> startUtcMillis
                existing.epgLoadedFromUtc == Long.MAX_VALUE -> startUtcMillis
                else -> minOf(existing.epgLoadedFromUtc, startUtcMillis)
            }
            val newTo = when {
                existing.epgLoadedToUtc == 0L -> endUtcMillis
                else -> maxOf(existing.epgLoadedToUtc, endUtcMillis)
            }
            if (programs.isEmpty()) {
                _viewState.update {
                    it.copy(
                        epgLoadedFromUtc = newFrom,
                        epgLoadedToUtc = newTo
                    )
                }
                return@launch
            }
            val merged = mergePrograms(existing.epgPrograms, programs)
            _viewState.update {
                it.copy(
                    epgPrograms = merged.toImmutableList(),
                    epgLoadedFromUtc = newFrom,
                    epgLoadedToUtc = newTo
                )
            }
        }
    }

    private companion object {
        const val EPG_LOADED_MESSAGE = "EPG loaded"
        private const val STARTUP_PLAYER_INIT_DELAY_MS = 250L
        private const val STARTUP_PLAYER_READY_WAIT_MS = 5000L
        private const val STARTUP_EPG_PRELOAD_DELAY_MS = 3500L
        private const val STARTUP_URL_REFRESH_DELAY_MS = 6000L
        private const val ACTIVE_EPG_REFRESH_DELAY_MS = 1500L
        private const val VISIBLE_PROGRAM_REFRESH_DELAY_MS = 700L
        private const val CHANNEL_PAGE_SIZE = 60
        private const val CHANNEL_PREFETCH_MARGIN = 8
    }
}
