package com.videoplayer

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.pm.ActivityInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

@OptIn(UnstableApi::class)
class FfmpegRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }
    
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(true)
            .build()
    }
    
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: android.os.Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        if (FfmpegLibrary.isAvailable()) {
            out.add(
                FfmpegAudioRenderer(
                    eventHandler,
                    eventListener,
                    audioSink
                )
            )
            Log.d("VideoPlayer", "✓ FFmpeg AUDIO renderer added - ALL audio formats")
        } else {
            Log.e("VideoPlayer", "✗ FFmpeg library NOT available!")
        }
    }
    
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        if (FfmpegLibrary.isAvailable()) {
            try {
                val videoRendererClass = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegVideoRenderer")
                val constructor = videoRendererClass.getConstructor(
                    Long::class.javaPrimitiveType,
                    android.os.Handler::class.java,
                    androidx.media3.exoplayer.video.VideoRendererEventListener::class.java,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                )
                val renderer = constructor.newInstance(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                    enableDecoderFallback,
                    30.0f
                ) as Renderer
                
                out.add(renderer)
                Log.d("VideoPlayer", "✓ FFmpeg VIDEO renderer added FIRST - ALL video via FFmpeg")
            } catch (e: ClassNotFoundException) {
                Log.w("VideoPlayer", "FfmpegVideoRenderer class not found in library")
            } catch (e: NoSuchMethodException) {
                Log.e("VideoPlayer", "FfmpegVideoRenderer constructor mismatch: ${e.message}", e)
            } catch (e: Exception) {
                Log.e("VideoPlayer", "Error instantiating FFmpeg video renderer", e)
            }
        }
        
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
    }
    
    override fun buildTextRenderers(
        context: Context,
        output: androidx.media3.exoplayer.text.TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
    }
    
    companion object {
        private const val MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 50
    }
}

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
    
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var debugLog: TextView
    private lateinit var btnAspectRatio: ImageButton
    private lateinit var btnOrientation: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnPlaylist: ImageButton
    private lateinit var channelInfo: TextView
    private lateinit var logo: ImageView
    
    private val playlist = mutableListOf<VideoItem>()
    private val debugMessages = mutableListOf<String>()
    private var bufferingStartTime: Long = 0
    private val BUFFERING_TIMEOUT_MS = 30000L
    private val bufferingCheckHandler = Handler(Looper.getMainLooper())
    private var bufferingCheckRunnable: Runnable? = null
    
    private var currentResizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var videoRotation = 0f
    private var showDebugLog = true
    private var playlistUserVisible = true
    private var lastPlaylistHash = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        playerView = findViewById(R.id.player_view)
        playlistRecyclerView = findViewById(R.id.playlist_container)
        debugLog = findViewById(R.id.debug_log)
        btnAspectRatio = findViewById(R.id.btn_aspect_ratio)
        btnOrientation = findViewById(R.id.btn_orientation)
        btnSettings = findViewById(R.id.btn_settings)
        btnPlaylist = findViewById(R.id.btn_playlist)
        channelInfo = findViewById(R.id.channel_info)
        logo = findViewById(R.id.logo)
        
        loadPreferences()
        addDebugMessage("App Started")
        
        logo.visibility = View.VISIBLE
        
        setupSettingsButton()
        setupAspectRatioButton()
        setupOrientationButton()
        setupPlaylistButton()
        setupPlayerTapGesture()
        setupRecyclerView()
        setupFullscreen()
        
        lifecycleScope.launch {
            if (autoLoadPlaylist()) {
                val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val playlistType = prefs.getString(SettingsActivity.KEY_PLAYLIST_TYPE, null)
                lastPlaylistHash = when (playlistType) {
                    SettingsActivity.TYPE_FILE -> prefs.getString(SettingsActivity.KEY_PLAYLIST_CONTENT, "")?.hashCode().toString()
                    SettingsActivity.TYPE_URL -> prefs.getString(SettingsActivity.KEY_PLAYLIST_URL, "")?.hashCode().toString()
                    else -> ""
                }
            }
        }
    }
    
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        loadPreferences()
        checkAndReloadPlaylist()
    }
    
    private fun setupSettingsButton() {
        btnSettings.setOnClickListener {
            settingsLauncher.launch(android.content.Intent(this, SettingsActivity::class.java))
        }
    }
    
    private fun loadPreferences() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        showDebugLog = prefs.getBoolean(SettingsActivity.KEY_SHOW_DEBUG_LOG, true)
        updateDebugLogVisibility()
    }
    
    private fun updateDebugLogVisibility() {
        if (::debugLog.isInitialized && ::playlistRecyclerView.isInitialized) {
            debugLog.visibility = if (showDebugLog && playlistRecyclerView.visibility == View.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }
    
    private fun checkAndReloadPlaylist() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val playlistType = prefs.getString(SettingsActivity.KEY_PLAYLIST_TYPE, null)
        
        val currentHash = when (playlistType) {
            SettingsActivity.TYPE_FILE -> prefs.getString(SettingsActivity.KEY_PLAYLIST_CONTENT, "")?.hashCode().toString()
            SettingsActivity.TYPE_URL -> prefs.getString(SettingsActivity.KEY_PLAYLIST_URL, "")?.hashCode().toString()
            else -> ""
        }
        
        if (currentHash != lastPlaylistHash) {
            lifecycleScope.launch {
                if (autoLoadPlaylist()) {
                    lastPlaylistHash = currentHash
                }
            }
        }
    }
    
    private suspend fun autoLoadPlaylist(): Boolean {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val playlistType = prefs.getString(SettingsActivity.KEY_PLAYLIST_TYPE, null)
        
        try {
            when (playlistType) {
                SettingsActivity.TYPE_FILE -> {
                    val content = prefs.getString(SettingsActivity.KEY_PLAYLIST_CONTENT, null)
                    return content?.let { 
                        addDebugMessage("Auto-loading saved playlist file")
                        if (it.length > 500000) {
                            addDebugMessage("⚠️ Playlist too large, clearing...")
                            prefs.edit().remove(SettingsActivity.KEY_PLAYLIST_CONTENT).apply()
                            Toast.makeText(this, "Saved playlist too large. Please use URL mode for large playlists.", Toast.LENGTH_LONG).show()
                            false
                        } else {
                            loadPlaylistContent(it)
                        }
                    } ?: false
                }
                SettingsActivity.TYPE_URL -> {
                    val url = prefs.getString(SettingsActivity.KEY_PLAYLIST_URL, null)
                    return url?.let {
                        addDebugMessage("Auto-loading playlist from URL")
                        loadPlaylistFromUrlWithResult(it)
                    } ?: false
                }
                else -> {
                    addDebugMessage("No saved playlist - tap ⚙ to configure")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Error auto-loading playlist", e)
            addDebugMessage("✗ Failed to auto-load playlist")
            prefs.edit().clear().apply()
            Toast.makeText(this, "Cleared corrupted playlist. Please reload.", Toast.LENGTH_LONG).show()
            return false
        }
    }
    
    private suspend fun loadPlaylistFromUrlWithResult(urlString: String): Boolean {
        Toast.makeText(this, "Loading playlist from URL...", Toast.LENGTH_SHORT).show()
        
        return try {
            val content = withContext(Dispatchers.IO) {
                URL(urlString).readText()
            }
            loadPlaylistContent(content)
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Error loading playlist from URL", e)
            Toast.makeText(this@MainActivity, "Failed to load URL: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }
    
    private fun setupAspectRatioButton() {
        btnAspectRatio.setOnClickListener {
            currentResizeMode = when (currentResizeMode) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> 
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> 
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> 
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            
            playerView.resizeMode = currentResizeMode
            
            val modeText = when (currentResizeMode) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> "FIT"
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "FILL"
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "ZOOM"
                else -> "FIT"
            }
            
            Toast.makeText(this, "Aspect ratio: $modeText", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupOrientationButton() {
        btnOrientation.setOnClickListener {
            videoRotation = when (videoRotation) {
                0f -> {
                    Toast.makeText(this, "Video rotated 90°", Toast.LENGTH_SHORT).show()
                    90f
                }
                90f -> {
                    Toast.makeText(this, "Video rotated 180°", Toast.LENGTH_SHORT).show()
                    180f
                }
                180f -> {
                    Toast.makeText(this, "Video rotated 270°", Toast.LENGTH_SHORT).show()
                    270f
                }
                else -> {
                    Toast.makeText(this, "Video rotation reset", Toast.LENGTH_SHORT).show()
                    0f
                }
            }
            playerView.videoSurfaceView?.apply {
                rotation = videoRotation
                pivotX = width / 2f
                pivotY = height / 2f
            }
        }
    }
    
    private fun setupPlaylistButton() {
        btnPlaylist.setOnClickListener {
            playlistUserVisible = !playlistUserVisible
            if (playlistUserVisible) {
                playlistRecyclerView.visibility = View.VISIBLE
                updateDebugLogVisibility()
            } else {
                playlistRecyclerView.visibility = View.GONE
                debugLog.visibility = View.GONE
            }
        }
    }
    
    private fun setupPlayerTapGesture() {
    }
    
    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) {
                btnPlaylist.visibility = View.VISIBLE
                btnAspectRatio.visibility = View.VISIBLE
                btnOrientation.visibility = View.VISIBLE
                btnSettings.visibility = View.VISIBLE
                logo.visibility = View.VISIBLE
                updateChannelInfo()
                showUIElements()
            } else {
                btnPlaylist.visibility = View.GONE
                btnAspectRatio.visibility = View.GONE
                btnOrientation.visibility = View.GONE
                btnSettings.visibility = View.GONE
                logo.visibility = View.GONE
                channelInfo.visibility = View.GONE
                hideUIElements()
            }
        })
    }
    
    private fun addDebugMessage(message: String) {
        runOnUiThread {
            debugMessages.add(message)
            if (debugMessages.size > 10) {
                debugMessages.removeAt(0)
            }
            debugLog.text = debugMessages.joinToString("\n")
            updateDebugLogVisibility()
            Log.d("VideoPlayer", message)
        }
    }
    
    private fun hideUIElements() {
        playlistRecyclerView.visibility = View.GONE
        debugLog.visibility = View.GONE
    }
    
    private fun showUIElements() {
        if (playlistUserVisible) {
            playlistRecyclerView.visibility = View.VISIBLE
            updateDebugLogVisibility()
        }
    }
    
    private fun updateChannelInfo() {
        if (::playlistAdapter.isInitialized && playlistAdapter.selectedPosition >= 0) {
            val item = playlist.getOrNull(playlistAdapter.selectedPosition)
            item?.let {
                channelInfo.text = "#${playlistAdapter.selectedPosition + 1} • ${it.title}"
                channelInfo.visibility = View.VISIBLE
            }
        } else {
            channelInfo.visibility = View.GONE
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    
    
    private fun loadPlaylistContent(content: String): Boolean {
        try {
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
                player = null
                
                initializePlayer()
                
                Toast.makeText(this, "Loaded ${channels.size} channels", Toast.LENGTH_SHORT).show()
                addDebugMessage("✓ Loaded ${channels.size} channels")
                Log.d("VideoPlayer", "Loaded ${channels.size} channels from playlist")
                return true
            } else {
                Toast.makeText(this, "No valid channels found in playlist", Toast.LENGTH_LONG).show()
                return false
            }
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Error loading playlist content", e)
            addDebugMessage("✗ Error loading playlist: ${e.message}")
            Toast.makeText(this, "Error loading playlist: ${e.message}", Toast.LENGTH_LONG).show()
            return false
        }
    }
    
    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(playlist) { position ->
            player?.let { p ->
                if (p.currentMediaItemIndex != position) {
                    addDebugMessage("→ Switching to channel #${position + 1}")
                    p.seekTo(position, C.TIME_UNSET)
                    p.prepare()
                    p.playWhenReady = true
                    p.play()
                    
                    playlistUserVisible = false
                    hideUIElements()
                    updateChannelInfo()
                }
            }
        }
        
        playlistRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.VERTICAL, false)
            adapter = playlistAdapter
        }
    }
    
    private fun initializePlayer() {
        if (playlist.isEmpty()) {
            Log.d("VideoPlayer", "Playlist is empty, player not initialized")
            return
        }
        
        try {
            if (FfmpegLibrary.isAvailable()) {
                val version = FfmpegLibrary.getVersion()
                addDebugMessage("✓ FFmpeg: v$version (fallback mode)")
                addDebugMessage("✓ Built-in codecs first, FFmpeg if unsupported")
            } else {
                addDebugMessage("✗ FFmpeg: NOT LOADED")
                addDebugMessage("✗ Some formats may not work")
            }
            
            if (playlist.size > 500) {
                addDebugMessage("⚠️ Large playlist (${playlist.size} channels) - may take time to load")
            }
        
        val renderersFactory = FfmpegRenderersFactory(this)
        
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                3000,
                15000,
                1500,
                2000
            )
            .build()
        
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
            .setDefaultRequestProperties(mapOf(
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate",
                "Connection" to "keep-alive"
            ))
        
        val hlsExtractorFactory = DefaultHlsExtractorFactory(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
            true
        )
        
        val hlsMediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)
            .setExtractorFactory(hlsExtractorFactory)
            .setAllowChunklessPreparation(false)
        
        addDebugMessage("✓ HLS extractor: Aggressive MPEG audio detection enabled")
        addDebugMessage("✓ HTTP: User-Agent and headers configured")
        
        player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(hlsMediaSourceFactory)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
            .apply {
                val mediaItems = playlist.map { videoItem ->
                    MediaItem.Builder()
                        .setUri(videoItem.url)
                        .setMediaId(videoItem.title)
                        .build()
                }
                
                setMediaItems(mediaItems)
                
                addDebugMessage("📺 Loaded ${mediaItems.size} channels into player")
                
                repeatMode = Player.REPEAT_MODE_ALL
                
                addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onAudioDecoderInitialized(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long
                    ) {
                        addDebugMessage("🔊 Audio decoder: $decoderName")
                    }
                    
                    override fun onVideoDecoderInitialized(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long
                    ) {
                        addDebugMessage("🎬 Video decoder: $decoderName")
                    }
                })
                
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
                    
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        addDebugMessage("📋 Tracks detected:")
                        
                        var hasAudio = false
                        var hasVideo = false
                        
                        for (trackGroup in tracks.groups) {
                            val type = trackGroup.type
                            val typeStr = when (type) {
                                C.TRACK_TYPE_VIDEO -> {
                                    hasVideo = true
                                    "VIDEO"
                                }
                                C.TRACK_TYPE_AUDIO -> {
                                    hasAudio = true
                                    "AUDIO"
                                }
                                C.TRACK_TYPE_TEXT -> "TEXT"
                                else -> "OTHER($type)"
                            }
                            
                            addDebugMessage("  $typeStr: ${trackGroup.length} track(s)")
                            
                            for (i in 0 until trackGroup.length) {
                                val format = trackGroup.getTrackFormat(i)
                                val supported = trackGroup.isTrackSupported(i)
                                val selected = trackGroup.isTrackSelected(i)
                                
                                addDebugMessage("    [$i] ${format.sampleMimeType} - support=$supported, selected=$selected")
                                
                                if (type == C.TRACK_TYPE_AUDIO) {
                                    addDebugMessage("       ${format.channelCount}ch @ ${format.sampleRate}Hz, bitrate=${format.bitrate}")
                                    
                                    if (!supported) {
                                        addDebugMessage("       ⚠️ AUDIO NOT SUPPORTED BY ANY RENDERER!")
                                    }
                                } else if (type == C.TRACK_TYPE_VIDEO) {
                                    addDebugMessage("       ${format.width}x${format.height} @ ${format.frameRate}fps")
                                }
                            }
                        }
                        
                        if (!hasAudio) {
                            addDebugMessage("  ⚠️ NO AUDIO TRACKS IN STREAM!")
                        }
                    }
                    
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                val channelName = currentMediaItem?.mediaId ?: "Unknown"
                                addDebugMessage("▶ Playing: $channelName")
                                
                                val vFormat = videoFormat
                                val aFormat = audioFormat
                                
                                if (vFormat != null) {
                                    addDebugMessage("  📺 Video: ${vFormat.sampleMimeType} (${vFormat.width}x${vFormat.height})")
                                }
                                
                                if (aFormat != null) {
                                    addDebugMessage("  🔊 Audio: ${aFormat.sampleMimeType} (${aFormat.channelCount}ch @ ${aFormat.sampleRate}Hz)")
                                } else {
                                    addDebugMessage("  ⚠️ NO AUDIO FORMAT ACTIVE!")
                                }
                                
                                stopBufferingCheck()
                            }
                            Player.STATE_BUFFERING -> {
                                if (bufferingStartTime == 0L) {
                                    bufferingStartTime = System.currentTimeMillis()
                                    addDebugMessage("⏳ Buffering...")
                                    startBufferingCheck()
                                }
                            }
                            Player.STATE_ENDED -> {
                                addDebugMessage("⏹ Playback ended")
                                showUIElements()
                                stopBufferingCheck()
                            }
                            Player.STATE_IDLE -> {
                                stopBufferingCheck()
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: PlaybackException) {
                        val channelName = currentMediaItem?.mediaId ?: "Unknown"
                        val errorMsg = error.message ?: "Unknown error"
                        
                        addDebugMessage("✗ Error: $channelName")
                        addDebugMessage("  → $errorMsg")
                        
                        if (error.cause != null) {
                            addDebugMessage("  → Cause: ${error.cause?.message}")
                            Log.e("VideoPlayer", "Error cause:", error.cause)
                        }
                        
                        val errorCode = error.errorCode
                        addDebugMessage("  → Code: ${error.errorCodeName} ($errorCode)")
                        
                        stopBufferingCheck()
                        
                        Toast.makeText(
                            this@MainActivity, 
                            "Playback failed: ${error.errorCodeName}\nSelect another channel", 
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
                
                prepare()
                playWhenReady = true
            }
        
            playerView.player = player
            playlistAdapter.updateCurrentlyPlaying(0)
            
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Error initializing player", e)
            addDebugMessage("✗ Player init failed: ${e.message}")
            Toast.makeText(this, "Failed to initialize player: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun startBufferingCheck() {
        cancelBufferingCheckCallbacks()
        bufferingCheckRunnable = object : Runnable {
            override fun run() {
                player?.let { p ->
                    if (p.playbackState == Player.STATE_BUFFERING && bufferingStartTime > 0) {
                        val bufferingDuration = System.currentTimeMillis() - bufferingStartTime
                        if (bufferingDuration > BUFFERING_TIMEOUT_MS) {
                            addDebugMessage("⚠ Buffering timeout (${bufferingDuration/1000}s)")
                            addDebugMessage("→ Stream may be unavailable")
                            stopBufferingCheck()
                            p.playWhenReady = false
                            Toast.makeText(
                                this@MainActivity,
                                "Channel not responding - please try another",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            bufferingCheckHandler.postDelayed(this, 1000)
                        }
                    }
                }
            }
        }
        bufferingCheckHandler.postDelayed(bufferingCheckRunnable!!, 1000)
    }
    
    private fun cancelBufferingCheckCallbacks() {
        bufferingCheckRunnable?.let {
            bufferingCheckHandler.removeCallbacks(it)
            bufferingCheckRunnable = null
        }
    }
    
    private fun stopBufferingCheck() {
        cancelBufferingCheckCallbacks()
        bufferingStartTime = 0
    }
    
    override fun onStart() {
        super.onStart()
        if (player == null) {
            initializePlayer()
        }
    }
    
    override fun onStop() {
        super.onStop()
        stopBufferingCheck()
        player?.playWhenReady = false
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopBufferingCheck()
        player?.release()
        player = null
    }
}
