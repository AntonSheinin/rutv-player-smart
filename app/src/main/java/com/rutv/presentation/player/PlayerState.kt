package com.rutv.presentation.player

import androidx.media3.common.util.UnstableApi
import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram

/**
 * Represents the state of the player
 */
@UnstableApi
sealed class PlayerState {
    object Idle : PlayerState()
    object Buffering : PlayerState()
    data class Ready(val channel: Channel, val index: Int) : PlayerState()
    data class Archive(
        val channel: Channel,
        val program: EpgProgram,
        val endReason: ArchiveEndReason? = null
    ) : PlayerState()
    data class Error(
        val issue: PlaybackIssue,
        val channel: Channel?,
        val isRetrying: Boolean = false,
        val retryAttempt: Int = 0,
        val retryMaxAttempts: Int = 0
    ) : PlayerState()
    object Ended : PlayerState()
}

enum class ArchiveEndReason {
    COMPLETED
}

/**
 * Debug message for logging
 */
data class DebugMessage(
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
