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
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {
    
    private var currentlyPlayingIndex = -1
    var selectedPosition = -1
    
    class PlaylistViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val logoImageView: ImageView = view.findViewById(R.id.channel_logo)
        val numberTextView: TextView = view.findViewById(R.id.channel_number)
        val titleTextView: TextView = view.findViewById(R.id.video_title)
        val groupTextView: TextView = view.findViewById(R.id.video_group)
        val statusTextView: TextView = view.findViewById(R.id.video_status)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.playlist_item, parent, false)
        return PlaylistViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val videoItem = playlist[position]
        
        holder.numberTextView.text = "${position + 1}"
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
        
        holder.itemView.isSelected = (position == currentlyPlayingIndex)
        holder.statusTextView.text = if (position == currentlyPlayingIndex) "▶ Playing" else ""
        
        holder.itemView.setOnClickListener {
            selectedPosition = position
            onItemClick(position)
        }
    }
    
    override fun getItemCount(): Int = playlist.size
    
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
