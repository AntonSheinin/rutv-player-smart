package com.rutv.domain.usecase

import com.rutv.data.model.Channel
import javax.inject.Inject

/**
 * Use case for filtering channels based on favorites
 */
class FilterChannelsUseCase @Inject constructor() {
    /**
     * Filter channels based on favorites flag and optional group selection.
     */
    operator fun invoke(
        channels: List<Channel>,
        showFavoritesOnly: Boolean,
        selectedGroup: String?
    ): List<Channel> {
        val normalizedGroup = selectedGroup?.trim().takeIf { !it.isNullOrBlank() }
        return channels.filter { channel ->
            val matchesFavorite = !showFavoritesOnly || channel.isFavorite
            val matchesGroup = normalizedGroup == null || channel.allGroups.any { it.trim() == normalizedGroup }
            matchesFavorite && matchesGroup
        }
    }
}
