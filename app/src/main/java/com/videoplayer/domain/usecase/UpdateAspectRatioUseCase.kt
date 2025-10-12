package com.videoplayer.domain.usecase

import com.videoplayer.data.repository.ChannelRepository
import com.videoplayer.util.Result
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
            Timber.d("Updating aspect ratio for channel: $channelUrl to $aspectRatio")
            channelRepository.updateAspectRatio(channelUrl, aspectRatio)
        } catch (e: Exception) {
            Timber.e(e, "Error updating aspect ratio")
            Result.Error(e)
        }
    }
}
