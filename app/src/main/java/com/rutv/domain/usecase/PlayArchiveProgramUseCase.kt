package com.rutv.domain.usecase

import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.util.Result
import com.rutv.util.logDebug
import timber.log.Timber
import javax.inject.Inject

/**
 * Use case for playing archived programs with DVR validation
 *
 * Validates that:
 * - Channel supports catch-up/DVR
 * - Program has ended (for archive playback)
 * - Program is within archive window
 */
class PlayArchiveProgramUseCase @Inject constructor() {

    /**
     * Validate archive playback request
     *
     * @param channel Channel to play archive from
     * @param program Program to play from archive
     * @return Result indicating if playback is valid
     */
    operator fun invoke(channel: Channel, program: EpgProgram): Result<ArchivePlaybackInfo> {
        try {
            // Validate channel supports catch-up
            if (!channel.supportsCatchup()) {
                val message = "Channel ${channel.title} does not support catch-up/DVR"
                Timber.w(message)
                return Result.Error(Exception(message), message)
            }

            val currentTime = System.currentTimeMillis()

            // Check if program has ended (for completed archive playback)
            if (program.stopTimeMillis > currentTime) {
                val minutesRemaining = (program.stopTimeMillis - currentTime) / 60000
                val message = "${program.title} is still airing (ends in $minutesRemaining minutes)"
                logDebug { message }
                return Result.Error(Exception(message), message)
            }

            // Check if program is within archive window
            val maxArchiveMillis = channel.catchupDays * 24L * 60 * 60 * 1000
            val age = currentTime - program.startTimeMillis
            if (maxArchiveMillis > 0 && age > maxArchiveMillis) {
                val message = "${program.title} is outside of ${channel.catchupDays} day archive window"
                Timber.w(message)
                return Result.Error(Exception(message), message)
            }

            // Calculate metadata
            val durationMinutes = ((program.stopTimeMillis - program.startTimeMillis) / 60000L).coerceAtLeast(1)
            val ageMinutes = (age / 60000L).coerceAtLeast(0)

            val info = ArchivePlaybackInfo(
                channel = channel,
                program = program,
                durationMinutes = durationMinutes,
                ageMinutes = ageMinutes,
                templateUsed = channel.catchupSource.ifBlank { "Flussonic path-based" }
            )

            logDebug { "Archive playback validated: ${channel.title} -> ${program.title} (${durationMinutes}m, ${ageMinutes}m ago)" }
            return Result.Success(info)

        } catch (e: Exception) {
            Timber.e(e, "Error validating archive playback")
            return Result.Error(e, "Archive playback validation failed: ${e.message}")
        }
    }
}

/**
 * Archive playback metadata
 */
data class ArchivePlaybackInfo(
    val channel: Channel,
    val program: EpgProgram,
    val durationMinutes: Long,
    val ageMinutes: Long,
    val templateUsed: String
)
