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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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

            val bandwidthMeter = getBandwidthMeter(context)

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

            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(Constants.HTTP_CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(Constants.HTTP_READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .setTransferListener(bandwidthMeter)
                .setDefaultRequestProperties(mapOf(
                    "Accept" to "*/*",
                    "Accept-Encoding" to "gzip, deflate",
                    "Connection" to "keep-alive"
                ))

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

    fun playArchive(channel: Channel, program: EpgProgram) {
        val playerInstance = player ?: return
        val archiveUrl = channel.buildArchiveUrl(program)
        if (archiveUrl.isNullOrBlank()) {
            addDebugMessage("DVR: ${channel.title} does not provide a catch-up URL")
            return
        }
        val uri = Uri.parse(archiveUrl)
        val channelIndex = channels.indexOfFirst { it.url == channel.url }
        if (channelIndex >= 0) {
            lastLiveIndex = channelIndex
        }

        isArchivePlayback = true
        archiveChannel = channel
        archiveProgram = program

        playerInstance.stop()
        playerInstance.clearMediaItems()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaId("${channel.title}_${program.startTimeMillis}")
            .build()
        playerInstance.setMediaItem(mediaItem)
        playerInstance.prepare()
        playerInstance.playWhenReady = true

        addDebugMessage("â–¶ DVR: ${channel.title} â†’ ${program.title}")
        _playerState.value = PlayerState.Archive(channel, program)
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
            prepare()
            playWhenReady = true
        }
        isArchivePlayback = false
        archiveChannel = null
        archiveProgram = null
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






