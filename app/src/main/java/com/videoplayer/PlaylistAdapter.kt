package com.videoplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class PlaylistAdapter(
    private val playlist: List<VideoItem>,
    private val onChannelClick: (Int) -> Unit,
    private val onFavoriteClick: (Int) -> Unit,
    private val onShowPrograms: (Int) -> Unit,
    private val epgService: EpgService?
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {
    
    private var currentlyPlayingIndex = -1
    var selectedPosition = -1
    private var displayList: List<VideoItem> = playlist
    private var showingFavoritesOnly = false
    
    class PlaylistViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.channel_item_card)
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
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.playlist_item, parent, false)
        return PlaylistViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val videoItem = displayList[position]
        val actualIndex = playlist.indexOf(videoItem)
        
        android.util.Log.d("PlaylistAdapter", "onBindViewHolder called for position: $position, channel: ${videoItem.title}")
        
        // CRITICAL: Clear tap state to prevent leaks when ViewHolder is reused without recycling
        holder.pendingAction?.let { holder.itemView.removeCallbacks(it) }
        holder.pendingAction = null
        holder.lastClickTime = 0L
        
        holder.favoriteButton.text = if (videoItem.isFavorite) "★" else "☆"
        holder.favoriteButton.setTextColor(
            if (videoItem.isFavorite) 
                android.graphics.Color.parseColor("#FFD700")
            else 
                android.graphics.Color.parseColor("#666666")
        )
        
        holder.favoriteButton.setOnClickListener {
            onFavoriteClick(actualIndex)
        }
        
        holder.numberTextView.text = "${actualIndex + 1}"
        holder.titleTextView.text = videoItem.title
        holder.groupTextView.text = videoItem.group
        
        if (videoItem.logo.isNotEmpty()) {
            holder.logoImageView.visibility = View.VISIBLE
            Glide.with(holder.itemView.context)
                .load(videoItem.logo)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .into(holder.logoImageView)
        } else {
            holder.logoImageView.visibility = View.VISIBLE
            holder.logoImageView.setImageResource(R.drawable.ic_channel_placeholder)
        }
        
        holder.itemView.isSelected = (actualIndex == currentlyPlayingIndex)
        holder.statusTextView.text = if (actualIndex == currentlyPlayingIndex) "▶ Playing" else ""
        
        // Update current program from EPG
        if (videoItem.tvgId.isNotBlank() && epgService != null) {
            val currentProgram = epgService.getCurrentProgram(videoItem.tvgId)
            if (currentProgram != null) {
                holder.currentProgramTextView.visibility = View.VISIBLE
                holder.currentProgramTextView.text = currentProgram.title
            } else {
                holder.currentProgramTextView.visibility = View.GONE
            }
        } else {
            holder.currentProgramTextView.visibility = View.GONE
        }
        
        // Simple click listener with double-tap detection
        holder.cardView.setOnClickListener {
            android.util.Log.e("TAP_DEBUG", "========== CARD CLICKED: ${videoItem.title} ==========")
            
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition == RecyclerView.NO_POSITION) {
                android.util.Log.e("TAP_DEBUG", "NO_POSITION - returning")
                return@setOnClickListener
            }
            
            val currentItem = displayList.getOrNull(currentPosition)
            if (currentItem == null) {
                android.util.Log.e("TAP_DEBUG", "currentItem is null - returning")
                return@setOnClickListener
            }
            
            val currentActualIndex = playlist.indexOf(currentItem)
            android.util.Log.e("TAP_DEBUG", "Position: $currentPosition, ActualIndex: $currentActualIndex")
            
            val currentTime = System.currentTimeMillis()
            val timeSinceLastClick = currentTime - holder.lastClickTime
            android.util.Log.e("TAP_DEBUG", "Time since last click: ${timeSinceLastClick}ms")
            
            // Remove any pending single-tap action
            holder.pendingAction?.let { 
                android.util.Log.e("TAP_DEBUG", "Removing pending action")
                holder.itemView.removeCallbacks(it) 
            }
            
            if (timeSinceLastClick < 300) {
                // DOUBLE TAP - play channel immediately
                android.util.Log.e("TAP_DEBUG", "========== DOUBLE TAP: Playing ${currentItem.title} ==========")
                holder.lastClickTime = 0
                holder.pendingAction = null  // Clear state after double tap
                selectedPosition = currentActualIndex
                onChannelClick(currentActualIndex)
            } else {
                // SINGLE TAP - show EPG after delay (in case of double tap)
                android.util.Log.e("TAP_DEBUG", "========== SINGLE TAP: Scheduling EPG for ${currentItem.title} ==========")
                android.util.Log.e("TAP_DEBUG", "tvgId: '${currentItem.tvgId}'")
                holder.lastClickTime = currentTime
                
                holder.pendingAction = Runnable {
                    android.util.Log.e("TAP_DEBUG", "========== RUNNABLE EXECUTING for ${currentItem.title} ==========")
                    if (currentItem.tvgId.isNotBlank()) {
                        android.util.Log.e("TAP_DEBUG", "Calling onShowPrograms for: ${currentItem.title}, tvgId='${currentItem.tvgId}'")
                        onShowPrograms(currentActualIndex)
                        android.util.Log.e("TAP_DEBUG", "onShowPrograms called!")
                    } else {
                        android.util.Log.e("TAP_DEBUG", "No tvg-id for channel: ${currentItem.title}")
                    }
                }
                android.util.Log.e("TAP_DEBUG", "Posting delayed runnable (300ms)")
                holder.itemView.postDelayed(holder.pendingAction!!, 300)
            }
        }
    }
    
    override fun getItemCount(): Int = displayList.size
    
    fun updateFilter(filteredList: List<VideoItem>, favoritesOnly: Boolean) {
        displayList = filteredList
        showingFavoritesOnly = favoritesOnly
        notifyDataSetChanged()
    }
    
    fun updateCurrentlyPlaying(index: Int) {
        val previousIndex = currentlyPlayingIndex
        currentlyPlayingIndex = index
        selectedPosition = index
        
        if (previousIndex >= 0) {
            notifyItemChanged(previousIndex)
        }
        notifyItemChanged(currentlyPlayingIndex)
    }
    
    fun updateError(index: Int, error: String) {
        if (index == currentlyPlayingIndex) {
            notifyItemChanged(index)
        }
    }
    
    override fun onViewRecycled(holder: PlaylistViewHolder) {
        super.onViewRecycled(holder)
        // Clean up pending actions to prevent stray callbacks on recycled views
        holder.pendingAction?.let { holder.itemView.removeCallbacks(it) }
        holder.pendingAction = null
        holder.lastClickTime = 0L
    }
}
