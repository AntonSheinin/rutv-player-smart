@file:Suppress("unused")
@file:SuppressLint("NewApi")

package com.rutv.presentation.main

import android.annotation.SuppressLint
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.data.model.PlaylistSource
import com.rutv.data.repository.ChannelRepository
import com.rutv.data.repository.EpgRepository
import com.rutv.data.repository.PreferencesRepository
import com.rutv.domain.usecase.FilterChannelsUseCase
import com.rutv.domain.usecase.FetchEpgProgramsUseCase
import com.rutv.domain.usecase.LoadPlaylistUseCase
import com.rutv.domain.usecase.PlayArchiveProgramUseCase
import com.rutv.domain.usecase.ToggleFavoriteUseCase
import com.rutv.domain.usecase.UpdateAspectRatioUseCase
import com.rutv.domain.usecase.WatchFromBeginningUseCase
import com.rutv.presentation.main.usecase.InitializeAppUseCase
import com.rutv.presentation.main.usecase.InitializePlayerUseCase
import com.rutv.presentation.player.DebugMessage
import com.rutv.presentation.player.PlayerManager
import com.rutv.presentation.player.PlayerState
import com.rutv.util.PlayerConstants
import com.rutv.util.Result
import com.rutv.util.StringFormatter
import com.rutv.util.logDebug
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@UnstableApi
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
 * - EPG is cached in two layers:
 *   - repository cache inside [EpgRepository] (window + per-channel caches)
 *   - in-ViewModel map [epgProgramCache] for quick panel open without waiting for IO
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
    private val initializeAppUseCase: InitializeAppUseCase,
    private val initializePlayerUseCase: InitializePlayerUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val updateAspectRatioUseCase: UpdateAspectRatioUseCase,
    private val playArchiveProgramUseCase: PlayArchiveProgramUseCase,
    private val watchFromBeginningUseCase: WatchFromBeginningUseCase,
    private val filterChannelsUseCase: FilterChannelsUseCase
) : ViewModel() {

    private val _viewState = MutableStateFlow(MainViewState())
    val viewState: StateFlow<MainViewState> = _viewState.asStateFlow()

    // We keep a bounded list for the on-screen debug overlay. Writes are mutex-protected because
    // messages can come from multiple coroutines (player + network + UI actions).
    private val debugMessageList = mutableListOf<DebugMessage>()
    private val debugMessageMutex = Mutex()
    // Simple in-memory cache: tvgId -> program list currently shown/known.
    // This is *in addition* to EpgRepository caches and exists mostly for UX responsiveness.
    private val epgProgramCache = mutableMapOf<String, List<EpgProgram>>()
    private val epgCacheLock = Any()
    private val playlistLoadRequestId = AtomicLong(0)

    private fun postEpgNotification() {
        if (_viewState.value.epgNotificationMessage == EPG_LOADED_MESSAGE) return
        _viewState.update { it.copy(epgNotificationMessage = EPG_LOADED_MESSAGE) }
    }

    private fun postNotificationMessage(message: String) {
        _viewState.update { it.copy(epgNotificationMessage = message) }
    }

    private fun nextPlaylistLoadId(): Long = playlistLoadRequestId.incrementAndGet()

    private fun isLatestPlaylistLoad(loadId: Long): Boolean = playlistLoadRequestId.get() == loadId

    private fun getCachedPrograms(tvgId: String): List<EpgProgram>? =
        synchronized(epgCacheLock) { epgProgramCache[tvgId] }

    private fun setCachedPrograms(tvgId: String, programs: List<EpgProgram>) {
        synchronized(epgCacheLock) { epgProgramCache[tvgId] = programs }
    }

    private fun clearCachedPrograms() {
        synchronized(epgCacheLock) { epgProgramCache.clear() }
    }

    private fun updateEpgPanelState(
        tvgId: String,
        programs: List<EpgProgram>,
        currentProgram: EpgProgram?
    ) {
        _viewState.update {
            it.copy(
                showEpgPanel = true,
                epgChannelTvgId = tvgId,
                epgPrograms = programs,
                currentProgram = currentProgram
            )
        }
    }

    fun clearEpgNotification() {
        _viewState.update { it.copy(epgNotificationMessage = null) }
    }

    init {
        // --- Player state collection ---
        // The player is the source of truth for what is currently playing.
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
                        ensureChannelVisibility(state.index)
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
                            val archiveIndex = _viewState.value.channels.indexOfFirst { it.url == state.channel.url }
                            ensureChannelVisibility(archiveIndex)
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

        val debugEnabledFlow = preferencesRepository.playerConfig
            .map { it.showDebugLog }
            .distinctUntilChanged()

        // Playlist performance toggle: whether to populate per-channel "current program" cache
        // for list items. When disabled we keep `currentProgramsMap` empty to reduce recompositions.
        viewModelScope.launch {
            preferencesRepository.showCurrentProgramInChannelList
                .distinctUntilChanged()
                .collect { enabled ->
                    _viewState.update { current ->
                        val clearedMap = if (enabled) current.currentProgramsMap else emptyMap()
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
                }
        }

        // Reflect the toggle in view state, and clear accumulated debug messages when disabled.
        viewModelScope.launch {
            debugEnabledFlow.collect { enabled ->
                _viewState.update { it.copy(showDebugLog = enabled) }
                if (!enabled) {
                    debugMessageMutex.withLock {
                        debugMessageList.clear()
                        _viewState.update { it.copy(debugMessages = emptyList()) }
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

        // Collect playlist source
        viewModelScope.launch {
            preferencesRepository.playlistSource.collect { source ->
                _viewState.update { it.copy(playlistSource = source) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.epgDaysPast.collect { days ->
                _viewState.update { it.copy(epgDaysPast = days.coerceAtLeast(0)) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.epgDaysAhead.collect { days ->
                _viewState.update { it.copy(epgDaysAhead = days.coerceAtLeast(0)) }
            }
        }

        // Initialize app: load playlist and initialize the player.
        // EPG is fetched lazily; we may preload it for the start channel after initialization.
        initializeApp()
    }

    /**
     * Initialize app in proper order: EPG cache -> Playlist -> Player
     * This prevents race conditions and ensures EPG is ready
     */
    private fun initializeApp() {
        val loadId = nextPlaylistLoadId()
        viewModelScope.launch(Dispatchers.IO) {
            loadPlaylistAndPlayer(loadId)
        }
    }

    private fun observeFilteredChannels() {
        viewModelScope.launch {
            combine(
                _viewState.map { it.channels }.distinctUntilChanged(),
                _viewState.map { it.showFavoritesOnly }.distinctUntilChanged()
            ) { channels, showFavoritesOnly ->
                filterChannelsUseCase(channels, showFavoritesOnly)
            }
                .flowOn(Dispatchers.Default)
                .collect { filtered ->
                    updateFilteredChannels(filtered)
                }
        }
    }

    private fun updateFilteredChannels(filtered: List<Channel>) {
        val visibleCount = filtered.size.coerceAtMost(DEFAULT_VISIBLE_CHANNELS)
        _viewState.update { current ->
            if (current.filteredChannels === filtered && current.visibleChannelCount == visibleCount) {
                current
            } else {
                current.copy(
                    filteredChannels = filtered,
                    visibleChannelCount = visibleCount
                )
            }
        }
        val currentChannelUrl = _viewState.value.currentChannel?.url
        if (!currentChannelUrl.isNullOrBlank()) {
            val playingIndex = filtered.indexOfFirst { it.url == currentChannelUrl }
            ensureChannelVisibility(playingIndex)
        }
    }

    fun requestMoreChannels(targetIndex: Int) {
        ensureChannelVisibility(targetIndex)
    }

    private suspend fun loadPlaylistAndPlayer(loadId: Long) {
        try {
            if (!isLatestPlaylistLoad(loadId)) return
            _viewState.update { it.copy(isLoading = true, error = null) }
            // Cold start optimization is encapsulated in InitializeAppUseCase.
            val result = initializeAppUseCase.loadStartupPlaylist()
            if (!isLatestPlaylistLoad(loadId)) return

            when (result) {
                is Result.Success -> {
                    val source = result.data.source
                    val channels = result.data.channels

                    // Update state first so UI can render quickly (and show playlist / channel title).
                    _viewState.update {
                        it.copy(
                            channels = channels,
                            isLoading = false,
                            error = null
                        )
                    }
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
                        // Initializes PlayerManager using persisted PlayerConfig + last played index.
                        // The use case returns the selected start channel for optional EPG preload.
                        val startChannel = initializePlayerUseCase(channels)

                        startChannel?.let { preloadChannelEpg(it) }
                        val startIndex = startChannel?.let { ch ->
                            channels.indexOf(ch).takeIf { idx -> idx >= 0 }
                        } ?: 0
                        ensureChannelVisibility(startIndex)
                    }

                    // Background refresh for URL playlists to get fresh content without blocking cold start.
                    if (source is PlaylistSource.Url) {
                        viewModelScope.launch(Dispatchers.IO) {
                            when (val refreshed = initializeAppUseCase.refreshUrlPlaylistInBackground()) {
                                is Result.Success -> {
                                    if (!isLatestPlaylistLoad(loadId)) return@launch
                                    val newChannels = refreshed.data
                                    val oldChannels = _viewState.value.channels
                                    if (newChannels.isNotEmpty() && newChannels != oldChannels) {
                                        // Try to preserve the currently playing channel by URL when replacing the list.
                                        val currentUrl = _viewState.value.currentChannel?.url
                                        val newIndex = currentUrl?.let { url ->
                                            newChannels.indexOfFirst { it.url == url }
                                        } ?: -1
                                        _viewState.update { current ->
                                            current.copy(
                                                channels = newChannels,
                                                currentChannelIndex = if (newIndex >= 0) newIndex else current.currentChannelIndex
                                            )
                                        }
                                    }
                                }
                                else -> Unit // ignore background refresh errors on startup
                            }
                        }
                    }
                }
                is Result.Error -> {
                    if (!isLatestPlaylistLoad(loadId)) return
                    Timber.e(result.exception, "App Init: Failed to load playlist")
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
                is Result.Loading -> {
                    // Should not happen
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

                        val programsMapToUse = if (forceReload) {
                            clearCachedPrograms()
                            epgRepository.clearCache()
                            emptyMap()
                        } else {
                            _viewState.value.currentProgramsMap
                        }

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
                            val startChannel = initializePlayerUseCase(channels)

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
                            preloadChannelEpg(channelForPreload)
                            val preloadIndex = channels.indexOf(channelForPreload).takeIf { it >= 0 } ?: 0
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
                    is Result.Loading -> {
                        // Should not happen
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
     * Initialize player with current channels (moved to [InitializePlayerUseCase]).
     */
    // (intentionally removed)

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
                    clearCachedPrograms()
                    _viewState.update {
                        it.copy(
                            currentProgram = null,
                            currentProgramsMap = emptyMap(),
                            epgPrograms = emptyList(),
                            epgLoadedFromUtc = 0L,
                            epgLoadedToUtc = 0L,
                            epgLoadedTimestamp = 0L
                        )
                    }
                    _viewState.value.currentChannel?.let { preloadChannelEpg(it) }
                }
                EpgRepository.TimeChangeResult.CLOCK_CHANGED -> {
                    Timber.i("System clock changed (action=$action); refreshing current program cache")
                    appendDebugMessage(DebugMessage(StringFormatter.formatEpgClockChanged()))
                    epgRepository.clearCache()
                    clearCachedPrograms()
                    _viewState.update {
                        it.copy(
                            currentProgramsMap = emptyMap(),
                            epgPrograms = emptyList(),
                            epgLoadedFromUtc = 0L,
                            epgLoadedToUtc = 0L,
                            epgLoadedTimestamp = 0L
                        )
                    }
                    _viewState.value.currentChannel?.let { preloadChannelEpg(it) }
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
     * Handles filtered lists (favorites) by mapping index to main list.
     */
    fun playChannel(index: Int) {
        viewModelScope.launch {
            // Map index from current filtered list to actual channel
            val currentState = _viewState.value
            val channelList = if (currentState.showFavoritesOnly) currentState.filteredChannels else currentState.channels

            if (index in channelList.indices) {
                val channel = channelList[index]
                // Find true index in main channel list
                val mainIndex = currentState.channels.indexOf(channel)

                if (mainIndex >= 0) {
                    playerManager.playChannel(mainIndex)

                    // Save last played index
                    preferencesRepository.saveLastPlayedIndex(mainIndex)

                    // Hide playlist and EPG
                    _viewState.update {
                        it.copy(
                            showPlaylist = false,
                            showEpgPanel = false,
                            isArchivePlayback = false,
                            isTimeshiftPlayback = false,
                            archiveProgram = null,
                            currentChannelIndex = mainIndex,
                            currentChannel = channel
                        )
                    }
                }
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
                    val newStatus = result.data
                    val currentChannels = _viewState.value.channels
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
                                channels = updatedChannels,
                                currentChannel = updatedCurrent ?: current.currentChannel
                            )
                        }
                    } else {
                        // Fallback to full reload if the channel isn't in memory (unexpected).
                        val reloaded = channelRepository.getAllChannels()
                        if (reloaded is Result.Success) {
                            _viewState.update { it.copy(channels = reloaded.data) }
                        }
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
     * Open playlist explicitly with optional favorites filter
     */
    fun openPlaylist(showFavoritesOnly: Boolean = false) {
        _viewState.update { current ->
            current.copy(
                showPlaylist = true,
                showFavoritesOnly = showFavoritesOnly,
                showEpgPanel = false,
                selectedProgramDetails = null
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
        _viewState.update { current ->
            current.copy(
                showPlaylist = false,
                showEpgPanel = false,
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
                selectedProgramDetails = null
            )
        }
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
                setCachedPrograms(channel.tvgId, programs)
                postEpgNotification()
            }

            _viewState.update { state ->
                val shouldUpdateCurrent = state.currentChannel?.tvgId == channel.tvgId
                val updatedMap = if (state.showCurrentProgramInChannelList) {
                    state.currentProgramsMap.toMutableMap().apply {
                        this[channel.tvgId] = currentProgram
                    }
                } else {
                    state.currentProgramsMap
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
     * Show EPG for channel
     */
    fun showEpgForChannel(tvgId: String) {
        // UI responsiveness: open panel immediately using cached programs if we have them,
        // then refresh in the background (IO) and replace the list.
        getCachedPrograms(tvgId)?.let { cachedPrograms ->
            val cachedCurrent = cachedPrograms.firstOrNull { it.isCurrent() }
            updateEpgPanelState(tvgId, cachedPrograms, cachedCurrent)
        } ?: run {
            _viewState.update {
                it.copy(
                    showEpgPanel = true,
                    epgChannelTvgId = tvgId,
                    epgPrograms = emptyList(),
                    currentProgram = null
                )
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = fetchEpgProgramsUseCase(
                    tvgId = tvgId,
                    mode = com.rutv.domain.usecase.ComputeEpgWindowUseCase.Mode.Today
                )
                if (result is Result.Error) {
                    // Preserve existing UX: if URL isn't configured, show a friendly debug message and return.
                    appendDebugMessage(DebugMessage(StringFormatter.formatEpgUrlNotConfigured()))
                    return@launch
                }
                if (_viewState.value.epgChannelTvgId != tvgId) return@launch
                val window = (result as Result.Success).data
                val programs = window.programs
                val current = programs.firstOrNull { it.isCurrent() }
                setCachedPrograms(tvgId, programs)

                _viewState.update { state ->
                    val updatedMap = if (state.showCurrentProgramInChannelList) {
                        state.currentProgramsMap.toMutableMap().apply {
                            this[tvgId] = current
                        }
                    } else {
                        state.currentProgramsMap
                    }
                    state.copy(
                        currentProgramsMap = updatedMap,
                        epgLoadedFromUtc = window.fromUtcMillis,
                        epgLoadedToUtc = window.toUtcMillis,
                        showEpgPanel = true,
                        epgChannelTvgId = tvgId,
                        epgPrograms = programs,
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
            }
        }
    }

    fun loadMoreEpgPast() {
        viewModelScope.launch(Dispatchers.IO) {
            val tvgId = _viewState.value.epgChannelTvgId.ifBlank { return@launch }
            val epgUrl = preferencesRepository.epgUrl.first().ifBlank { return@launch }
            val pastDays = preferencesRepository.epgDaysPast.first().coerceAtLeast(0)
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
                    epgPrograms = merged,
                    epgLoadedFromUtc = newFrom
                )
            }
        }
    }

    fun loadMoreEpgFuture() {
        viewModelScope.launch(Dispatchers.IO) {
            val tvgId = _viewState.value.epgChannelTvgId.ifBlank { return@launch }
            val epgUrl = preferencesRepository.epgUrl.first().ifBlank { return@launch }
            val daysAhead = preferencesRepository.epgDaysAhead.first().coerceAtLeast(0)
            val stepDays = preferencesRepository.epgPageDays.first().coerceAtLeast(1)
            val extensionDays = stepDays + 1

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
                    .plusDays(extensionDays.toLong())
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
                    epgPrograms = merged,
                    epgLoadedToUtc = maxOf(it.epgLoadedToUtc, newTo)
                )
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
        val currentChannel = _viewState.value.currentChannel
        if (currentChannel?.url != channel.url) return

        if (!channel.hasEpg || channel.tvgId.isBlank()) {
            _viewState.update {
                it.copy(currentProgram = null)
            }
            return
        }

        try {
            val program = epgRepository.getCurrentProgram(channel.tvgId)
            _viewState.update {
                val updatedMap = if (it.showCurrentProgramInChannelList) {
                    it.currentProgramsMap.toMutableMap().apply {
                        this[channel.tvgId] = program
                    }
                } else {
                    it.currentProgramsMap
                }
                it.copy(currentProgram = program, currentProgramsMap = updatedMap)
            }
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
     * Add a debug message (public wrapper for MainActivity and other components)
     */
    fun logDebug(message: String) {
        viewModelScope.launch {
            appendDebugMessage(DebugMessage(message))
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
                    epgPrograms = merged,
                    epgLoadedFromUtc = newFrom,
                    epgLoadedToUtc = newTo
                )
            }
            setCachedPrograms(tvgId, merged)
        }
    }

    private companion object {
        const val EPG_LOADED_MESSAGE = "EPG loaded"
        private const val CHANNEL_PAGE_SIZE = 60
        private const val CHANNEL_PREFETCH_MARGIN = 8
    }
}
