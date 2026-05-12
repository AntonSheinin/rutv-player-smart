package com.rutv.domain.usecase

import com.rutv.data.model.Channel

enum class ChannelListMode {
    Full,
    Favorites,
    Category
}

/**
 * Filter channels by the explicitly selected channel list mode.
 */
fun filterChannels(
    channels: List<Channel>,
    channelListMode: ChannelListMode,
    selectedGroup: String?
): List<Channel> {
    return when (channelListMode) {
        ChannelListMode.Full -> channels
        ChannelListMode.Favorites -> channels.filter { it.isFavorite }
        ChannelListMode.Category -> {
            val normalizedGroup = selectedGroup?.trim().takeIf { !it.isNullOrBlank() }
                ?: return channels
            channels.filter { channel ->
                channel.allGroups.any { it.trim() == normalizedGroup }
            }
        }
    }
}
