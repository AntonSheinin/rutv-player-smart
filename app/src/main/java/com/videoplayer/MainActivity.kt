package com.videoplayer

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.IOException

class MainActivity : AppCompatActivity() {
    
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    
    private val playlist = mutableListOf<VideoItem>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        playerView = findViewById(R.id.player_view)
        playlistRecyclerView = findViewById(R.id.playlist_container)
        
        setupPlaylist()
        setupRecyclerView()
        initializePlayer()
    }
    
    private fun setupPlaylist() {
        try {
            val m3u8Content = assets.open("playlist.m3u8").bufferedReader().use { it.readText() }
            val channels = M3U8Parser.parse(m3u8Content)
            
            if (channels.isNotEmpty()) {
                playlist.addAll(channels.map { channel ->
                    VideoItem(
                        title = channel.title,
                        url = channel.url,
                        isPlaying = false,
                        logo = channel.logo,
                        group = channel.group
                    )
                })
                Log.d("VideoPlayer", "Loaded ${channels.size} channels from M3U8 playlist")
            } else {
                loadDefaultPlaylist()
            }
        } catch (e: IOException) {
            Log.e("VideoPlayer", "Error loading M3U8 playlist", e)
            loadDefaultPlaylist()
        }
    }
    
    private fun loadDefaultPlaylist() {
        playlist.addAll(listOf(
            VideoItem(
                "Big Buck Bunny",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
            ),
            VideoItem(
                "Elephant Dream",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"
            ),
            VideoItem(
                "For Bigger Blazes",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
            ),
            VideoItem(
                "For Bigger Escape",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4"
            ),
            VideoItem(
                "For Bigger Fun",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4"
            ),
            VideoItem(
                "Sintel",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"
            )
        ))
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
