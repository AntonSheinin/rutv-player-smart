package com.rutv.domain.usecase

import com.rutv.data.repository.ChannelRepository
import com.rutv.util.Result
import com.rutv.util.logDebug
import timber.log.Timber
import javax.inject.Inject

/**
 * Use case for updating aspect ratio of a channel
 */
class UpdateAspectRatioUseCase @Inject constructor(
    private val channelRepository: ChannelRepository
) {

    suspend operator fun invoke(channelUrl: String, aspectRatio: Int): Result<Unit> {
        return try {
            logDebug { "Updating aspect ratio for channel: $channelUrl to $aspectRatio" }
            channelRepository.updateAspectRatio(channelUrl, aspectRatio)
        } catch (e: Exception) {
            Timber.e(e, "Error updating aspect ratio")
            Result.Error(e)
        }
    }
}
