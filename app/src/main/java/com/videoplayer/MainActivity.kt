package com.videoplayer

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.videoplayer.data.model.Channel
import com.videoplayer.presentation.adapter.ChannelListAdapter
import com.videoplayer.presentation.adapter.EpgListAdapter
import com.videoplayer.presentation.main.MainViewModel
import com.videoplayer.presentation.player.PlayerState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Main Activity - Refactored to use MVVM architecture
 * Reduced from 1,389 lines to ~400 lines by moving logic to ViewModel
 */
@UnstableApi
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    // UI Components
    private lateinit var playerView: PlayerView
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var playlistWrapper: LinearLayout
    private lateinit var playlistTitle: TextView
    private lateinit var btnClosePlaylist: ImageButton
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

    private lateinit var programsWrapper: LinearLayout
    private lateinit var programsRecyclerView: RecyclerView

    // Adapters
    private lateinit var channelAdapter: ChannelListAdapter
    private lateinit var epgAdapter: EpgListAdapter

    // Track if we've shown the no-playlist prompt
    private var hasShownNoPlaylistPrompt = false

    // Track last EPG loaded timestamp to trigger adapter refresh
    private var lastEpgLoadedTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupAdapters()
        setupButtons()
        setupFullscreen()
        observeViewModel()

        // Show controls initially so user can access settings
        playerView.showController()

        Timber.d("MainActivity created")
    }

    /**
     * Initialize all views
     */
    private fun initializeViews() {
        playerView = findViewById(R.id.player_view)
        playlistRecyclerView = findViewById(R.id.playlist_container)
        playlistWrapper = findViewById(R.id.playlist_wrapper)
        playlistTitle = findViewById(R.id.playlist_title)
        btnClosePlaylist = findViewById(R.id.btn_close_playlist)
        debugLog = findViewById(R.id.debug_log)
        debugLogScroll = findViewById(R.id.debug_log_scroll)
        channelInfo = findViewById(R.id.channel_info)
        logo = findViewById(R.id.logo)

        programsWrapper = findViewById(R.id.programs_wrapper)
        programsRecyclerView = findViewById(R.id.programs_container)

        btnAspectRatio = playerView.findViewById(R.id.btn_aspect_ratio)
        btnOrientation = playerView.findViewById(R.id.btn_orientation)
        btnSettings = playerView.findViewById(R.id.btn_settings)
        btnPlaylist = playerView.findViewById(R.id.btn_playlist)
        btnFavorites = playerView.findViewById(R.id.btn_favorites)
        btnGoToChannel = playerView.findViewById(R.id.btn_go_to_channel)

        logo.visibility = View.GONE
    }

    /**
     * Setup RecyclerView adapters
     */
    private fun setupAdapters() {
        // Channel list adapter
        channelAdapter = ChannelListAdapter(
            onChannelClick = { channel, position ->
                viewModel.playChannel(position)
            },
            onFavoriteClick = { channel, _ ->
                viewModel.toggleFavorite(channel.url)
            },
            onShowPrograms = { channel, position ->
                channelAdapter.updateEpgOpen(position)
                viewModel.showEpgForChannel(channel.tvgId)
            },
            getCurrentProgram = { tvgId ->
                viewModel.getCurrentProgramForChannel(tvgId)
            }
        )

        playlistRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.VERTICAL, false)
            adapter = channelAdapter
        }

        // EPG adapter
        epgAdapter = EpgListAdapter { program ->
            Timber.d("EPG program clicked: ${program.title}")
        }

        programsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.VERTICAL, false)
            adapter = epgAdapter
        }
    }

    /**
     * Setup all button click listeners
     */
    private fun setupButtons() {
        btnSettings.setOnClickListener {
            settingsLauncher.launch(android.content.Intent(this, SettingsActivity::class.java))
        }

        btnAspectRatio.setOnClickListener {
            viewModel.cycleAspectRatio()
        }

        btnOrientation.setOnClickListener {
            viewModel.toggleRotation()
        }

        btnPlaylist.setOnClickListener {
            viewModel.togglePlaylist()
        }

        btnFavorites.setOnClickListener {
            viewModel.toggleFavorites()
        }

        btnGoToChannel.setOnClickListener {
            showChannelNumberDialog()
        }

        btnClosePlaylist.setOnClickListener {
            viewModel.closePlaylist()
        }
    }

    /**
     * Settings launcher
     */
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Settings changed, reload playlist
        hasShownNoPlaylistPrompt = false // Reset flag to show prompt again if still no playlist
        viewModel.loadPlaylist(forceReload = false)
    }

    /**
     * Observe ViewModel state changes
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.viewState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    /**
     * Update UI based on state
     */
    private fun updateUI(state: com.videoplayer.presentation.main.MainViewState) {
        // Check if no playlist is configured and show prompt
        if (!state.isLoading && !state.hasChannels && !hasShownNoPlaylistPrompt) {
            hasShownNoPlaylistPrompt = true
            showNoPlaylistDialog()
        }

        // Update channel list
        if (state.hasChannels) {
            channelAdapter.submitList(state.filteredChannels)
        }

        // Refresh adapter if EPG was just loaded
        if (state.epgLoadedTimestamp > lastEpgLoadedTimestamp && state.epgLoadedTimestamp > 0) {
            lastEpgLoadedTimestamp = state.epgLoadedTimestamp
            Timber.d("EPG loaded, refreshing channel adapter to show EPG data")
            channelAdapter.notifyDataSetChanged() // Force refresh to show EPG info
        }

        // Update playlist visibility
        playlistWrapper.visibility = if (state.showPlaylist) View.VISIBLE else View.GONE
        playlistTitle.text = state.playlistTitle

        // Update EPG panel
        programsWrapper.visibility = if (state.showEpgPanel) View.VISIBLE else View.GONE
        if (state.showEpgPanel && state.epgPrograms.isNotEmpty()) {
            val currentProgramPosition = epgAdapter.updatePrograms(state.epgPrograms)
            if (currentProgramPosition >= 0) {
                programsRecyclerView.post {
                    val layoutManager = programsRecyclerView.layoutManager as? LinearLayoutManager
                    if (layoutManager != null) {
                        val screenHeight = programsRecyclerView.height
                        val itemHeight = programsRecyclerView.getChildAt(0)?.height ?: 100
                        val offset = (screenHeight / 2) - (itemHeight / 2)
                        layoutManager.scrollToPositionWithOffset(currentProgramPosition, offset)
                    }
                }
            }
        }

        // Update player view
        playerView.player = viewModel.getPlayer()
        playerView.resizeMode = state.currentResizeMode

        // Apply video rotation
        applyVideoRotation(state.videoRotation)

        // Update currently playing channel
        if (state.currentChannelIndex >= 0) {
            channelAdapter.updateCurrentlyPlaying(state.currentChannelIndex)
        }

        // Update channel info
        updateChannelInfo(state)

        // Update debug log
        updateDebugLog(state)

        // Handle player state
        handlePlayerState(state.playerState)
    }

    /**
     * Update channel info display
     */
    private fun updateChannelInfo(state: com.videoplayer.presentation.main.MainViewState) {
        state.currentChannel?.let { channel ->
            val channelText = "#${state.currentChannelIndex + 1} • ${channel.title}"
            channelInfo.text = if (state.currentProgram != null) {
                "$channelText\n${state.currentProgram.title}"
            } else {
                channelText
            }
            channelInfo.visibility = View.VISIBLE
        } ?: run {
            channelInfo.visibility = View.GONE
        }
    }

    /**
     * Update debug log
     */
    private fun updateDebugLog(state: com.videoplayer.presentation.main.MainViewState) {
        debugLogScroll.visibility = if (state.showDebugLog) View.VISIBLE else View.GONE

        if (state.showDebugLog) {
            if (state.debugMessages.isNotEmpty()) {
                debugLog.text = state.debugMessages.takeLast(100).joinToString("\n") { it.message }
                debugLogScroll.post {
                    debugLogScroll.fullScroll(View.FOCUS_DOWN)
                }
            } else {
                debugLog.text = "Debug Log\n(No messages yet)"
            }
        }
    }

    /**
     * Handle player state changes
     */
    private fun handlePlayerState(playerState: PlayerState) {
        when (playerState) {
            is PlayerState.Ready -> {
                // Player is ready
            }
            is PlayerState.Buffering -> {
                // Show buffering indicator if needed
            }
            is PlayerState.Error -> {
                Timber.e("Player error: ${playerState.message}")
            }
            is PlayerState.Idle -> {
                // Player idle
            }
            is PlayerState.Ended -> {
                // Playback ended
            }
        }
    }

    /**
     * Apply video rotation
     */
    private fun applyVideoRotation(rotation: Float) {
        val contentFrame = playerView.findViewById<android.widget.FrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame
        )

        contentFrame?.apply {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child is android.view.TextureView) {
                    child.apply {
                        this.rotation = rotation
                        pivotX = width / 2f
                        pivotY = height / 2f

                        if (rotation == 90f || rotation == 270f) {
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

    /**
     * Show channel number dialog
     */
    private fun showChannelNumberDialog() {
        val state = viewModel.viewState.value
        if (!state.hasChannels) return

        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Enter channel number (1-${state.channels.size})"
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
            if (channelNumber != null && channelNumber > 0 && channelNumber <= state.channels.size) {
                viewModel.playChannel(channelNumber - 1)
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

        currentDialog?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        currentDialog?.window?.setBackgroundDrawableResource(android.R.color.black)
        currentDialog?.show()

        input.requestFocus()
    }

    /**
     * Show dialog when no playlist is configured
     */
    private fun showNoPlaylistDialog() {
        AlertDialog.Builder(this)
            .setTitle("No Playlist Configured")
            .setMessage("Please go to Settings to configure your playlist source (URL or file) to start watching channels.")
            .setPositiveButton("Open Settings") { dialog, _ ->
                dialog.dismiss()
                settingsLauncher.launch(android.content.Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .create()
            .apply {
                window?.setBackgroundDrawableResource(android.R.color.black)
                show()
            }
    }

    /**
     * Setup fullscreen mode
     */
    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) {
                logo.visibility = View.VISIBLE
                updateChannelInfo(viewModel.viewState.value)
            } else {
                logo.visibility = View.GONE
                channelInfo.visibility = View.GONE
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }
}
