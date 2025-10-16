package com.videoplayer.presentation.player

import androidx.media3.common.util.UnstableApi
import com.videoplayer.data.model.Channel

/**
 * Represents the state of the player
 */
@UnstableApi
sealed class PlayerState {
    object Idle : PlayerState()
    object Buffering : PlayerState()
    data class Ready(val channel: Channel, val index: Int) : PlayerState()
    data class Error(val message: String, val channel: Channel?) : PlayerState()
    object Ended : PlayerState()
}

/**
 * Player events
 */
@UnstableApi
sealed class PlayerEvent {
    data class ChannelChanged(val channel: Channel, val index: Int) : PlayerEvent()
    data class AudioDecoderInitialized(val decoderName: String) : PlayerEvent()
    data class VideoDecoderInitialized(val decoderName: String) : PlayerEvent()
    data class DroppedFrames(val count: Int, val elapsedMs: Long) : PlayerEvent()
    data class BufferingTimeout(val durationMs: Long) : PlayerEvent()
}

/**
 * Debug message for logging
 */
data class DebugMessage(
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
