package com.rutv.domain.usecase

import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.util.Result
import com.rutv.util.logDebug
import kotlinx.coroutines.CancellationException
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

            val windowResult = validateArchiveWindowAndBuildInfo(channel, program, currentTime)
            return when (windowResult) {
                is Result.Success -> {
                    val info = windowResult.data
                    logDebug {
                        "Archive playback validated: ${channel.title} -> ${program.title} " +
                            "(${info.durationMinutes}m, ${info.ageMinutes}m ago)"
                    }
                    Result.Success(info)
                }
                is Result.Error -> windowResult
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error validating archive playback")
            return Result.Error(e, "Archive playback validation failed: ${e.message}")
        }
    }
}

