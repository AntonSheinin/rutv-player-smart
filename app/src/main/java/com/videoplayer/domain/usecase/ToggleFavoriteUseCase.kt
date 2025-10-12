package com.videoplayer.domain.usecase

import com.videoplayer.data.repository.ChannelRepository
import com.videoplayer.util.Result
import timber.log.Timber
import javax.inject.Inject

/**
 * Use case for toggling favorite status of a channel
 */
class ToggleFavoriteUseCase @Inject constructor(
    private val channelRepository: ChannelRepository
) {

    suspend operator fun invoke(channelUrl: String): Result<Boolean> {
        return try {
            Timber.d("Toggling favorite for channel: $channelUrl")
            channelRepository.toggleFavorite(channelUrl)
        } catch (e: Exception) {
            Timber.e(e, "Error toggling favorite")
            Result.Error(e)
        }
    }
}
