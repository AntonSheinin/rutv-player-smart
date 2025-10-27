package com.videoplayer.domain.usecase

import com.videoplayer.data.model.EpgProgram
import com.videoplayer.data.repository.EpgRepository
import com.videoplayer.util.Result
import timber.log.Timber
import javax.inject.Inject

/**
 * Use case for loading EPG data for a specific channel
 *
 * Retrieves all programs and current program for a channel by tvgId
 */
class LoadEpgForChannelUseCase @Inject constructor(
    private val epgRepository: EpgRepository
) {

    /**
     * Load EPG data for specific channel
     *
     * @param tvgId Channel TVG ID
     * @return Result containing channel EPG data
     */
    operator fun invoke(tvgId: String): Result<ChannelEpgData> {
        try {
            if (tvgId.isBlank()) {
                val message = "TVG ID cannot be blank"
                Timber.w(message)
                return Result.Error(Exception(message), message)
            }

            // Get all programs for channel
            val programs = epgRepository.getProgramsForChannel(tvgId)

            // Get current program
            val currentProgram = epgRepository.getCurrentProgram(tvgId)

            val data = ChannelEpgData(
                tvgId = tvgId,
                programs = programs,
                currentProgram = currentProgram
            )

            Timber.d("Loaded EPG for $tvgId: ${programs.size} programs${currentProgram?.let { ", current: ${it.title}" } ?: ""}")
            return Result.Success(data)

        } catch (e: Exception) {
            Timber.e(e, "Error loading EPG for channel $tvgId")
            return Result.Error(e, "Failed to load EPG: ${e.message}")
        }
    }
}

/**
 * Channel EPG data
 */
data class ChannelEpgData(
    val tvgId: String,
    val programs: List<EpgProgram>,
    val currentProgram: EpgProgram?
)
