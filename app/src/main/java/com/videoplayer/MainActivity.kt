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
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
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
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
class FfmpegRenderersFactory(
    context: Context,
    private val useFfmpegAudio: Boolean,
    private val useFfmpegVideo: Boolean
) : NextRenderersFactory(context) {
    
    init {
        setEnableDecoderFallback(true)
        forceEnableMediaCodecAsynchronousQueueing()
        setAllowedVideoJoiningTimeMs(10000)
        experimentalSetEnableMediaCodecVideoRendererPrewarming(false)
        experimentalSetParseAv1SampleDependencies(false)
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
        val videoMode = if (useFfmpegVideo) EXTENSION_RENDERER_MODE_PREFER else EXTENSION_RENDERER_MODE_OFF
        super.buildVideoRenderers(
            context,
            videoMode,
            mediaCodecSelector,
            true,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
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
        val audioMode = if (useFfmpegAudio) EXTENSION_RENDERER_MODE_PREFER else EXTENSION_RENDERER_MODE_OFF
        super.buildAudioRenderers(
            context,
            audioMode,
            mediaCodecSelector,
            true,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
    }
    
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
            .build()
    }
    
    override fun buildTextRenderers(
        context: Context,
        output: androidx.media3.exoplayer.text.TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
    }
}

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
    
    companion object {
        private var sharedBandwidthMeter: DefaultBandwidthMeter? = null
        
        fun getBandwidthMeter(context: Context): DefaultBandwidthMeter {
            if (sharedBandwidthMeter == null) {
                sharedBandwidthMeter = DefaultBandwidthMeter.Builder(context.applicationContext)
                    .setInitialBitrateEstimate(2800000L)
                    .build()
            }
            return sharedBandwidthMeter!!
        }
    }
    
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var playlistWrapper: LinearLayout
    private lateinit var playlistTitle: TextView
    private lateinit var btnClosePlaylist: ImageButton
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var debugLog: TextView
    private lateinit var debugLogScroll: android.widget.ScrollView
    private lateinit var btnAspectRatio: ImageButton
    private lateinit var btnOrientation: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnPlaylist: ImageButton
    private lateinit var btnFavorites: ImageButton
    private lateinit var btnGoToChannel: ImageButton
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
    private var playlistUserVisible = false
    private var showFavoritesOnly = false
    private var currentChannelUrl: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        playerView = findViewById(R.id.player_view)
        playlistRecyclerView = findViewById(R.id.playlist_container)
        playlistWrapper = findViewById(R.id.playlist_wrapper)
        playlistTitle = findViewById(R.id.playlist_title)
        btnClosePlaylist = findViewById(R.id.btn_close_playlist)
        debugLog = findViewById(R.id.debug_log)
        debugLogScroll = findViewById(R.id.debug_log_scroll)
        channelInfo = findViewById(R.id.channel_info)
        logo = findViewById(R.id.logo)
        
        btnAspectRatio = playerView.findViewById(R.id.btn_aspect_ratio)
        btnOrientation = playerView.findViewById(R.id.btn_orientation)
        btnSettings = playerView.findViewById(R.id.btn_settings)
        btnPlaylist = playerView.findViewById(R.id.btn_playlist)
        btnFavorites = playerView.findViewById(R.id.btn_favorites)
        btnGoToChannel = playerView.findViewById(R.id.btn_go_to_channel)
        
        loadPreferences()
        addDebugMessage("App Started")
        
        logo.visibility = View.GONE
        
        setupSettingsButton()
        setupAspectRatioButton()
        setupOrientationButton()
        setupPlaylistButton()
        setupFavoritesButton()
        setupGoToChannelButton()
        setupPlayerTapGesture()
        setupRecyclerView()
        setupClosePlaylistButton()
        setupFullscreen()
        
        lifecycleScope.launch {
            autoLoadPlaylist()
        }
    }
    
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        loadPreferences()
        lifecycleScope.launch {
            val hadPlayer = player != null
            val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val bufferSeconds = prefs.getInt(SettingsActivity.KEY_BUFFER_SECONDS, 15)
            val useFfmpegAudio = prefs.getBoolean(SettingsActivity.KEY_USE_FFMPEG_AUDIO, true)
            val useFfmpegVideo = prefs.getBoolean(SettingsActivity.KEY_USE_FFMPEG_VIDEO, false)
            
            addDebugMessage("Settings: Buffer=${bufferSeconds}s, FFmpeg Audio=${if (useFfmpegAudio) "ON" else "OFF"}, FFmpeg Video=${if (useFfmpegVideo) "ON" else "OFF"}")
            
            autoLoadPlaylist()
            
            if (hadPlayer && playlist.isNotEmpty()) {
                player?.release()
                player = null
                initializePlayer()
                addDebugMessage("✓ Player restarted with new settings")
            }
        }
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
        if (::debugLogScroll.isInitialized) {
            debugLogScroll.visibility = if (showDebugLog) {
                View.VISIBLE
            } else {
                View.GONE
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
                        if (it.length > 500000) {
                            addDebugMessage("⚠️ Playlist too large, clearing...")
                            prefs.edit().remove(SettingsActivity.KEY_PLAYLIST_CONTENT).apply()
                            Toast.makeText(this, "Saved playlist too large. Please use URL mode for large playlists.", Toast.LENGTH_LONG).show()
                            false
                        } else {
                            val contentHash = it.hashCode().toString()
                            val storedHash = ChannelStorage.getStoredPlaylistHash(this)
                            
                            if (contentHash == storedHash) {
                                val cachedChannels = ChannelStorage.loadChannels(this)
                                if (cachedChannels != null && cachedChannels.isNotEmpty()) {
                                    addDebugMessage("✓ Loading ${cachedChannels.size} channels from cache")
                                    playlist.clear()
                                    playlist.addAll(cachedChannels)
                                    playlistAdapter.notifyDataSetChanged()
                                    initializePlayer()
                                    return true
                                }
                            }
                            
                            addDebugMessage("Parsing playlist file")
                            loadPlaylistContent(it, contentHash)
                        }
                    } ?: false
                }
                SettingsActivity.TYPE_URL -> {
                    val url = prefs.getString(SettingsActivity.KEY_PLAYLIST_URL, null)
                    return url?.let {
                        addDebugMessage("Fetching playlist from URL")
                        val content = withContext(Dispatchers.IO) {
                            try {
                                URL(it).readText()
                            } catch (e: Exception) {
                                null
                            }
                        }
                        
                        content?.let { playlistContent ->
                            val contentHash = playlistContent.hashCode().toString()
                            val storedHash = ChannelStorage.getStoredPlaylistHash(this)
                            
                            if (contentHash == storedHash) {
                                val cachedChannels = ChannelStorage.loadChannels(this)
                                if (cachedChannels != null && cachedChannels.isNotEmpty()) {
                                    addDebugMessage("✓ Loading ${cachedChannels.size} channels from cache")
                                    playlist.clear()
                                    playlist.addAll(cachedChannels)
                                    playlistAdapter.notifyDataSetChanged()
                                    initializePlayer()
                                    return true
                                }
                            }
                            
                            addDebugMessage("Parsing playlist (content changed)")
                            loadPlaylistContent(playlistContent, contentHash)
                        } ?: run {
                            Toast.makeText(this, "Failed to load playlist from URL", Toast.LENGTH_LONG).show()
                            false
                        }
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
    
    private suspend fun loadPlaylistFromUrlWithResult(urlString: String, playlistHash: String = ""): Boolean {
        Toast.makeText(this, "Loading playlist from URL...", Toast.LENGTH_SHORT).show()
        
        return try {
            val content = withContext(Dispatchers.IO) {
                URL(urlString).readText()
            }
            loadPlaylistContent(content, playlistHash)
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
            
            currentChannelUrl?.let { url ->
                ChannelStorage.setAspectRatio(this, url, currentResizeMode)
                playlist.find { it.url == url }?.aspectRatio = currentResizeMode
            }
            
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
            videoRotation = if (videoRotation == 0f) {
                Toast.makeText(this, "Vertical", Toast.LENGTH_SHORT).show()
                270f
            } else {
                Toast.makeText(this, "Horizontal", Toast.LENGTH_SHORT).show()
                0f
            }
            
            val contentFrame = playerView.findViewById<android.widget.FrameLayout>(
                androidx.media3.ui.R.id.exo_content_frame
            )
            
            contentFrame?.apply {
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    if (child is android.view.TextureView) {
                        child.apply {
                            rotation = videoRotation
                            pivotX = width / 2f
                            pivotY = height / 2f
                            
                            if (videoRotation == 90f || videoRotation == 270f) {
                                val containerWidth = this@apply.width.toFloat()
                                val containerHeight = this@apply.height.toFloat()
                                val viewWidth = width.toFloat()
                                val viewHeight = height.toFloat()
                                
                                val scaleX = containerWidth / viewHeight
                                val scaleY = containerHeight / viewWidth
                                val scaleFactor = minOf(scaleX, scaleY)
                                
                                this.scaleX = scaleFactor
                                this.scaleY = scaleFactor
                            } else {
                                scaleX = 1f
                                scaleY = 1f
                            }
                        }
                        break
                    }
                }
            }
        }
    }
    
    private fun setupPlaylistButton() {
        btnPlaylist.setOnClickListener {
            playlistUserVisible = !playlistUserVisible
            showFavoritesOnly = false
            updatePlaylistView()
        }
    }
    
    private fun setupFavoritesButton() {
        btnFavorites.setOnClickListener {
            playlistUserVisible = !playlistUserVisible
            showFavoritesOnly = true
            updatePlaylistView()
        }
    }
    
    private fun setupGoToChannelButton() {
        btnGoToChannel.setOnClickListener {
            showChannelNumberDialog()
        }
    }
    
    private fun showChannelNumberDialog() {
        if (playlist.isEmpty()) {
            Toast.makeText(this, "No playlist loaded. Load a playlist in settings first.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Enter channel number (1-${playlist.size})"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        
        val switchChannel: () -> Unit = {
            val channelNumber = input.text.toString().toIntOrNull()
            if (channelNumber != null && channelNumber > 0 && channelNumber <= playlist.size) {
                val channelIndex = channelNumber - 1
                player?.let { p ->
                    if (p.currentMediaItemIndex != channelIndex) {
                        addDebugMessage("→ Jumping to channel #$channelNumber")
                        p.seekTo(channelIndex, C.TIME_UNSET)
                        p.prepare()
                        p.playWhenReady = true
                        p.play()
                        
                        currentChannelUrl = playlist.getOrNull(channelIndex)?.url
                        currentChannelUrl?.let { url ->
                            currentResizeMode = ChannelStorage.getAspectRatio(this, url)
                            playerView.resizeMode = currentResizeMode
                        }
                        
                        playlistUserVisible = false
                        playlistWrapper.visibility = View.GONE
                        hideUIElements()
                        updateChannelInfo()
                    }
                }
            } else {
                Toast.makeText(this, "Invalid channel number. Enter 1-${playlist.size}", Toast.LENGTH_SHORT).show()
            }
        }
        
        var currentDialog: AlertDialog? = null
        
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                switchChannel()
                currentDialog?.dismiss()
                true
            } else {
                false
            }
        }
        
        currentDialog = AlertDialog.Builder(this)
            .setTitle("Go to Channel")
            .setView(input)
            .setPositiveButton("OK") { dialogInterface, _ ->
                switchChannel()
                dialogInterface.dismiss()
            }
            .setNegativeButton("Cancel") { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            .create()
        
        currentDialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        currentDialog?.window?.setBackgroundDrawableResource(android.R.color.black)
        currentDialog?.show()
        
        input.requestFocus()
    }
    
    private fun updatePlaylistView() {
        if (playlistUserVisible) {
            val filteredPlaylist = if (showFavoritesOnly) {
                playlist.filter { it.isFavorite }
            } else {
                playlist
            }
            
            playlistTitle.text = if (showFavoritesOnly) "Favorites" else "Channels"
            playlistWrapper.visibility = View.VISIBLE
            playlistAdapter.updateFilter(filteredPlaylist, showFavoritesOnly)
        } else {
            playlistWrapper.visibility = View.GONE
        }
    }
    
    private fun setupPlayerTapGesture() {
    }
    
    private fun setupClosePlaylistButton() {
        btnClosePlaylist.setOnClickListener {
            playlistUserVisible = false
            updatePlaylistView()
        }
    }
    
    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) {
                logo.visibility = View.VISIBLE
                updateChannelInfo()
                showUIElements()
            } else {
                logo.visibility = View.GONE
                channelInfo.visibility = View.GONE
                hideUIElements()
            }
        })
    }
    
    private fun addDebugMessage(message: String) {
        runOnUiThread {
            debugMessages.add(message)
            if (debugMessages.size > 50) {
                debugMessages.removeAt(0)
            }
            debugLog.text = debugMessages.joinToString("\n")
            updateDebugLogVisibility()
            
            debugLogScroll.post {
                debugLogScroll.fullScroll(android.view.View.FOCUS_DOWN)
            }
            
            Log.d("VideoPlayer", message)
        }
    }
    
    private fun hideUIElements() {
        playlistUserVisible = false
        playlistWrapper.visibility = View.GONE
    }
    
    private fun showUIElements() {
        if (playlistUserVisible) {
            playlistWrapper.visibility = View.VISIBLE
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
    
    
    private fun loadPlaylistContent(content: String, playlistHash: String = ""): Boolean {
        try {
            val channels = M3U8Parser.parse(content)
            
            if (channels.isNotEmpty()) {
                ChannelStorage.clearAspectRatios(this)
                
                playlist.clear()
                playlist.addAll(channels.map { channel ->
                    VideoItem(
                        title = channel.title,
                        url = channel.url,
                        isPlaying = false,
                        logo = channel.logo,
                        group = channel.group,
                        isFavorite = ChannelStorage.isFavorite(this, channel.url),
                        aspectRatio = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    )
                })
                
                ChannelStorage.saveChannels(this, playlist, playlistHash)
                
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
        playlistAdapter = PlaylistAdapter(playlist, 
            onChannelClick = { position ->
                player?.let { p ->
                    if (p.currentMediaItemIndex != position) {
                        addDebugMessage("→ Switching to channel #${position + 1}")
                        p.seekTo(position, C.TIME_UNSET)
                        p.prepare()
                        p.playWhenReady = true
                        p.play()
                        
                        currentChannelUrl = playlist.getOrNull(position)?.url
                        currentChannelUrl?.let { url ->
                            currentResizeMode = ChannelStorage.getAspectRatio(this, url)
                            playerView.resizeMode = currentResizeMode
                        }
                        
                        playlistUserVisible = false
                        hideUIElements()
                        updateChannelInfo()
                    }
                }
            },
            onFavoriteClick = { position ->
                val channel = playlist.getOrNull(position)
                channel?.let {
                    it.isFavorite = !it.isFavorite
                    ChannelStorage.setFavorite(this, it.url, it.isFavorite)
                    playlistAdapter.notifyItemChanged(position)
                }
            }
        )
        
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
            val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val useFfmpegAudio = prefs.getBoolean(SettingsActivity.KEY_USE_FFMPEG_AUDIO, true)
            val useFfmpegVideo = prefs.getBoolean(SettingsActivity.KEY_USE_FFMPEG_VIDEO, false)
            val bufferSeconds = prefs.getInt(SettingsActivity.KEY_BUFFER_SECONDS, 15)
            
            val modes = mutableListOf<String>()
            if (useFfmpegAudio) modes.add("audio")
            if (useFfmpegVideo) modes.add("video")
            if (modes.isNotEmpty()) {
                addDebugMessage("✓ NextLib FFmpeg: ${modes.joinToString(", ")} decoder")
            } else {
                addDebugMessage("✓ Hardware decoders only")
            }
            
            addDebugMessage("✓ MediaCodec: Async mode with dedicated thread")
            
            addDebugMessage("✓ Buffer: ${bufferSeconds}s")
            
            if (playlist.size > 500) {
                addDebugMessage("⚠️ Large playlist (${playlist.size} channels) - may take time to load")
            }
        
        val renderersFactory = if (useFfmpegAudio || useFfmpegVideo) {
            FfmpegRenderersFactory(this, useFfmpegAudio, useFfmpegVideo)
        } else {
            DefaultRenderersFactory(this).apply {
                setEnableDecoderFallback(true)
                forceEnableMediaCodecAsynchronousQueueing()
                setAllowedVideoJoiningTimeMs(10000)
                experimentalSetEnableMediaCodecVideoRendererPrewarming(false)
                experimentalSetParseAv1SampleDependencies(false)
            }
        }
        
        val bandwidthMeter = getBandwidthMeter(this)
        
        val bufferMs = bufferSeconds * 1000
        val minBufferMs = maxOf(15000, bufferMs)
        val maxBufferMs = maxOf(50000, bufferMs)
        val bufferForPlaybackMs = 7500
        val bufferForPlaybackAfterRebufferMs = 10000
        
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
            .setTransferListener(bandwidthMeter)
            .setDefaultRequestProperties(mapOf(
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate",
                "Connection" to "keep-alive"
            ))
        
        val hlsExtractorFactory = DefaultHlsExtractorFactory(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
            true
        )
        
        val hlsMediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)
            .setExtractorFactory(hlsExtractorFactory)
            .setAllowChunklessPreparation(false)
            .setTimestampAdjusterInitializationTimeoutMs(30000)
        
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setForceHighestSupportedBitrate(false)
                .setAllowVideoMixedMimeTypeAdaptiveness(false)
                .setAllowVideoNonSeamlessAdaptiveness(false)
                .setAllowAudioMixedMimeTypeAdaptiveness(false)
                .setAllowAudioMixedSampleRateAdaptiveness(false)
                .setMaxVideoBitrate(10000000)
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                .setDisabledTextTrackSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                .setSelectUndeterminedTextLanguage(false)
                .build()
        }
        
        addDebugMessage("✓ Surface: TextureView (supports rotation transforms)")
        addDebugMessage("✓ Audio: Standard PCM mode (no float/params)")
        addDebugMessage("✓ HLS: Simplified flags (better AAC timestamp handling)")
        addDebugMessage("✓ Timestamp adjuster: 30s timeout (handle discontinuities)")
        addDebugMessage("✓ Frame rate: OFF (disable display rate changes)")
        addDebugMessage("✓ Buffer: Time-based priority for smooth playback")
        
        player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(hlsMediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
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
                    
                    override fun onDroppedVideoFrames(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        if (droppedFrames > 0) {
                            val fps = if (elapsedMs > 0) (droppedFrames * 1000f / elapsedMs) else 0f
                            addDebugMessage("⚠️ Dropped $droppedFrames frames in ${elapsedMs}ms (${String.format("%.1f", fps)} fps)")
                        }
                    }
                    
                    override fun onVideoFrameProcessingOffset(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        totalProcessingOffsetUs: Long,
                        frameCount: Int
                    ) {
                        if (frameCount > 0) {
                            val avgOffsetMs = totalProcessingOffsetUs / 1000f / frameCount
                            if (avgOffsetMs > 20) {
                                addDebugMessage("⏱️ Frame processing slow: ${String.format("%.1f", avgOffsetMs)}ms avg offset")
                            }
                        }
                    }
                })
                
                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        mediaItem?.let {
                            val currentIndex = currentMediaItemIndex
                            playlistAdapter.updateCurrentlyPlaying(currentIndex)
                            ChannelStorage.saveLastPlayedIndex(this@MainActivity, currentIndex)
                            
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
                        
                        if (errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                            addDebugMessage("  → Recovering: Seeking to live edge...")
                            seekToDefaultPosition()
                            prepare()
                            playWhenReady = true
                            Toast.makeText(
                                this@MainActivity,
                                "Stream fell behind - jumping to live",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this@MainActivity, 
                                "Playback failed: ${error.errorCodeName}\nSelect another channel", 
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                })
                
                val lastPlayedIndex = ChannelStorage.getLastPlayedIndex(this@MainActivity)
                if (lastPlayedIndex >= 0 && lastPlayedIndex < playlist.size) {
                    seekTo(lastPlayedIndex, C.TIME_UNSET)
                    addDebugMessage("⏩ Resuming from channel #${lastPlayedIndex + 1}")
                }
                
                prepare()
                playWhenReady = true
            }
        
            playerView.player = player
            val startIndex = player?.currentMediaItemIndex ?: 0
            playlistAdapter.updateCurrentlyPlaying(startIndex)
            
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
