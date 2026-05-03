package com.rutv.presentation.player

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.data.model.PlayerConfig
import com.rutv.util.ArchiveUrlBuilder
import com.rutv.util.PlayerConstants
import com.rutv.data.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel as CommandQueue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.rutv.util.logDebug
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URI
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min
import kotlin.math.max

@UnstableApi
@Singleton
/**
 * Media3/ExoPlayer façade used by the app.
 *
 * Why this exists (instead of using `ExoPlayer` directly from the UI):
 * - **Single owner** of the player instance (lifecycle + release policy).
 * - **Deterministic switching** between two playback modes:
 *   - live playlist: many channels (`setMediaItems(...)`, repeat all)
 *   - archive/catch-up: a single VOD-like item (repeat off)
 * - **Centralized retry/timeout policy** and debug telemetry.
 * - **Threading**: player operations must run on the main thread; network probing runs on IO.
 *
 * State is exposed as:
 * - [playerState] for UI (Ready/Buffering/Error/Archive...)
 * - [debugMessages] for the optional on-screen debug overlay.
 */
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val baseHttpDataSourceFactory: DefaultHttpDataSource.Factory
) {

    private var player: ExoPlayer? = null
    private var playerListener: Player.Listener? = null
    private var analyticsListener: AnalyticsListener? = null
    private var channels: List<Channel> = emptyList()
    private var liveMediaItemsCache: List<MediaItem> = emptyList()

    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _debugMessages = MutableSharedFlow<DebugMessage>(replay = 0, extraBufferCapacity = 50)
    val debugMessages: SharedFlow<DebugMessage> = _debugMessages.asSharedFlow()

    private var bufferingStartTime: Long = 0
    private var bufferingCheckJob: Job? = null

    // Structured scopes (avoid ad-hoc CoroutineScope(...) allocations)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var currentConfig: PlayerConfig? = null
    private var isArchivePlayback: Boolean = false
    private var archiveProgram: EpgProgram? = null
    private var archiveChannel: Channel? = null
    private var lastLiveIndex: Int = 0
    private var pendingArchiveSeek: Boolean = false
    private var archiveProbeJob: Job? = null

    private var attemptedFfmpegAudioFallback: Boolean = false
    private var autoRetryJob: Job? = null
    private var autoRetryTargetIndex: Int = -1
    private var isUserPaused: Boolean = false
    private var autoRetryEnabled: Boolean = true
    private var autoRetryMaxAttempts: Int = PlayerConstants.DEFAULT_AUTO_RETRY_MAX_ATTEMPTS
    private var autoRetryIntervalMs: Long = PlayerConstants.DEFAULT_AUTO_RETRY_PERIOD_SECONDS * 1_000L
    private var autoRetrySuppressed: Boolean = false
    private val playbackCommands = CommandQueue<PlaybackCommand>(capacity = CommandQueue.UNLIMITED)
    private val deferredPlaybackCommands = ArrayDeque<PlaybackCommand>()
    private var playbackCommandProcessorJob: Job? = null
    private var preferencesCollectorJob: Job? = null
    private var liveSwitchRequestSeq: Long = 0L
    private var pendingLiveSwitch: PendingLiveSwitch? = null
    private var unknownSwitchErrorCount: Int = 0
    private var lastLiveSwitchCompletedAtMs: Long = 0L

    /**
     * Start (or restart) the command processor and preferences collector.
     * Idempotent — skips if jobs are already active.
     * Called from MainViewModel.init to ensure the singleton is ready for a new ViewModel instance.
     */
    fun prepare() {
        if (playbackCommandProcessorJob?.isActive != true) {
            playbackCommandProcessorJob = mainScope.launch {
                processPlaybackCommands()
            }
        }
        if (preferencesCollectorJob?.isActive != true) {
            preferencesCollectorJob = mainScope.launch {
                combine(
                    preferencesRepository.autoRetryEnabled,
                    preferencesRepository.autoRetryMaxAttempts,
                    preferencesRepository.autoRetryPeriodSeconds
                ) { enabled, attempts, seconds ->
                    Triple(enabled, attempts, seconds)
                }.collect { (enabled, attempts, seconds) ->
                    autoRetryEnabled = enabled
                    autoRetryMaxAttempts = attempts.coerceIn(
                        PlayerConstants.MIN_AUTO_RETRY_MAX_ATTEMPTS,
                        PlayerConstants.MAX_AUTO_RETRY_MAX_ATTEMPTS
                    )
                    autoRetryIntervalMs = seconds.coerceIn(
                        PlayerConstants.MIN_AUTO_RETRY_PERIOD_SECONDS,
                        PlayerConstants.MAX_AUTO_RETRY_PERIOD_SECONDS
                    ) * 1_000L
                    if (!enabled) {
                        stopAutoRetry()
                        clearRetryingState()
                    }
                }
            }
        }
    }

private fun submitPlaybackCommand(command: PlaybackCommand) {
        if (playbackCommands.trySend(command).isSuccess) return
        mainScope.launch {
            playbackCommands.send(command)
        }
    }

    private suspend fun nextPlaybackCommand(): PlaybackCommand {
        return deferredPlaybackCommands.removeFirstOrNull() ?: playbackCommands.receive()
    }

    private suspend fun processPlaybackCommands() {
        try {
            while (true) {
                when (val command = nextPlaybackCommand()) {
                    is PlaybackCommand.Initialize -> {
                        initializeInternal(command.channels, command.config, command.startIndex, command.mediaItems)
                    }
                    is PlaybackCommand.RefreshChannels -> {
                        handleRefreshChannels(command.channels, command.preferredIndex, command.mediaItems)
                    }
                    is PlaybackCommand.SwitchLive -> {
                        var latestIndex = command.index
                        while (true) {
                            val next = playbackCommands.tryReceive().getOrNull() ?: break
                            if (next is PlaybackCommand.SwitchLive) {
                                latestIndex = next.index
                            } else {
                                deferredPlaybackCommands.addLast(next)
                            }
                        }
                        switchToLiveChannel(latestIndex)
                    }
                    is PlaybackCommand.ReturnToLive -> {
                        returnToLiveInternal()
                    }
                    is PlaybackCommand.PlayArchive -> {
                        val started = runCatching { playArchiveInternal(command.channel, command.program) }
                            .getOrElse {
                                Timber.e(it, "Failed to start archive playback")
                                false
                            }
                        command.result.complete(started)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun markLiveSwitchRequested(targetIndex: Int) {
        liveSwitchRequestSeq += 1L
        pendingLiveSwitch = PendingLiveSwitch(
            requestId = liveSwitchRequestSeq,
            targetIndex = targetIndex
        )
        unknownSwitchErrorCount = 0
    }

    private fun markLiveSwitchCompleted(currentIndex: Int) {
        val pending = pendingLiveSwitch ?: return
        if (pending.targetIndex == currentIndex) {
            pendingLiveSwitch = null
            unknownSwitchErrorCount = 0
            lastLiveSwitchCompletedAtMs = System.currentTimeMillis()
            logDebug { "markLiveSwitchCompleted: target=$currentIndex cleared, grace window starts" }
        }
    }

    private fun clearPendingLiveSwitch() {
        pendingLiveSwitch = null
        unknownSwitchErrorCount = 0
    }

    private fun resolveErrorMediaItemIndex(error: PlaybackException): Int? {
        val exoError = error as? ExoPlaybackException ?: return null
        val periodUid = exoError.mediaPeriodId?.periodUid ?: return null
        val timeline = player?.currentTimeline ?: return null
        if (timeline.isEmpty) return null
        val periodIndex = timeline.getIndexOfPeriod(periodUid)
        return periodIndex.takeIf { it != C.INDEX_UNSET }
    }

    private fun resolveErrorChannelIndex(currentIndex: Int, errorIndex: Int?): Int {
        return when {
            errorIndex != null && errorIndex in channels.indices -> errorIndex
            currentIndex in channels.indices -> currentIndex
            else -> -1
        }
    }

    private fun isStaleLiveError(currentIndex: Int, errorIndex: Int?): Boolean {
        if (isArchivePlayback) return false
        val pending = pendingLiveSwitch
        if (pending != null) {
            // Switch in flight: surface only errors we can positively pin to the target.
            // Unmappable origin (errorIndex == null) is a stale leftover from the
            // previous stream, not a fault of the new channel.
            return errorIndex != pending.targetIndex
        }
        // No pending switch — but a switch we just completed may still produce late
        // stale errors (delayed CDN responses, manifest-refresh failures from the
        // prior stream that won the race against cancellation). Within a short grace
        // window after completion, treat any error that does not pin positively to
        // the current channel (mapped to a different index OR unmappable origin) as
        // stale. Outside the window, behave normally — unmappable errors at that
        // point are renderer/DRM failures worth surfacing.
        val sinceCompletion = System.currentTimeMillis() - lastLiveSwitchCompletedAtMs
        if (sinceCompletion in 0 until SWITCH_COMPLETED_STALE_GRACE_MS) {
            return errorIndex == null || errorIndex != currentIndex
        }
        return errorIndex != null && errorIndex != currentIndex
    }

    private fun shouldRetryUnknownIssue(): Boolean {
        if (pendingLiveSwitch == null) return true
        unknownSwitchErrorCount += 1
        return unknownSwitchErrorCount <= MAX_UNKNOWN_RETRIES_DURING_SWITCH
    }

    private fun isRetryableSourceError(error: PlaybackException): Boolean {
        val cause = error.cause
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            val code = cause.responseCode
            return code in listOf(404, 408, 429, 500, 502, 503, 504)
        }
        val message = error.message?.lowercase(Locale.US).orEmpty()
        val sourceLikeMessage = message.contains("source error") ||
            message.contains("failed to load") ||
            message.contains("load error")
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> true
            else -> sourceLikeMessage
        }
    }

    private fun shouldAutoRetry(error: PlaybackException, issue: PlaybackIssue): Boolean {
        if (issue is PlaybackIssue.Forbidden ||
            issue is PlaybackIssue.TokenNotFound ||
            issue is PlaybackIssue.Suspended
        ) {
            return false
        }
        val issueRetryable = when (issue) {
            is PlaybackIssue.NotFound,
            is PlaybackIssue.HttpError,
            is PlaybackIssue.Network,
            is PlaybackIssue.Timeout -> true
            is PlaybackIssue.Unknown -> shouldRetryUnknownIssue()
            else -> false
        }
        return autoRetryEnabled &&
            autoRetryMaxAttempts > 0 &&
            autoRetryIntervalMs > 0L &&
            !autoRetrySuppressed &&
            (issueRetryable || isRetryableSourceError(error))
    }

    private fun clearRetryingState() {
        val current = _playerState.value
        if (current is PlayerState.Error && current.isRetrying) {
            _playerState.value = current.copy(
                isRetrying = false,
                retryAttempt = 0,
                retryMaxAttempts = 0
            )
        }
    }

    private fun startAutoRetry(targetIndex: Int) {
        if (targetIndex < 0) return
        if (!autoRetryEnabled) return
        if (autoRetryMaxAttempts <= 0) return
        if (autoRetryIntervalMs <= 0L) return
        if (autoRetrySuppressed) return
        if (autoRetryTargetIndex == targetIndex && autoRetryJob?.isActive == true) return
        stopAutoRetry()
        autoRetryTargetIndex = targetIndex
        autoRetryJob = mainScope.launch {
            val self = coroutineContext[Job]
            var attempts = 0
            try {
                while (isActive) {
                    val maxAttempts = autoRetryMaxAttempts.coerceAtLeast(1)
                    if (!autoRetryEnabled || attempts >= maxAttempts) {
                        if (attempts >= maxAttempts) {
                            addDebugMessage("  -> Auto-retry stopped after $attempts attempts")
                        }
                        clearRetryingState()
                        return@launch
                    }
                    delay(autoRetryIntervalMs)
                    val playerInstance = player ?: return@launch
                    if (playerInstance.currentMediaItemIndex != targetIndex) {
                        return@launch
                    }
                    if (isUserPaused) {
                        continue
                    }
                    attempts++
                    val retryingError = _playerState.value as? PlayerState.Error
                    if (retryingError?.isRetrying == true) {
                        _playerState.value = retryingError.copy(
                            retryAttempt = attempts,
                            retryMaxAttempts = maxAttempts
                        )
                    }
                    addDebugMessage("  -> Auto-retry attempt $attempts/$maxAttempts")
                    // For live streams, retry from the live edge instead of reusing the previous
                    // position, which can repeatedly hit stale/missing HLS segments.
                    if (!isArchivePlayback) {
                        playerInstance.seekToDefaultPosition(targetIndex)
                    } else {
                        val pos = playerInstance.currentPosition.takeIf { it >= 0 } ?: C.TIME_UNSET
                        playerInstance.seekTo(targetIndex, pos)
                    }
                    playerInstance.prepare()
                    playerInstance.playWhenReady = true
                }
            } finally {
                if (autoRetryJob === self) {
                    autoRetryJob = null
                    autoRetryTargetIndex = -1
                }
            }
        }
    }

    private fun stopAutoRetry() {
        autoRetryJob?.cancel()
        autoRetryJob = null
        autoRetryTargetIndex = -1
    }

    private fun classifyPlaybackIssue(error: PlaybackException): PlaybackIssue {
        val cause = error.cause
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            val code = cause.responseCode
            val body = runCatching { cause.responseBody?.toString(Charsets.UTF_8) }.getOrNull().orEmpty()
            val text = buildString {
                append(error.message ?: "")
                if (body.isNotBlank()) {
                    append('\n')
                    append(body)
                }
            }.lowercase()

            fun containsAny(vararg needles: String): Boolean = needles.any { text.contains(it) }

            // Provider-specific heuristics (Flussonic and common IPTV middlewares).
            if (containsAny("suspend", "suspended", "disabled by provider")) {
                return PlaybackIssue.Suspended()
            }
            if (containsAny("token not found", "no token", "invalid token", "token expired", "auth token")) {
                return PlaybackIssue.TokenNotFound()
            }

            return when (code) {
                401, 403 -> PlaybackIssue.Forbidden()
                404 -> PlaybackIssue.NotFound()
                else -> PlaybackIssue.HttpError(code)
            }
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                PlaybackIssue.Network(error.message)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT ->
                PlaybackIssue.Timeout(error.message)
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                PlaybackIssue.HttpError(code = -1, message = error.message)
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                PlaybackIssue.NotFound(error.message)
            else -> PlaybackIssue.Unknown(error.message)
        }
    }

    private fun isAudioDecoderError(error: PlaybackException): Boolean {
        val code = error.errorCode
        val isDecoderError = code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
        if (!isDecoderError) return false

        val cause = error.cause
        if (cause is MediaCodecRenderer.DecoderInitializationException) {
            return cause.mimeType?.startsWith("audio/") == true
        }

        val message = error.message?.lowercase(Locale.US).orEmpty()
        return message.contains("audiocoderenderer") || message.contains("audio decoder")
    }

    private fun shouldTryFfmpegAudioFallback(error: PlaybackException): Boolean {
        val config = currentConfig ?: return false
        if (config.useFfmpegAudio || attemptedFfmpegAudioFallback) return false
        return isAudioDecoderError(error)
    }

    private fun attemptFfmpegAudioFallback(): Boolean {
        val config = currentConfig ?: return false
        if (config.useFfmpegAudio) return false

        attemptedFfmpegAudioFallback = true
        val archiveChannelSnapshot = if (isArchivePlayback) archiveChannel else null
        val archiveProgramSnapshot = if (isArchivePlayback) archiveProgram else null
        val startIndex = if (isArchivePlayback) {
            lastLiveIndex
        } else {
            player?.currentMediaItemIndex ?: lastLiveIndex
        }

        addDebugMessage("Audio decoder failed; retrying with FFmpeg audio")

        val mediaItems = buildMediaItems(channels)
        initializeInternal(channels, config.copy(useFfmpegAudio = true), startIndex, mediaItems)

        if (archiveChannelSnapshot != null && archiveProgramSnapshot != null) {
            playArchiveInternal(archiveChannelSnapshot, archiveProgramSnapshot)
        }

        return true
    }

    /**
     * Initialize player with channels
     */
    fun initialize(channels: List<Channel>, config: PlayerConfig, startIndex: Int = 0) {
        if (channels.isEmpty()) {
            Timber.w("Cannot initialize player with empty channel list")
            return
        }

        // We snapshot the list to avoid accidental mutation while we build MediaItems in background.
        // (The app uses immutable Lists in practice, but this keeps the function robust.)
        val channelSnapshot = channels.toList()
        val postInitialize: (List<MediaItem>) -> Unit = { mediaItems ->
            submitPlaybackCommand(
                PlaybackCommand.Initialize(
                    channels = channelSnapshot,
                    config = config,
                    startIndex = startIndex,
                    mediaItems = mediaItems
                )
            )
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            workerScope.launch {
                try {
                    val mediaItems = buildMediaItems(channelSnapshot)
                    postInitialize(mediaItems)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to prepare media items on background thread")
                    mainScope.launch {
                        _playerState.value = PlayerState.Error(PlaybackIssue.Unknown("Failed to prepare media items"), null)
                    }
                }
            }
        } else {
            try {
                val mediaItems = buildMediaItems(channelSnapshot)
                postInitialize(mediaItems)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to prepare media items")
                _playerState.value = PlayerState.Error(PlaybackIssue.Unknown("Failed to prepare media items"), null)
            }
        }
    }

    /**
     * Refresh live channel list while preserving current playback position semantics.
     *
     * - In live mode: rebuilds playlist items so ExoPlayer and UI channel indices stay aligned.
     * - In archive mode: updates cached live channels and last live index, but keeps archive playback.
     */
    fun refreshChannels(channels: List<Channel>, preferredIndex: Int = 0) {
        if (channels.isEmpty()) return
        val channelSnapshot = channels.toList()
        val normalizedIndex = preferredIndex.coerceIn(0, channelSnapshot.lastIndex)
        val postRefresh: (List<MediaItem>) -> Unit = { mediaItems ->
            submitPlaybackCommand(
                PlaybackCommand.RefreshChannels(
                    channels = channelSnapshot,
                    preferredIndex = normalizedIndex,
                    mediaItems = mediaItems
                )
            )
        }

        val canReinitialize = currentConfig != null && !isArchivePlayback
        if (!canReinitialize) {
            postRefresh(emptyList())
            return
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            workerScope.launch {
                try {
                    val mediaItems = buildMediaItems(channelSnapshot)
                    postRefresh(mediaItems)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to refresh media items on background thread")
                    mainScope.launch {
                        _playerState.value = PlayerState.Error(PlaybackIssue.Unknown("Failed to refresh channels"), null)
                    }
                }
            }
        } else {
            try {
                val mediaItems = buildMediaItems(channelSnapshot)
                postRefresh(mediaItems)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh channels")
                _playerState.value = PlayerState.Error(PlaybackIssue.Unknown("Failed to refresh channels"), null)
            }
        }
    }

    private fun initializeInternal(
        channelList: List<Channel>,
        config: PlayerConfig,
        startIndex: Int,
        mediaItems: List<MediaItem>
    ) {
        logDebug { "Initializing player with ${channelList.size} channels, startIndex=$startIndex" }
        addDebugMessage("App Started")

        if (channelList.isEmpty()) {
            Timber.w("Cannot initialize player with empty channel list")
            return
        }

        this.channels = channelList
        this.liveMediaItemsCache = mediaItems
        this.currentConfig = config
        attemptedFfmpegAudioFallback = false
        isUserPaused = false

        releaseInternal()
        createPlayer(config, startIndex, mediaItems)
    }

    private fun handleRefreshChannels(
        channels: List<Channel>,
        preferredIndex: Int,
        mediaItems: List<MediaItem>
    ) {
        if (channels.isEmpty()) return
        val normalizedIndex = preferredIndex.coerceIn(0, channels.lastIndex)
        val config = currentConfig

        if (config == null || isArchivePlayback) {
            this.channels = channels
            this.liveMediaItemsCache = if (mediaItems.isNotEmpty()) mediaItems else buildMediaItems(channels)
            lastLiveIndex = normalizedIndex
            clearPendingLiveSwitch()
            return
        }

        val effectiveItems = if (mediaItems.isNotEmpty()) mediaItems else buildMediaItems(channels)
        initializeInternal(channels, config, normalizedIndex, effectiveItems)
    }

    /**
     * Create ExoPlayer instance
     */
    private fun createPlayer(config: PlayerConfig, startIndex: Int, mediaItems: List<MediaItem>) {
        try {
            addDebugMessage("━━━ PLAYER INITIALIZATION ━━━")

            val modes = mutableListOf<String>()
            if (config.useFfmpegAudio) modes.add("audio")
            if (config.useFfmpegVideo) modes.add("video")
            if (modes.isNotEmpty()) {
                addDebugMessage("✓ NextLib FFmpeg: ${modes.joinToString(", ")} decoder")
            } else {
                addDebugMessage("✓ Hardware decoders only")
            }

            addDebugMessage("✓ Buffer: ${config.bufferSeconds}s")

            // Build renderers factory
            val renderersFactory = if (config.useFfmpegAudio || config.useFfmpegVideo) {
                addDebugMessage("🏭 Factory: FFmpegRenderersFactory")
                FFmpegRenderersFactory(context, config.useFfmpegAudio, config.useFfmpegVideo)
            } else {
                addDebugMessage("🏭 Factory: DefaultRenderersFactory")
                DefaultRenderersFactory(context).apply {
                    setEnableDecoderFallback(true)
                }
            }

            // Calculate buffer durations
            val bufferMs = config.bufferSeconds * 1000
            val minBufferMs = maxOf(PlayerConstants.MIN_BUFFER_MS, bufferMs)
            val maxBufferMs = maxOf(PlayerConstants.MAX_BUFFER_MS, bufferMs)

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    minBufferMs,
                    maxBufferMs,
                    PlayerConstants.BUFFER_FOR_PLAYBACK_MS,
                    PlayerConstants.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val httpDataSourceFactory = baseHttpDataSourceFactory

            val hlsExtractorFactory = DefaultHlsExtractorFactory(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
                true
            )

            val hlsMediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)
                .setExtractorFactory(hlsExtractorFactory)
                .setAllowChunklessPreparation(false)
                .setTimestampAdjusterInitializationTimeoutMs(30000)

            val trackSelector = DefaultTrackSelector(context).apply {
                @Suppress("DEPRECATION")
                parameters = buildUponParameters()
                    .setForceHighestSupportedBitrate(false)
                    .setAllowVideoMixedMimeTypeAdaptiveness(false)
                    .setAllowVideoNonSeamlessAdaptiveness(false)
                    .setAllowAudioMixedMimeTypeAdaptiveness(false)
                    .setAllowAudioMixedSampleRateAdaptiveness(false)
                    .setMaxVideoBitrate(10000000)
                    .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                    .setDisabledTextTrackSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                    .setSelectUndeterminedTextLanguage(false)
                    .build()
            }

            player = ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(hlsMediaSourceFactory)
                .setTrackSelector(trackSelector)
                .setSeekBackIncrementMs(PlayerConstants.SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(PlayerConstants.SEEK_INCREMENT_MS)
                .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                .build()
                .apply {
                    // Set media items
                    setMediaItems(mediaItems)

                    addDebugMessage("📺 Loaded ${mediaItems.size} channels into player")

                    repeatMode = Player.REPEAT_MODE_ALL

                    // Add analytics listener
                    val analytics = createAnalyticsListener()
                    analyticsListener = analytics
                    addAnalyticsListener(analytics)

                    // Add player listener
                    val listener = createPlayerListener()
                    playerListener = listener
                    addListener(listener)

                    // Seek to start index
                    if (startIndex >= 0 && startIndex < channels.size) {
                        seekTo(startIndex, C.TIME_UNSET)
                        addDebugMessage("⏩ Starting from channel #${startIndex + 1}")
                    }

                    prepare()
                    playWhenReady = true
                }

            isArchivePlayback = false
            archiveChannel = null
            archiveProgram = null
            lastLiveIndex = startIndex.coerceIn(0, channels.lastIndex.takeIf { channels.isNotEmpty() } ?: 0)

            addDebugMessage("━━━ PLAYER READY ━━━")

        } catch (e: Exception) {
            Timber.e(e, "Error creating player")
            addDebugMessage("✗ Player init failed: ${e.message}")
            _playerState.value = PlayerState.Error(PlaybackIssue.Unknown(e.message), null)
        }
    }

    /**
     * Create analytics listener for player events
     */
    private fun createAnalyticsListener(): AnalyticsListener {
        return object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long
            ) {
                addDebugMessage("🔊 Audio decoder: $decoderName")
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long
            ) {
                addDebugMessage("🎬 Video decoder: $decoderName")
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                if (droppedFrames > 0) {
                    val fps = if (elapsedMs > 0) (droppedFrames * 1000f / elapsedMs) else 0f
                    addDebugMessage("⚠️ Dropped $droppedFrames frames in ${elapsedMs}ms (${String.format(
                        Locale.US, "%.1f", fps)} fps)")
                }
            }
        }
    }

    private fun fetchVariantPreview(
        factory: DefaultHttpDataSource.Factory,
        uri: Uri,
        program: EpgProgram
    ) {
        val source = factory.createDataSource()
        try {
            val dataSpec = DataSpec.Builder()
                .setUri(uri)
                .setHttpMethod(DataSpec.HTTP_METHOD_GET)
                .build()
            val inputStream = DataSourceInputStream(source, dataSpec)
            inputStream.use { stream ->
                val buffer = ByteArray(2048)
                val builder = StringBuilder()
                var totalRead = 0
                while (totalRead < buffer.size) {
                    val read = stream.read(buffer, 0, buffer.size - totalRead)
                    if (read <= 0) break
                    builder.append(String(buffer, 0, read, StandardCharsets.UTF_8))
                    totalRead += read
                }
                val manifest = builder.toString()
                if (manifest.isNotBlank()) {
                    val lines = manifest.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                    val preview = lines.take(6).joinToString(" | ")
                    if (preview.isNotEmpty()) {
                        addDebugMessage("DVR: Variant ${maskSensitive(preview)}")
                    } else {
                        addDebugMessage("DVR: Variant (only blank lines)")
                    }
                    val programDateLine = lines.firstOrNull { it.startsWith("#EXT-X-PROGRAM-DATE-TIME", ignoreCase = true) }
                    val mediaSequenceLine = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) }
                    programDateLine?.let { line ->
                        val timestamp = line.substringAfter(':', "").trim()
                        val firstSegmentMillis = parseIso8601ToMillis(timestamp)
                        if (firstSegmentMillis != null) {
                            val deltaSeconds = ((firstSegmentMillis - program.startUtcMillis) / 1000.0)
                            addDebugMessage(
                                "DVR: Variant first PDT=${timestamp} (delta=${String.format(Locale.US, "%.1f", deltaSeconds)}s vs EPG start)"
                            )
                        } else {
                            addDebugMessage("DVR: Variant PDT parse failed (${maskSensitive(line)})")
                        }
                    }
                    mediaSequenceLine?.let { line ->
                        addDebugMessage("DVR: Variant $line")
                    }
                } else {
                    addDebugMessage("DVR: Variant manifest empty")
                }
            }
        } catch (e: Exception) {
            addDebugMessage("DVR: Variant fetch failed (${e.message ?: "unknown error"})")
        } finally {
            try {
                source.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }

    /**
     * Create player listener for state changes
     */
    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (isArchivePlayback) return
                val retryingError = _playerState.value as? PlayerState.Error
                if (retryingError?.isRetrying == true) return
                mediaItem?.let {
                    val currentIndex = player?.currentMediaItemIndex ?: return
                    val channel = channels.getOrNull(currentIndex) ?: return
                    logDebug { "Channel transition observed: ${channel.title} (#${currentIndex + 1})" }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
                ) {
                    isUserPaused = !playWhenReady
                    if (!playWhenReady) {
                        stopAutoRetry()
                        val retryingError = _playerState.value as? PlayerState.Error
                        if (retryingError?.isRetrying == true) {
                            _playerState.value = retryingError.copy(
                                isRetrying = false,
                                retryAttempt = 0,
                                retryMaxAttempts = 0
                            )
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            stopAutoRetry()
                            if (isArchivePlayback) {
                                clearPendingLiveSwitch()
                                val channel = archiveChannel
                                val program = archiveProgram
                                if (channel != null && program != null) {
                                    if (pendingArchiveSeek) {
                                    player?.seekTo(0L)
                                    pendingArchiveSeek = false
                                }
                                addDebugMessage("▶ DVR Playing: ${channel.title}")
                                _playerState.value = PlayerState.Archive(channel, program)
                            }
                            stopBufferingCheck()
                            return
                        }
                        val currentIndex = player?.currentMediaItemIndex ?: return
                        val channel = channels.getOrNull(currentIndex)

                        channel?.let {
                            addDebugMessage("▶ Playing: ${it.title}")
                            markLiveSwitchCompleted(currentIndex)
                            _playerState.value = PlayerState.Ready(it, currentIndex)
                        }

                        stopBufferingCheck()
                    }
                    Player.STATE_BUFFERING -> {
                        val retryingError = _playerState.value as? PlayerState.Error
                        if (retryingError?.isRetrying == true) {
                            if (bufferingStartTime == 0L) {
                                bufferingStartTime = System.currentTimeMillis()
                                startBufferingCheck()
                            }
                            return
                        }
                        if (bufferingStartTime == 0L) {
                            bufferingStartTime = System.currentTimeMillis()
                            addDebugMessage("⏳ Buffering...")
                            _playerState.value = PlayerState.Buffering
                            startBufferingCheck()
                        }
                    }
                    Player.STATE_ENDED -> {
                        stopBufferingCheck()
                        if (isArchivePlayback) {
                            val channel = archiveChannel
                            val program = archiveProgram
                            if (channel != null && program != null) {
                                _playerState.value = PlayerState.Archive(channel, program, ArchiveEndReason.COMPLETED)
                            }
                            pendingArchiveSeek = false
                        } else {
                            addDebugMessage("⏹ Playback ended")
                            _playerState.value = PlayerState.Ended
                        }
                    }
                    Player.STATE_IDLE -> {
                        stopBufferingCheck()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentIndex = player?.currentMediaItemIndex ?: -1
                val errorIndex = resolveErrorMediaItemIndex(error)
                val channelIndex = resolveErrorChannelIndex(currentIndex, errorIndex)
                val channel = channels.getOrNull(channelIndex)
                val errorMsg = error.message ?: "Unknown error"
                val pendingSnapshot = pendingLiveSwitch
                val sinceCompletion = System.currentTimeMillis() - lastLiveSwitchCompletedAtMs
                logDebug {
                    "onPlayerError: code=${error.errorCode} msg=$errorMsg currentIdx=$currentIndex " +
                        "errorIdx=${errorIndex?.toString() ?: "null"} " +
                        "pendingTarget=${pendingSnapshot?.targetIndex?.toString() ?: "null"} " +
                        "sinceCompletionMs=$sinceCompletion"
                }
                if (isStaleLiveError(currentIndex, errorIndex)) {
                    logDebug {
                        "  -> SUPPRESSED as stale (current=$currentIndex " +
                            "error=${errorIndex?.toString() ?: "null"} " +
                            "pending=${pendingSnapshot?.targetIndex?.toString() ?: "null"})"
                    }
                    addDebugMessage(
                        "  -> Ignoring stale playback error from an outdated switch request (current=$currentIndex, error=$errorIndex)"
                    )
                    return
                }

                if (shouldTryFfmpegAudioFallback(error)) {
                    if (attemptFfmpegAudioFallback()) {
                        return
                    }
                }

                logDebug { "  -> SURFACING error for channel=${channel?.title ?: "null"}" }
                addDebugMessage("✗ Error: ${channel?.title ?: "Unknown"}")
                addDebugMessage("  → $errorMsg")

                val issue = classifyPlaybackIssue(error)
                val shouldRetry = channelIndex >= 0 && shouldAutoRetry(error, issue)
                if (shouldRetry) {
                    val startingRetry = autoRetryJob?.isActive != true || autoRetryTargetIndex != channelIndex
                    startAutoRetry(channelIndex)
                    if (startingRetry) {
                        val periodSeconds = maxOf(1, (autoRetryIntervalMs / 1000L).toInt())
                        addDebugMessage("  -> Auto-retrying source every ${periodSeconds}s")
                    }
                } else {
                    stopAutoRetry()
                    if (!isArchivePlayback && channelIndex >= 0) {
                        markLiveSwitchCompleted(channelIndex)
                    }
                }
                _playerState.value = PlayerState.Error(
                    issue = issue,
                    channel = channel,
                    isRetrying = shouldRetry,
                    retryAttempt = 0,
                    retryMaxAttempts = if (shouldRetry) autoRetryMaxAttempts.coerceAtLeast(1) else 0
                )

                stopBufferingCheck()

                // Handle live window error
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    addDebugMessage("  → Recovering: Seeking to live edge...")
                    player?.apply {
                        seekToDefaultPosition()
                        prepare()
                        playWhenReady = true
                    }
                }
            }
        }
    }

    /**
     * Play channel at index
     */
    fun playChannel(index: Int) {
        submitPlaybackCommand(PlaybackCommand.SwitchLive(index))
    }

    private fun switchToLiveChannel(index: Int) {
        val playerInstance = player ?: return
        if (index !in channels.indices) {
            Timber.w("Invalid channel index: $index")
            return
        }
        val safeIndex = index.coerceIn(0, channels.lastIndex)
        val currentIndex = playerInstance.currentMediaItemIndex
        val isSameLiveChannel = !isArchivePlayback && currentIndex == safeIndex
        val stateAllowsSkip = when (_playerState.value) {
            is PlayerState.Ready, is PlayerState.Buffering -> true
            else -> false
        }
        if (isSameLiveChannel && stateAllowsSkip) {
            logDebug { "Ignoring duplicate playChannel request for index $safeIndex" }
            return
        }

        logDebug { "Switching to channel index $safeIndex" }
        logDebug { "switchToLiveChannel: from=$currentIndex to=$safeIndex" }
        isUserPaused = false
        stopAutoRetry()
        // Surface "Buffering" immediately so the UI reflects the in-progress switch
        // rather than (a) keeping a stale Forbidden/NotFound overlay from the prior
        // channel or (b) continuing to show the previous Ready(channel) until the
        // new content loads. The duplicate-skip above already covers the case where
        // the user re-taps the currently-playing channel.
        _playerState.value = PlayerState.Buffering
        markLiveSwitchRequested(safeIndex)

        if (isArchivePlayback) {
            restoreLivePlaylist(safeIndex)
        } else {
            playerInstance.seekToDefaultPosition(safeIndex)
            if (playerInstance.playbackState == Player.STATE_IDLE) {
                playerInstance.prepare()
            }
            playerInstance.playWhenReady = true
            playerInstance.play()
            // The prior buffering timer (now invalidated by the channel change) would
            // self-exit, but onPlaybackStateChanged does not re-fire when the player
            // stays in STATE_BUFFERING across the seek. Re-arm explicitly so the new
            // channel has a watchdog and the pending-switch rescue path can fire.
            stopBufferingCheck()
            bufferingStartTime = System.currentTimeMillis()
            startBufferingCheck()
        }

        lastLiveIndex = safeIndex
        isArchivePlayback = false
        archiveChannel = null
        archiveProgram = null
        pendingArchiveSeek = false
    }

    suspend fun playArchive(channel: Channel, program: EpgProgram): Boolean {
        val result = CompletableDeferred<Boolean>()
        submitPlaybackCommand(
            PlaybackCommand.PlayArchive(
                channel = channel,
                program = program,
                result = result
            )
        )
        return result.await()
    }

    private fun playArchiveInternal(channel: Channel, program: EpgProgram): Boolean {
        val playerInstance = player ?: return false
        val archiveUrl = ArchiveUrlBuilder.buildArchiveUrl(channel, program)
        if (archiveUrl.isNullOrBlank()) {
            addDebugMessage("DVR: ${channel.title} does not provide a catch-up URL")
            return false
        }
        isUserPaused = false
        stopAutoRetry()
        clearPendingLiveSwitch()
        val uri = archiveUrl.toUri()
        channels.indexOfFirst { it.url == channel.url }
            .takeIf { it >= 0 }
            ?.let { lastLiveIndex = it }

        val durationSeconds = program.durationUtcSeconds.coerceAtLeast(60)
        val startUtcSeconds = program.startUtcMillis / 1000L
        addDebugMessage("DVR: Request ${channel.title} • ${program.title}")
        addDebugMessage("DVR: Start=${startUtcSeconds}s, Duration=${durationSeconds}s")
        addDebugMessage("DVR: URL ${maskSensitive(uri)}")
        if (isDebugLoggingEnabled()) {
            probeArchiveUri(uri, program)
        }

        isArchivePlayback = true
        archiveChannel = channel
        archiveProgram = program
        pendingArchiveSeek = true

        playerInstance.stop()
        playerInstance.clearMediaItems()

        // ═══════════════════════════════════════════════════════════════
        // Flussonic DVR: Simple MediaItem without workarounds
        // The archive-{from}-{duration}.m3u8 format returns proper VOD
        // playlists with #EXT-X-ENDLIST, so ExoPlayer treats them
        // correctly without needing LiveConfiguration or seek hacks
        // ═══════════════════════════════════════════════════════════════
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaId("${channel.title}_${program.startUtcMillis}")
            .build()

        playerInstance.setMediaItems(listOf(mediaItem), /* startIndex = */ 0, /* startPositionMs = */ 0L)
        playerInstance.repeatMode = Player.REPEAT_MODE_OFF

        playerInstance.prepare()
        playerInstance.playWhenReady = true
        playerInstance.play()

        addDebugMessage("▶ DVR: ${channel.title} → ${program.title}")
        _playerState.value = PlayerState.Archive(channel, program)
        return true
    }

    fun restartArchive() {
        if (!isArchivePlayback) return
        player?.seekTo(0L)
        pendingArchiveSeek = false
    }

    fun seekBy(offsetMs: Long): Boolean {
        val playerInstance = player ?: return false
        var target = playerInstance.currentPosition + offsetMs
        val duration = playerInstance.duration
        if (duration != C.TIME_UNSET && offsetMs > 0) {
            target = min(target, max(0L, duration - 1000L))
        }
        target = max(0L, target)
        playerInstance.seekTo(target)
        return true
    }

    fun returnToLive() {
        submitPlaybackCommand(PlaybackCommand.ReturnToLive)
    }

    private fun returnToLiveInternal() {
        isUserPaused = false
        stopAutoRetry()
        clearPendingLiveSwitch()
        if (!isArchivePlayback) {
            player?.let { exoPlayer ->
                addDebugMessage("Return to live: resume live edge")
                exoPlayer.seekToDefaultPosition()
                exoPlayer.playWhenReady = true
                exoPlayer.play()
            }
            return
        }
        val index = lastLiveIndex.coerceIn(0, channels.lastIndex.takeIf { channels.isNotEmpty() } ?: 0)
        restoreLivePlaylist(index)
        addDebugMessage("Return to live: ${channels.getOrNull(index)?.title ?: "Unknown"}")
        channels.getOrNull(index)?.let {
            _playerState.value = PlayerState.Ready(it, index)
        }
    }

    private fun restoreLivePlaylist(targetIndex: Int) {
        if (channels.isEmpty()) return
        isUserPaused = false
        stopAutoRetry()
        val items = buildLiveMediaItems()
        player?.apply {
            stop()
            clearMediaItems()
            val index = targetIndex.coerceIn(0, channels.lastIndex)
            setMediaItems(items, index, C.TIME_UNSET)
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
        isArchivePlayback = false
        archiveChannel = null
        archiveProgram = null
        pendingArchiveSeek = false
    }


    private fun probeArchiveUri(uri: Uri, program: EpgProgram) {
        archiveProbeJob?.cancel()
        archiveProbeJob = workerScope.launch(Dispatchers.IO) {
            val factory = baseHttpDataSourceFactory
            val headSource = factory.createDataSource()
            try {
                val headSpec = DataSpec.Builder()
                    .setUri(uri)
                    .setHttpMethod(DataSpec.HTTP_METHOD_HEAD)
                    .build()
                headSource.open(headSpec)
                val resolved = headSource.uri ?: uri
                val headers = headSource.responseHeaders
                val contentType = headers["Content-Type"]?.firstOrNull() ?: "content-type=?"
                val contentLength = headers["Content-Length"]?.firstOrNull() ?: "?"
                addDebugMessage(
                    "DVR: Probe ${maskSensitive(resolved)} ($contentType, len=$contentLength)"
                )
                fetchManifestPreview(factory, resolved, program)
            } catch (e: Exception) {
                addDebugMessage("DVR: Probe failed (${e.message ?: "unknown error"})")
            } finally {
                try {
                    headSource.close()
                } catch (_: Exception) {
                    // Ignore close errors
                }
            }
        }
    }

    private fun fetchManifestPreview(
        factory: DefaultHttpDataSource.Factory,
        uri: Uri,
        program: EpgProgram
    ) {
        val source = factory.createDataSource()
        try {
            val dataSpec = DataSpec.Builder()
                .setUri(uri)
                .setHttpMethod(DataSpec.HTTP_METHOD_GET)
                .build()
            val inputStream = DataSourceInputStream(source, dataSpec)
            inputStream.use { stream ->
                val buffer = ByteArray(1024)
                val builder = StringBuilder()
                var totalRead = 0
                while (totalRead < 2048) {
                    val bytesToRead = min(buffer.size, 2048 - totalRead)
                    val read = stream.read(buffer, 0, bytesToRead)
                    if (read <= 0) break
                    builder.append(String(buffer, 0, read, StandardCharsets.UTF_8))
                    totalRead += read
                }
                val manifest = builder.toString()
                if (manifest.isNotBlank()) {
                    val preview = manifest.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .take(6)
                        .joinToString(" | ")
                    if (preview.isNotEmpty()) {
                        addDebugMessage("DVR: Manifest ${maskSensitive(preview)}")
                    } else {
                        addDebugMessage("DVR: Manifest (only blank lines)")
                    }
                    manifest.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { line ->
                            line.isNotEmpty() && !line.startsWith("#") && line.contains(".m3u8", ignoreCase = true)
                        }?.let { variantLine ->
                            val variantUri = resolveRelativeUri(uri, variantLine)
                            fetchVariantPreview(factory, variantUri, program)
                        }
                } else {
                    addDebugMessage("DVR: Manifest empty response")
                }
            }
        } catch (e: Exception) {
            addDebugMessage("DVR: Manifest fetch failed (${e.message ?: "unknown error"})")
        } finally {
            try {
                source.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }

    /**
     * Get current player instance
     */
    fun getPlayer(): ExoPlayer? = player

    /**
     * Pause playback
     */
    fun pause() {
        isUserPaused = true
        stopAutoRetry()
        val retryingError = _playerState.value as? PlayerState.Error
        if (retryingError?.isRetrying == true) {
            _playerState.value = retryingError.copy(
                isRetrying = false,
                retryAttempt = 0,
                retryMaxAttempts = 0
            )
        }
        player?.playWhenReady = false
    }

    fun cancelAutoRetry() {
        stopAutoRetry()
        clearRetryingState()
    }

    fun setAutoRetrySuppressed(suppressed: Boolean) {
        if (autoRetrySuppressed == suppressed) return
        autoRetrySuppressed = suppressed
        if (suppressed) {
            stopAutoRetry()
            clearRetryingState()
        }
    }

    private fun buildLiveMediaItems(): List<MediaItem> {
        if (liveMediaItemsCache.size != channels.size || liveMediaItemsCache.isEmpty()) {
            liveMediaItemsCache = buildMediaItems(channels)
        }
        return liveMediaItemsCache
    }

    private fun buildMediaItems(channelList: List<Channel>): List<MediaItem> {
        return channelList.map { channel ->
            MediaItem.Builder()
                .setUri(channel.url)
                .setMediaId(channel.title)
                .build()
        }
    }

    /**
     * Resume playback if a player exists
     */
    fun resume() {
        isUserPaused = false
        player?.let { exoPlayer ->
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.prepare()
            }
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
    }

    /**
     * Release player resources
     */
    fun release() {
        playbackCommandProcessorJob?.cancel()
        playbackCommandProcessorJob = null
        preferencesCollectorJob?.cancel()
        preferencesCollectorJob = null
        // Drain pending commands synchronously
        while (true) {
            val pending = playbackCommands.tryReceive().getOrNull() ?: break
            if (pending is PlaybackCommand.PlayArchive) {
                pending.result.complete(false)
            }
        }
        deferredPlaybackCommands.forEach { pending ->
            if (pending is PlaybackCommand.PlayArchive) {
                pending.result.complete(false)
            }
        }
        deferredPlaybackCommands.clear()
        releaseInternal()
    }

    private fun releaseInternal() {
        stopBufferingCheck()
        stopAutoRetry()
        clearPendingLiveSwitch()
        isUserPaused = false
        player?.let { p ->
            playerListener?.let { p.removeListener(it) }
            analyticsListener?.let { p.removeAnalyticsListener(it) }
            p.stop()
            p.release()
        }
        player = null
        playerListener = null
        analyticsListener = null
        archiveProbeJob?.cancel()
        archiveProbeJob = null
        isArchivePlayback = false
        archiveChannel = null
        archiveProgram = null
        pendingArchiveSeek = false
        liveMediaItemsCache = emptyList()
        _playerState.value = PlayerState.Idle
        logDebug { "Player released" }
    }

    /**
     * Buffering timeout check
     */
    private fun startBufferingCheck() {
        bufferingCheckJob?.cancel()
        val playerInstance = player ?: return
        val startedIndex = playerInstance.currentMediaItemIndex
        bufferingCheckJob = mainScope.launch {
            val startTime = bufferingStartTime
            if (startTime <= 0L) return@launch
            delay(PlayerConstants.BUFFERING_TIMEOUT_MS)
            val p = player ?: return@launch
            if (bufferingStartTime != startTime ||
                p.playbackState != Player.STATE_BUFFERING ||
                p.currentMediaItemIndex != startedIndex
            ) return@launch
            val bufferingDuration = System.currentTimeMillis() - startTime
            addDebugMessage("Buffering timeout (${bufferingDuration / 1000}s)")
            stopBufferingCheck()

            // Switch in flight that never reached READY: release suppression and
            // surface a Timeout so the user is not left on a frozen Buffering UI.
            if (pendingLiveSwitch != null) {
                clearPendingLiveSwitch()
                _playerState.value = PlayerState.Error(
                    issue = PlaybackIssue.Timeout("Stream did not start"),
                    channel = channels.getOrNull(startedIndex)
                )
            }

            p.playWhenReady = false
        }
    }

    private fun stopBufferingCheck() {
        bufferingCheckJob?.cancel()
        bufferingCheckJob = null
        bufferingStartTime = 0
    }

    private fun isDebugLoggingEnabled(): Boolean = currentConfig?.showDebugLog == true

    private fun resolveRelativeUri(base: Uri, reference: String): Uri {
        return try {
            val resolved = URI(base.toString()).resolve(reference)
            resolved.toString().toUri()
        } catch (e: Exception) {
            addDebugMessage("DVR: Failed to resolve URI ${maskSensitive(reference)} (${e.message ?: "unknown"})")
            base
        }
    }

    private fun parseIso8601ToMillis(value: String): Long? {
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX"
        )
        for (pattern in patterns) {
            try {
                val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = formatter.parse(value)
                if (date != null) return date.time
            } catch (_: Exception) {
                // Try next pattern
            }
        }
        return null
    }

    /**
     * Add debug message
     */
    private fun addDebugMessage(message: String) {
        if (!isDebugLoggingEnabled()) return
        logDebug { message }
        _debugMessages.tryEmit(DebugMessage(message))
    }

    private data class PendingLiveSwitch(
        val requestId: Long,
        val targetIndex: Int
    )

    private sealed interface PlaybackCommand {
        data class Initialize(
            val channels: List<Channel>,
            val config: PlayerConfig,
            val startIndex: Int,
            val mediaItems: List<MediaItem>
        ) : PlaybackCommand

        data class RefreshChannels(
            val channels: List<Channel>,
            val preferredIndex: Int,
            val mediaItems: List<MediaItem>
        ) : PlaybackCommand

        data class SwitchLive(val index: Int) : PlaybackCommand
        object ReturnToLive : PlaybackCommand

        data class PlayArchive(
            val channel: Channel,
            val program: EpgProgram,
            val result: CompletableDeferred<Boolean>
        ) : PlaybackCommand
    }

    private companion object {
        private const val MAX_UNKNOWN_RETRIES_DURING_SWITCH = 2
        private const val SWITCH_COMPLETED_STALE_GRACE_MS = 3_000L
    }

    private val sensitivePattern = Regex("(?i)((token|auth|sig|key|session)[^=]*)=[^&]*")

    private fun maskSensitive(uri: Uri): String = maskSensitive(uri.toString())

    private fun maskSensitive(text: CharSequence): String {
        return sensitivePattern.replace(text) { matchResult ->
            "${matchResult.groups[1]?.value}=***"
        }
    }
}

/**
 * FFmpeg Renderers Factory
 */
@UnstableApi
class FFmpegRenderersFactory(
    context: Context,
    private val useFfmpegAudio: Boolean,
    private val useFfmpegVideo: Boolean
) : NextRenderersFactory(context) {

    init {
        logDebug { "FFmpegFactory Init: Audio=$useFfmpegAudio, Video=$useFfmpegVideo" }
        setEnableDecoderFallback(false)
        forceEnableMediaCodecAsynchronousQueueing()
        setAllowedVideoJoiningTimeMs(10000)
        experimentalSetEnableMediaCodecVideoRendererPrewarming(false)
        experimentalSetParseAv1SampleDependencies(false)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val videoMode = if (useFfmpegVideo) EXTENSION_RENDERER_MODE_PREFER else EXTENSION_RENDERER_MODE_OFF
        super.buildVideoRenderers(
            context, videoMode, mediaCodecSelector, false,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out
        )
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val audioMode = if (useFfmpegAudio) EXTENSION_RENDERER_MODE_PREFER else EXTENSION_RENDERER_MODE_OFF
        super.buildAudioRenderers(
            context, audioMode, mediaCodecSelector, false,
            audioSink, eventHandler, eventListener, out
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
            .build()
    }

    override fun buildTextRenderers(
        context: Context,
        output: androidx.media3.exoplayer.text.TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        // Disable text renderers
    }
}
