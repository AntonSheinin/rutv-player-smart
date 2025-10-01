package com.videoplayer

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : AppCompatActivity() {
    
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var btnLoadFile: Button
    private lateinit var btnLoadUrl: Button
    
    private val playlist = mutableListOf<VideoItem>()
    
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadPlaylistFromUri(it) }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        playerView = findViewById(R.id.player_view)
        playlistRecyclerView = findViewById(R.id.playlist_container)
        btnLoadFile = findViewById(R.id.btn_load_file)
        btnLoadUrl = findViewById(R.id.btn_load_url)
        
        setupButtons()
        setupRecyclerView()
    }
    
    private fun setupButtons() {
        btnLoadFile.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }
        
        btnLoadUrl.setOnClickListener {
            showUrlDialog()
        }
    }
    
    private fun showUrlDialog() {
        val input = EditText(this)
        input.hint = "Enter M3U/M3U8 URL"
        
        AlertDialog.Builder(this)
            .setTitle("Load Playlist from URL")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val url = input.text.toString()
                if (url.isNotBlank()) {
                    loadPlaylistFromUrl(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun loadPlaylistFromUri(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            content?.let { loadPlaylistContent(it) }
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Error loading playlist from URI", e)
            Toast.makeText(this, "Failed to load playlist: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun loadPlaylistFromUrl(urlString: String) {
        Toast.makeText(this, "Loading playlist from URL...", Toast.LENGTH_SHORT).show()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val content = URL(urlString).readText()
                withContext(Dispatchers.Main) {
                    loadPlaylistContent(content)
                }
            } catch (e: Exception) {
                Log.e("VideoPlayer", "Error loading playlist from URL", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to load URL: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun loadPlaylistContent(content: String) {
        val channels = M3U8Parser.parse(content)
        
        if (channels.isNotEmpty()) {
            playlist.clear()
            playlist.addAll(channels.map { channel ->
                VideoItem(
                    title = channel.title,
                    url = channel.url,
                    isPlaying = false,
                    logo = channel.logo,
                    group = channel.group
                )
            })
            
            playlistAdapter.notifyDataSetChanged()
            
            player?.release()
            initializePlayer()
            
            Toast.makeText(this, "Loaded ${channels.size} channels", Toast.LENGTH_SHORT).show()
            Log.d("VideoPlayer", "Loaded ${channels.size} channels from playlist")
        } else {
            Toast.makeText(this, "No valid channels found in playlist", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(playlist) { position ->
            player?.seekToDefaultPosition(position)
            player?.playWhenReady = true
        }
        
        playlistRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = playlistAdapter
        }
    }
    
    private fun initializePlayer() {
        if (playlist.isEmpty()) {
            Log.d("VideoPlayer", "Playlist is empty, player not initialized")
            return
        }
        
        player = ExoPlayer.Builder(this)
            .build()
            .apply {
                val mediaItems = playlist.map { videoItem ->
                    MediaItem.Builder()
                        .setUri(videoItem.url)
                        .setMediaId(videoItem.title)
                        .build()
                }
                
                setMediaItems(mediaItems)
                
                repeatMode = Player.REPEAT_MODE_ALL
                
                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        mediaItem?.let {
                            val currentIndex = currentMediaItemIndex
                            playlistAdapter.updateCurrentlyPlaying(currentIndex)
                            
                            when (reason) {
                                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                                    Log.d("VideoPlayer", "Auto transition to: ${it.mediaId}")
                                }
                                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                                    Log.d("VideoPlayer", "User selected: ${it.mediaId}")
                                }
                                else -> {
                                    Log.d("VideoPlayer", "Media item transition: ${it.mediaId}")
                                }
                            }
                        }
                    }
                    
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                Log.d("VideoPlayer", "Ready to play")
                            }
                            Player.STATE_BUFFERING -> {
                                Log.d("VideoPlayer", "Buffering...")
                            }
                            Player.STATE_ENDED -> {
                                Log.d("VideoPlayer", "Playback ended")
                            }
                            else -> {
                                Log.d("VideoPlayer", "Playback state changed")
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("VideoPlayer", "Player error: ${error.message}", error)
                        Toast.makeText(
                            this@MainActivity, 
                            "Playback error: ${error.message}", 
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        if (hasNextMediaItem()) {
                            seekToNext()
                            prepare()
                        }
                    }
                })
                
                prepare()
                playWhenReady = true
            }
        
        playerView.player = player
        playlistAdapter.updateCurrentlyPlaying(0)
    }
    
    override fun onStart() {
        super.onStart()
        if (player == null) {
            initializePlayer()
        }
    }
    
    override fun onStop() {
        super.onStop()
        player?.let {
            it.playWhenReady = false
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
