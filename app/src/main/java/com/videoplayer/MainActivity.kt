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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var btnLoadFile: Button
    private lateinit var btnLoadUrl: Button
    private lateinit var buttonContainer: LinearLayout
    private lateinit var debugLog: TextView
    
    private val playlist = mutableListOf<VideoItem>()
    private val debugMessages = mutableListOf<String>()
    private var bufferingStartTime: Long = 0
    private val BUFFERING_TIMEOUT_MS = 30000L
    private val bufferingCheckHandler = Handler(Looper.getMainLooper())
    private var bufferingCheckRunnable: Runnable? = null
    
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
        buttonContainer = findViewById(R.id.button_container)
        debugLog = findViewById(R.id.debug_log)
        
        addDebugMessage("App Started")
        
        setupButtons()
        setupRecyclerView()
        setupFullscreen()
    }
    
    private fun setupButtons() {
        btnLoadFile.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }
        
        btnLoadUrl.setOnClickListener {
            showUrlDialog()
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
                showUIElements()
            } else {
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
            Log.d("VideoPlayer", message)
        }
    }
    
    private fun hideUIElements() {
        buttonContainer.visibility = View.GONE
        playlistRecyclerView.visibility = View.GONE
        debugLog.visibility = View.GONE
    }
    
    private fun showUIElements() {
        buttonContainer.visibility = View.VISIBLE
        playlistRecyclerView.visibility = View.VISIBLE
        debugLog.visibility = View.VISIBLE
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
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
        
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    URL(urlString).readText()
                }
                loadPlaylistContent(content)
            } catch (e: Exception) {
                Log.e("VideoPlayer", "Error loading playlist from URL", e)
                Toast.makeText(this@MainActivity, "Failed to load URL: ${e.message}", Toast.LENGTH_LONG).show()
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
            player?.let { p ->
                if (p.currentMediaItemIndex != position) {
                    addDebugMessage("→ Switching to channel #${position + 1}")
                    p.seekTo(position, C.TIME_UNSET)
                    p.play()
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
        
        if (FfmpegLibrary.isAvailable()) {
            val version = FfmpegLibrary.getVersion()
            addDebugMessage("✓ FFmpeg: v$version (fallback mode)")
            addDebugMessage("✓ Built-in codecs first, FFmpeg if unsupported")
        } else {
            addDebugMessage("✗ FFmpeg: NOT LOADED")
            addDebugMessage("✗ Some formats may not work")
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
