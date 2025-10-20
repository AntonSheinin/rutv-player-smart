package com.videoplayer.presentation.player

import android.content.Context
import android.net.Uri
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.data.model.PlayerConfig
import com.videoplayer.presentation.player.ArchiveEndReason
import com.videoplayer.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import java.nio.charset.StandardCharsets
import kotlin.math.min

@UnstableApi
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var player: ExoPlayer? = null
    private var channels: List<Channel> = emptyList()

    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _playerEvents = MutableSharedFlow<PlayerEvent>(replay = 0, extraBufferCapacity = 64)

    private val _debugMessages = MutableSharedFlow<DebugMessage>(replay = 100, extraBufferCapacity = 100)
    val debugMessages: SharedFlow<DebugMessage> = _debugMessages.asSharedFlow()

    private var bufferingStartTime: Long = 0
    private val bufferingCheckHandler = Handler(Looper.getMainLooper())
    private var bufferingCheckRunnable: Runnable? = null

    private var currentConfig: PlayerConfig? = null
    private var isArchivePlayback: Boolean = false
    private var archiveProgram: EpgProgram? = null
    private var archiveChannel: Channel? = null
    private var lastLiveIndex: Int = 0
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var httpDataSourceFactory: DefaultHttpDataSource.Factory

    companion object {
        @Suppress("StaticFieldLeak")
        private var sharedBandwidthMeter: DefaultBandwidthMeter? = null

        fun getBandwidthMeter(context: Context): DefaultBandwidthMeter {
            if (sharedBandwidthMeter == null) {
                sharedBandwidthMeter = DefaultBandwidthMeter.Builder(context.applicationContext)
                    .setInitialBitrateEstimate(2800000L)
                    .build()
            }
            return sharedBandwidthMeter!!
        }
    }

    /**
     * Initialize player with channels
     */
    fun initialize(channels: List<Channel>, config: PlayerConfig, startIndex: Int = 0) {
        Timber.d("Initializing player with ${channels.size} channels, startIndex=$startIndex")
        addDebugMessage("App Started")

        if (channels.isEmpty()) {
            Timber.w("Cannot initialize player with empty channel list")
            return
        }

        this.channels = channels
        this.currentConfig = config

        // Release existing player if any
        release()

        // Create new player
        createPlayer(config, startIndex)
    }

    /**
     * Create ExoPlayer instance
     */
    private fun createPlayer(config: PlayerConfig, startIndex: Int) {
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
            val minBufferMs = maxOf(Constants.MIN_BUFFER_MS, bufferMs)
            val maxBufferMs = maxOf(Constants.MAX_BUFFER_MS, bufferMs)

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    minBufferMs,
                    maxBufferMs,
                    Constants.BUFFER_FOR_PLAYBACK_MS,
                    Constants.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val httpDataSourceFactory = ensureHttpDataSourceFactory()

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
                .setSeekBackIncrementMs(Constants.SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(Constants.SEEK_INCREMENT_MS)
                .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                .build()
                .apply {
                    // Set media items
                    val mediaItems = buildLiveMediaItems()
                    setMediaItems(mediaItems)

                    addDebugMessage("📺 Loaded ${mediaItems.size} channels into player")

                    repeatMode = Player.REPEAT_MODE_ALL

                    // Add analytics listener
                    addAnalyticsListener(createAnalyticsListener())

                    // Add player listener
                    addListener(createPlayerListener())

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
            _playerState.value = PlayerState.Error(e.message ?: "Unknown error", null)
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
                _playerEvents.tryEmit(PlayerEvent.AudioDecoderInitialized(decoderName))
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long
            ) {
                addDebugMessage("🎬 Video decoder: $decoderName")
                _playerEvents.tryEmit(PlayerEvent.VideoDecoderInitialized(decoderName))
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                if (droppedFrames > 0) {
                    val fps = if (elapsedMs > 0) (droppedFrames * 1000f / elapsedMs) else 0f
                    addDebugMessage("⚠️ Dropped $droppedFrames frames in ${elapsedMs}ms (${String.format(java.util.Locale.US, "%.1f", fps)} fps)")
                    _playerEvents.tryEmit(PlayerEvent.DroppedFrames(droppedFrames, elapsedMs))
                }
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
                mediaItem?.let {
                    val currentIndex = player?.currentMediaItemIndex ?: return
                    val channel = channels.getOrNull(currentIndex) ?: return

                    Timber.d("Channel transition to: ${channel.title} (#${currentIndex + 1})")
                    _playerState.value = PlayerState.Ready(channel, currentIndex)
                    _playerEvents.tryEmit(PlayerEvent.ChannelChanged(channel, currentIndex))
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (isArchivePlayback) {
                            val channel = archiveChannel
                            val program = archiveProgram
                            if (channel != null && program != null) {
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
                            _playerState.value = PlayerState.Ready(it, currentIndex)
                        }

                        stopBufferingCheck()
                    }
                    Player.STATE_BUFFERING -> {
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
                val channel = channels.getOrNull(currentIndex)
                val errorMsg = error.message ?: "Unknown error"

                addDebugMessage("✗ Error: ${channel?.title ?: "Unknown"}")
                addDebugMessage("  → $errorMsg")

                _playerState.value = PlayerState.Error(errorMsg, channel)

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
        player?.let { p ->
            if (index >= 0 && index < channels.size) {
                Timber.d("Playing channel at index $index")
                if (isArchivePlayback) {
                    restoreLivePlaylist(index)
                } else {
                    p.seekTo(index, C.TIME_UNSET)
                    p.prepare()
                    p.playWhenReady = true
                    p.play()
                }
                lastLiveIndex = index
                isArchivePlayback = false
                archiveChannel = null
                archiveProgram = null
            } else {
                Timber.w("Invalid channel index: $index")
            }
        }
    }

    fun playArchive(channel: Channel, program: EpgProgram): Boolean {
        val playerInstance = player ?: return false
        val archiveUrl = channel.buildArchiveUrl(program)
        if (archiveUrl.isNullOrBlank()) {
            addDebugMessage("DVR: ${channel.title} does not provide a catch-up URL")
            return false
        }
        val uri = Uri.parse(archiveUrl)
        val channelIndex = channels.indexOfFirst { it.url == channel.url }
        if (channelIndex >= 0) {
            lastLiveIndex = channelIndex
        }

        val durationSeconds = ((program.stopTimeMillis - program.startTimeMillis) / 1000L).coerceAtLeast(60)
        val isOngoing = program.stopTimeMillis > System.currentTimeMillis()
        addDebugMessage("DVR: Template='${channel.catchupSource.ifBlank { "Flussonic path-based" }}'")
        addDebugMessage("DVR: Generated URL: ${maskSensitive(uri)}")
        addDebugMessage("DVR: Duration=${durationSeconds}s, Start=${program.startTimeMillis/1000}")
        if (isOngoing) {
            addDebugMessage("DVR: Ongoing program (EVENT playlist)")
        } else {
            addDebugMessage("DVR: Completed program (VOD playlist)")
        }

        isArchivePlayback = true
        archiveChannel = channel
        archiveProgram = program

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
            .setMediaId("${channel.title}_${program.startTimeMillis}")
            .build()

        playerInstance.setMediaItems(listOf(mediaItem))
        playerInstance.repeatMode = Player.REPEAT_MODE_OFF

        playerInstance.prepare()
        playerInstance.playWhenReady = true
        playerInstance.play()

        addDebugMessage("▶ DVR: ${channel.title} → ${program.title}")
        _playerState.value = PlayerState.Archive(channel, program)
        return true
    }

    fun returnToLive() {
        if (!isArchivePlayback) return
        val index = lastLiveIndex.coerceIn(0, channels.lastIndex.takeIf { channels.isNotEmpty() } ?: 0)
        restoreLivePlaylist(index)
        addDebugMessage("â–¶ Live: ${channels.getOrNull(index)?.title ?: "Unknown"}")
        channels.getOrNull(index)?.let {
            _playerState.value = PlayerState.Ready(it, index)
            _playerEvents.tryEmit(PlayerEvent.ChannelChanged(it, index))
        }
    }

    private fun restoreLivePlaylist(targetIndex: Int) {
        if (channels.isEmpty()) return
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
    }

    private fun ensureHttpDataSourceFactory(): DefaultHttpDataSource.Factory {
        if (!::httpDataSourceFactory.isInitialized) {
            httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(Constants.HTTP_CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(Constants.HTTP_READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)
                .setUserAgent(Constants.DEFAULT_USER_AGENT)
                .setTransferListener(getBandwidthMeter(context))
                .setDefaultRequestProperties(
                    mapOf(
                        "Accept" to "*/*",
                        "Accept-Encoding" to "gzip, deflate",
                        "Connection" to "keep-alive"
                    )
                )
        }
        return httpDataSourceFactory
    }

    private fun probeArchiveUri(uri: Uri) {
        networkScope.launch {
            val factory = ensureHttpDataSourceFactory()
            val headSource = factory.createDataSource()
            try {
                val headSpec = DataSpec.Builder()
                    .setUri(uri)
                    .setHttpMethod(DataSpec.HTTP_METHOD_HEAD)
                    .build()
                headSource.open(headSpec)
                val resolved = headSource.uri ?: uri
                val headers = (headSource as? DefaultHttpDataSource)?.responseHeaders.orEmpty()
                val contentType = headers["Content-Type"]?.firstOrNull()
                val contentLength = headers["Content-Length"]?.firstOrNull()
                addDebugMessage(
                    "DVR: Probe ${maskSensitive(resolved)} (${contentType ?: "content-type=?"}, len=${contentLength ?: "?"})"
                )
                fetchManifestPreview(factory, resolved)
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
        uri: Uri
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
        player?.playWhenReady = false
    }

    private fun buildLiveMediaItems(): List<MediaItem> {
        return channels.map { channel ->
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
        stopBufferingCheck()
        player?.stop()
        player?.release()
        player = null
        _playerState.value = PlayerState.Idle
        Timber.d("Player released")
    }

    /**
     * Buffering timeout check
     */
    private fun startBufferingCheck() {
        cancelBufferingCheckCallbacks()
        bufferingCheckRunnable = object : Runnable {
            override fun run() {
                player?.let { p ->
                    if (p.playbackState == Player.STATE_BUFFERING && bufferingStartTime > 0) {
                        val bufferingDuration = System.currentTimeMillis() - bufferingStartTime
                        if (bufferingDuration > Constants.BUFFERING_TIMEOUT_MS) {
                            addDebugMessage("⚠ Buffering timeout (${bufferingDuration/1000}s)")
                            _playerEvents.tryEmit(PlayerEvent.BufferingTimeout(bufferingDuration))
                            stopBufferingCheck()
                            p.playWhenReady = false
                        } else {
                            bufferingCheckHandler.postDelayed(this, 1000)
                        }
                    }
                }
            }
        }
        bufferingCheckHandler.postDelayed(bufferingCheckRunnable!!, 1000)
    }

    private fun cancelBufferingCheckCallbacks() {
        bufferingCheckRunnable?.let {
            bufferingCheckHandler.removeCallbacks(it)
            bufferingCheckRunnable = null
        }
    }

    private fun stopBufferingCheck() {
        cancelBufferingCheckCallbacks()
        bufferingStartTime = 0
    }

    /**
     * Add debug message
     */
    private fun addDebugMessage(message: String) {
        Timber.d(message)
        _debugMessages.tryEmit(DebugMessage(message))
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
        Timber.d("FFmpegFactory Init: Audio=$useFfmpegAudio, Video=$useFfmpegVideo")
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






