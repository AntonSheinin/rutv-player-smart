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
        val favoriteButton: TextView = view.findViewById(R.id.favorite_button)
        val logoImageView: ImageView = view.findViewById(R.id.channel_logo)
        val numberTextView: TextView = view.findViewById(R.id.channel_number)
        val titleTextView: TextView = view.findViewById(R.id.video_title)
        val groupTextView: TextView = view.findViewById(R.id.video_group)
        val currentProgramTextView: TextView = view.findViewById(R.id.current_program)
        val statusTextView: TextView = view.findViewById(R.id.video_status)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.playlist_item, parent, false)
        return PlaylistViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val videoItem = displayList[position]
        val actualIndex = playlist.indexOf(videoItem)
        
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
        
        // Single tap = show programs, Double tap = play channel
        val gestureDetector = GestureDetector(holder.itemView.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                android.util.Log.d("PlaylistAdapter", "Single tap on channel: ${videoItem.title}, tvgId: '${videoItem.tvgId}'")
                if (videoItem.tvgId.isNotBlank()) {
                    android.util.Log.d("PlaylistAdapter", "Calling onShowPrograms for index: $actualIndex")
                    onShowPrograms(actualIndex)
                } else {
                    android.util.Log.d("PlaylistAdapter", "No tvg-id for channel: ${videoItem.title}")
                }
                return true
            }
            
            override fun onDoubleTap(e: MotionEvent): Boolean {
                android.util.Log.d("PlaylistAdapter", "Double tap on channel: ${videoItem.title}")
                selectedPosition = actualIndex
                onChannelClick(actualIndex)
                return true
            }
        })
        
        // Apply gesture detector to the whole channel item
        holder.itemView.setOnTouchListener { v, event ->
            android.util.Log.d("PlaylistAdapter", "Touch on item: ${event.action} - ${videoItem.title}")
            gestureDetector.onTouchEvent(event)
            true
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
