package com.rutv.domain.usecase

import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.util.Result
import timber.log.Timber

internal fun validateArchiveWindowAndBuildInfo(
    channel: Channel,
    program: EpgProgram,
    currentTimeMillis: Long = System.currentTimeMillis()
): Result<ArchivePlaybackInfo> {
    val maxArchiveMillis = channel.catchupDays * 24L * 60 * 60 * 1000
    val age = currentTimeMillis - program.startTimeMillis
    if (maxArchiveMillis > 0 && age > maxArchiveMillis) {
        val message = "${program.title} is outside of ${channel.catchupDays} day archive window"
        Timber.w(message)
        return Result.Error(Exception(message), message)
    }

    val durationMinutes = ((program.stopTimeMillis - program.startTimeMillis) / 60000L).coerceAtLeast(1)
    val ageMinutes = (age / 60000L).coerceAtLeast(0)

    val info = ArchivePlaybackInfo(
        channel = channel,
        program = program,
        durationMinutes = durationMinutes,
        ageMinutes = ageMinutes,
        templateUsed = channel.catchupSource.ifBlank { "Flussonic path-based" }
    )

    return Result.Success(info)
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
