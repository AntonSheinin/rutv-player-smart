package com.rutv.domain.usecase

import com.rutv.data.repository.ChannelRepository
import com.rutv.util.Result
import com.rutv.util.logDebug
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
            logDebug { "Toggling favorite for channel: $channelUrl" }
            channelRepository.toggleFavorite(channelUrl)
        } catch (e: Exception) {
            Timber.e(e, "Error toggling favorite")
            Result.Error(e)
        }
    }
}
