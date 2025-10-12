package com.videoplayer.presentation.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.card.MaterialCardView
import com.videoplayer.R
import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.EpgProgram
import com.videoplayer.util.Constants
import timber.log.Timber

/**
 * Refactored adapter using ListAdapter with DiffUtil for efficient updates
 */
class ChannelListAdapter(
    private val onChannelClick: (Channel, Int) -> Unit,
    private val onFavoriteClick: (Channel, Int) -> Unit,
    private val onShowPrograms: (Channel, Int) -> Unit,
    private val getCurrentProgram: (String) -> EpgProgram?
) : ListAdapter<Channel, ChannelListAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    private var currentlyPlayingIndex = -1
    private var epgOpenForIndex = -1

    class ChannelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: MaterialCardView = view.findViewById(R.id.channel_item_card)
        val favoriteButton: TextView = view.findViewById(R.id.favorite_button)
        val logoImageView: ImageView = view.findViewById(R.id.channel_logo)
        val numberTextView: TextView = view.findViewById(R.id.channel_number)
        val titleTextView: TextView = view.findViewById(R.id.video_title)
        val groupTextView: TextView = view.findViewById(R.id.video_group)
        val currentProgramTextView: TextView = view.findViewById(R.id.current_program)
        val statusTextView: TextView = view.findViewById(R.id.video_status)

        var lastClickTime = 0L
        var pendingAction: Runnable? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.playlist_item, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = getItem(position)

        Timber.d("Binding channel at position $position: ${channel.title}")

        // Clear tap state to prevent leaks when ViewHolder is reused
        holder.pendingAction?.let { holder.itemView.removeCallbacks(it) }
        holder.pendingAction = null
        holder.lastClickTime = 0L

        // Favorite button
        holder.favoriteButton.text = if (channel.isFavorite) "★" else "☆"
        holder.favoriteButton.setTextColor(
            if (channel.isFavorite)
                android.graphics.Color.parseColor("#FFD700")
            else
                android.graphics.Color.parseColor("#666666")
        )
        holder.favoriteButton.setOnClickListener {
            onFavoriteClick(channel, position)
        }

        // Channel number and info
        holder.numberTextView.text = "${position + 1}"
        holder.titleTextView.text = channel.title
        holder.groupTextView.text = channel.group

        // Channel logo
        if (channel.logo.isNotEmpty()) {
            holder.logoImageView.visibility = View.VISIBLE
            Glide.with(holder.itemView.context)
                .load(channel.logo)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .into(holder.logoImageView)
        } else {
            holder.logoImageView.visibility = View.VISIBLE
            holder.logoImageView.setImageResource(R.drawable.ic_channel_placeholder)
        }

        // Playing status
        holder.itemView.isSelected = (position == currentlyPlayingIndex)
        holder.statusTextView.text = if (position == currentlyPlayingIndex) "▶ Playing" else ""

        // EPG open background
        if (position == epgOpenForIndex) {
            holder.cardView.setCardBackgroundColor(android.graphics.Color.parseColor("#3A3A00"))
        } else {
            holder.cardView.setCardBackgroundColor(android.graphics.Color.parseColor("#2a2a2a"))
        }

        // Current program from EPG
        if (channel.hasEpg) {
            val currentProgram = getCurrentProgram(channel.tvgId)
            holder.currentProgramTextView.visibility = View.VISIBLE
            holder.currentProgramTextView.text = currentProgram?.title ?: "no program"
        } else {
            holder.currentProgramTextView.visibility = View.GONE
        }

        // Click listener with double-tap detection
        holder.cardView.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition == RecyclerView.NO_POSITION) return@setOnClickListener

            val currentChannel = getItem(currentPosition)
            val currentTime = System.currentTimeMillis()
            val timeSinceLastClick = currentTime - holder.lastClickTime

            // Remove any pending single-tap action
            holder.pendingAction?.let { holder.itemView.removeCallbacks(it) }

            if (timeSinceLastClick < Constants.DOUBLE_TAP_DELAY_MS) {
                // DOUBLE TAP - play channel immediately
                Timber.d("Double tap: Playing ${currentChannel.title}")
                holder.lastClickTime = 0
                holder.pendingAction = null
                onChannelClick(currentChannel, currentPosition)
            } else {
                // SINGLE TAP - show EPG after delay (in case of double tap)
                Timber.d("Single tap: Scheduling EPG for ${currentChannel.title}")
                holder.lastClickTime = currentTime

                holder.pendingAction = Runnable {
                    if (currentChannel.hasEpg) {
                        Timber.d("Showing programs for: ${currentChannel.title}")
                        onShowPrograms(currentChannel, currentPosition)
                    } else {
                        Timber.d("No EPG for channel: ${currentChannel.title}")
                    }
                }
                holder.itemView.postDelayed(holder.pendingAction!!, Constants.DOUBLE_TAP_DELAY_MS)
            }
        }
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            // Handle partial updates
            val channel = getItem(position)
            for (payload in payloads) {
                when (payload) {
                    ChannelDiffCallback.PAYLOAD_FAVORITE -> {
                        holder.favoriteButton.text = if (channel.isFavorite) "★" else "☆"
                        holder.favoriteButton.setTextColor(
                            if (channel.isFavorite)
                                android.graphics.Color.parseColor("#FFD700")
                            else
                                android.graphics.Color.parseColor("#666666")
                        )
                    }
                }
            }
        }
    }

    fun updateCurrentlyPlaying(index: Int) {
        val previousIndex = currentlyPlayingIndex
        currentlyPlayingIndex = index

        if (previousIndex >= 0 && previousIndex < itemCount) {
            notifyItemChanged(previousIndex)
        }
        if (currentlyPlayingIndex >= 0 && currentlyPlayingIndex < itemCount) {
            notifyItemChanged(currentlyPlayingIndex)
        }
    }

    fun updateEpgOpen(index: Int) {
        val previousIndex = epgOpenForIndex
        epgOpenForIndex = index

        if (previousIndex >= 0 && previousIndex < itemCount) {
            notifyItemChanged(previousIndex)
        }
        if (index >= 0 && index < itemCount) {
            notifyItemChanged(index)
        }
    }

    override fun onViewRecycled(holder: ChannelViewHolder) {
        super.onViewRecycled(holder)
        // Clean up pending actions to prevent stray callbacks
        holder.pendingAction?.let { holder.itemView.removeCallbacks(it) }
        holder.pendingAction = null
        holder.lastClickTime = 0L
    }
}
