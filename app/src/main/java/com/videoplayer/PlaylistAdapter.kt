package com.videoplayer

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
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
        
        var onSingleTapAction: (() -> Unit)? = null
        var onDoubleTapAction: (() -> Unit)? = null
        
        // GestureDetector must be created ONCE per ViewHolder, not recreated on every bind
        val gestureDetector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                android.util.Log.e("PlaylistAdapter", "onDown event received")
                return true  // MUST return true to continue gesture detection
            }
            
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                android.util.Log.e("PlaylistAdapter", "========== SINGLE TAP CONFIRMED ==========")
                onSingleTapAction?.invoke()
                return true
            }
            
            override fun onDoubleTap(e: MotionEvent): Boolean {
                android.util.Log.e("PlaylistAdapter", "========== DOUBLE TAP DETECTED ==========")
                onDoubleTapAction?.invoke()
                return true
            }
        })
        
        init {
            // Set up touch listener ONCE on the cardView
            cardView.setOnTouchListener { v, event ->
                android.util.Log.d("PlaylistAdapter", "Touch event: action=${event.action}")
                gestureDetector.onTouchEvent(event)
                false  // Don't consume, allow propagation
            }
        }
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
        
        // Set up tap actions for the GestureDetector (updates on each bind)
        holder.onSingleTapAction = {
            android.util.Log.e("PlaylistAdapter", "Single tap action for: ${videoItem.title}")
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) {
                val currentItem = displayList.getOrNull(currentPosition)
                if (currentItem != null && currentItem.tvgId.isNotBlank()) {
                    val currentActualIndex = playlist.indexOf(currentItem)
                    android.util.Log.e("PlaylistAdapter", "Calling onShowPrograms for: ${currentItem.title}, tvgId='${currentItem.tvgId}'")
                    onShowPrograms(currentActualIndex)
                } else {
                    android.util.Log.e("PlaylistAdapter", "No tvg-id for channel: ${currentItem?.title}")
                }
            }
        }
        
        holder.onDoubleTapAction = {
            android.util.Log.e("PlaylistAdapter", "Double tap action for: ${videoItem.title}")
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) {
                val currentItem = displayList.getOrNull(currentPosition)
                if (currentItem != null) {
                    val currentActualIndex = playlist.indexOf(currentItem)
                    selectedPosition = currentActualIndex
                    onChannelClick(currentActualIndex)
                }
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
}
