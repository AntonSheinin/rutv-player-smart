package com.rutv.presentation.player

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import com.rutv.R
import com.rutv.data.model.PlayerConfig
import com.rutv.util.PlayerConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
@Singleton
class PreviewPlayerFactory @Inject constructor(
    private val httpDataSourceFactory: DefaultHttpDataSource.Factory
) {
    fun create(context: Context, config: PlayerConfig): ChannelPreviewController {
        val appContext = context.applicationContext
        val player = buildPreviewPlayer(appContext, config)
        val playerView = LayoutInflater.from(context)
            .inflate(R.layout.channel_preview_player_view, null, false) as PlayerView
        return ChannelPreviewController(player, playerView)
    }

    private fun buildPreviewPlayer(context: Context, config: PlayerConfig): ExoPlayer {
        val renderersFactory = if (config.useFfmpegAudio || config.useFfmpegVideo) {
            FFmpegRenderersFactory(context, config.useFfmpegAudio, config.useFfmpegVideo)
        } else {
            DefaultRenderersFactory(context).apply {
                setEnableDecoderFallback(true)
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                PlayerConstants.MIN_BUFFER_MS,
                PlayerConstants.MAX_BUFFER_MS,
                PlayerConstants.BUFFER_FOR_PLAYBACK_MS,
                PlayerConstants.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val hlsExtractorFactory = DefaultHlsExtractorFactory(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
            true
        )
        val mediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)
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
                .setMaxVideoBitrate(PREVIEW_MAX_VIDEO_BITRATE)
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                .setDisabledTextTrackSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                .setSelectUndeterminedTextLanguage(false)
                .build()
        }

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
            }
    }

    private companion object {
        private const val PREVIEW_MAX_VIDEO_BITRATE = 3_000_000
    }
}

@UnstableApi
class ChannelPreviewController(
    private val player: ExoPlayer,
    private val playerView: PlayerView
) {
    private val _state = MutableStateFlow<ChannelPreviewPlaybackState>(ChannelPreviewPlaybackState.Idle)
    val state: StateFlow<ChannelPreviewPlaybackState> = _state.asStateFlow()

    private var currentUrl: String? = null
    private var suppressedUrl: String? = null
    private var sessionDisabled: Boolean = false
    private var released: Boolean = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val url = currentUrl ?: return
            if (playbackState == Player.STATE_READY && _state.value is ChannelPreviewPlaybackState.Loading) {
                _state.value = ChannelPreviewPlaybackState.Playing(url)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val url = currentUrl
            Timber.w(error, "Channel preview playback failed")
            stopInternal(clearSuppressed = false)
            if (url == null) {
                _state.value = ChannelPreviewPlaybackState.Idle
                return
            }
            suppressedUrl = url
            if (isResourceFailure(error)) {
                sessionDisabled = true
                _state.value = ChannelPreviewPlaybackState.SessionDisabled
            } else {
                _state.value = ChannelPreviewPlaybackState.Unavailable(url)
            }
        }
    }

    init {
        player.addListener(listener)
        playerView.player = player
        playerView.useController = false
        playerView.controllerHideOnTouch = true
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    }

    fun obtainPlayerView(): PlayerView {
        (playerView.parent as? ViewGroup)?.removeView(playerView)
        playerView.useController = false
        playerView.player = player
        return playerView
    }

    fun preview(url: String) {
        if (released || sessionDisabled) return
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) {
            stop()
            return
        }
        if (suppressedUrl != null && suppressedUrl != trimmedUrl) {
            suppressedUrl = null
        }
        if (suppressedUrl == trimmedUrl) {
            stopInternal(clearSuppressed = false)
            _state.value = ChannelPreviewPlaybackState.Unavailable(trimmedUrl)
            return
        }
        val currentState = _state.value
        if (currentUrl == trimmedUrl &&
            (currentState is ChannelPreviewPlaybackState.Loading || currentState is ChannelPreviewPlaybackState.Playing)
        ) {
            return
        }

        stopInternal(clearSuppressed = false)
        currentUrl = trimmedUrl
        _state.value = ChannelPreviewPlaybackState.Loading(trimmedUrl)
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(trimmedUrl.toUri())
                .setMediaId(trimmedUrl)
                .build()
        )
        player.prepare()
        player.playWhenReady = true
        player.play()
    }

    fun stop() {
        stopInternal(clearSuppressed = false)
        if (!sessionDisabled) {
            _state.value = ChannelPreviewPlaybackState.Idle
        }
    }

    fun stopForLifecycle() {
        stopInternal(clearSuppressed = false)
        if (!sessionDisabled) {
            _state.value = ChannelPreviewPlaybackState.Idle
        }
    }

    fun resetSession() {
        sessionDisabled = false
        suppressedUrl = null
        stopInternal(clearSuppressed = true)
        _state.value = ChannelPreviewPlaybackState.Idle
    }

    fun release() {
        if (released) return
        released = true
        player.removeListener(listener)
        playerView.player = null
        player.stop()
        player.clearMediaItems()
        player.release()
        _state.value = ChannelPreviewPlaybackState.Idle
    }

    private fun stopInternal(clearSuppressed: Boolean) {
        currentUrl = null
        if (clearSuppressed) suppressedUrl = null
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
    }

    private fun isResourceFailure(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
            else -> false
        }
    }
}

sealed interface ChannelPreviewPlaybackState {
    object Idle : ChannelPreviewPlaybackState
    data class Loading(val url: String) : ChannelPreviewPlaybackState
    data class Playing(val url: String) : ChannelPreviewPlaybackState
    data class Unavailable(val url: String) : ChannelPreviewPlaybackState
    object SessionDisabled : ChannelPreviewPlaybackState
}
