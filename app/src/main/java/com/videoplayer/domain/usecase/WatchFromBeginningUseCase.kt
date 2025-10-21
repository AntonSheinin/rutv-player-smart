package com.videoplayer.domain.usecase

import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.util.Result
import timber.log.Timber
import javax.inject.Inject

/**
 * Use case for timeshift functionality - watch current program from beginning
 *
 * Validates that:
 * - Channel supports catch-up/timeshift
 * - Program has started
 * - Program is within archive window
 */
class WatchFromBeginningUseCase @Inject constructor() {

    /**
     * Validate timeshift request to watch current program from beginning
     *
     * @param channel Current channel
     * @param program Current program to restart
     * @return Result indicating if timeshift is valid
     */
    operator fun invoke(channel: Channel, program: EpgProgram): Result<ArchivePlaybackInfo> {
        try {
            // Validate channel supports catch-up/timeshift
            if (!channel.supportsCatchup()) {
                val message = "${channel.title} does not support timeshift/catch-up"
                Timber.w(message)
                return Result.Error(Exception(message), message)
            }

            val currentTime = System.currentTimeMillis()

            // Check if program has started
            if (program.startTimeMillis > currentTime) {
                val minutesUntilStart = (program.startTimeMillis - currentTime) / 60000
                val message = "${program.title} hasn't started yet (starts in $minutesUntilStart minutes)"
                Timber.d(message)
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
                templateUsed = if (channel.catchupSource.isBlank()) "Flussonic path-based" else channel.catchupSource
            )

            Timber.d("Timeshift validated: Restarting ${program.title} from beginning (${ageMinutes}m into program)")
            return Result.Success(info)

        } catch (e: Exception) {
            Timber.e(e, "Error validating timeshift request")
            return Result.Error(e, "Timeshift validation failed: ${e.message}")
        }
    }
}
