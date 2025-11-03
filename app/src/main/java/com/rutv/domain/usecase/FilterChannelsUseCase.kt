package com.rutv.domain.usecase

import com.rutv.data.model.Channel
import javax.inject.Inject

/**
 * Use case for filtering channels based on favorites
 */
class FilterChannelsUseCase @Inject constructor() {
    /**
     * Filter channels based on favorites flag
     */
    operator fun invoke(
        channels: List<Channel>,
        showFavoritesOnly: Boolean
    ): List<Channel> {
        return if (showFavoritesOnly) {
            channels.filter { it.isFavorite }
        } else {
            channels
        }
    }
}
