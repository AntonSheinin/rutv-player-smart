package com.rutv.domain.usecase

import com.rutv.data.model.Channel

/**
 * Filter channels based on favorites flag and optional group selection.
 */
fun filterChannels(
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
