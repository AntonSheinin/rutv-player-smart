package com.videoplayer.presentation.adapter

import androidx.recyclerview.widget.DiffUtil
import com.videoplayer.data.model.Channel

/**
 * DiffUtil callback for efficient RecyclerView updates
 */
class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {

    override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
        return oldItem.url == newItem.url
    }

    override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
        return oldItem == newItem
    }

    /**
     * Optional: return a payload for partial updates
     */
    override fun getChangePayload(oldItem: Channel, newItem: Channel): Any? {
        return when {
            oldItem.isFavorite != newItem.isFavorite -> PAYLOAD_FAVORITE
            else -> null
        }
    }

    companion object {
        const val PAYLOAD_FAVORITE = "favorite"
    }
}
