package com.videoplayer.presentation

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.videoplayer.R
import com.videoplayer.presentation.main.MainViewModel
import com.videoplayer.ui.screens.PlayerScreen
import com.videoplayer.ui.theme.RuTvTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * Main Activity - Refactored to use Jetpack Compose
 * Modern MVVM architecture with Compose UI
 */
@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Track if we've shown the no-playlist prompt
    private var hasShownNoPlaylistPrompt = false
    private var timeChangeReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Switch from splash screen theme to regular theme
        setTheme(R.style.Theme_VideoPlayer)

        setupFullscreen()

        setContent {
            RuTvTheme {
                MainScreen()
            }
        }

        Timber.d("MainActivity created with Compose UI")
    }

    override fun onStart() {
        super.onStart()
        registerTimeChangeReceiver()
    }

    override fun onStop() {
        unregisterTimeChangeReceiver()
        super.onStop()
    }

    @Composable
    private fun MainScreen() {
        val viewState by viewModel.viewState.collectAsStateWithLifecycle()
        val context = LocalContext.current

        // Show no-playlist dialog if needed
        var playlistCheckStarted by remember { mutableStateOf(false) }

        LaunchedEffect(viewState.hasChannels, viewState.isLoading) {
            if (viewState.isLoading) {
                playlistCheckStarted = true
            }
            if (playlistCheckStarted && !viewState.isLoading && !viewState.hasChannels && !hasShownNoPlaylistPrompt) {
                hasShownNoPlaylistPrompt = true
                showNoPlaylistDialog()
            }
        }

        // Coil will automatically use the ImageLoader from RuTvApplication's ImageLoaderFactory
        PlayerScreen(
            viewState = viewState,
            player = viewModel.getPlayer(),
            onPlayChannel = { index -> viewModel.playChannel(index) },
            onToggleFavorite = { url -> viewModel.toggleFavorite(url) },
            onShowEpgForChannel = { tvgId -> viewModel.showEpgForChannel(tvgId) },
            onTogglePlaylist = { viewModel.togglePlaylist() },
            onToggleFavorites = { viewModel.toggleFavorites() },
            onClosePlaylist = { viewModel.closePlaylist() },
            onCycleAspectRatio = { viewModel.cycleAspectRatio() },
            onToggleRotation = { viewModel.toggleRotation() },
            onOpenSettings = { settingsLauncher.launch(Intent(context, SettingsActivity::class.java)) },
            onGoToChannel = { showChannelNumberDialog() },
            onShowProgramDetails = { program -> viewModel.showProgramDetails(program) },
            onPlayArchiveProgram = { program -> viewModel.playArchiveProgram(program) },
            onReturnToLive = { viewModel.returnToLive() },
            onRestartPlayback = { viewModel.restartCurrentPlayback() },
            onSeekBack = { viewModel.seekBackTenSeconds() },
            onSeekForward = { viewModel.seekForwardTenSeconds() },
            onPausePlayback = { viewModel.pausePlayback() },
            onResumePlayback = { viewModel.resumePlayback() },
            onArchivePromptContinue = { viewModel.continueArchiveFromPrompt() },
            onArchivePromptBackToLive = { viewModel.dismissArchivePrompt() },
            onCloseProgramDetails = { viewModel.closeProgramDetails() },
            modifier = Modifier.fillMaxSize()
        )
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
     * Show channel number dialog
     */
    private fun showChannelNumberDialog() {
        val state = viewModel.viewState.value
        if (!state.hasChannels) return

        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.hint_channel_number, state.channels.size)
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setBackgroundColor("#1A1A1A".toColorInt())
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }

        val switchChannel: () -> Unit = {
            input.text.toString().toIntOrNull()?.let { channelNumber ->
                if (channelNumber in 1..state.channels.size) {
                    viewModel.playChannel(channelNumber - 1)
                }
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
            .setTitle(getString(R.string.dialog_title_go_to_channel))
            .setView(input)
            .setPositiveButton(getString(R.string.button_ok)) { dialogInterface, _ ->
                switchChannel()
                dialogInterface.dismiss()
            }
            .setNegativeButton(getString(R.string.button_cancel)) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            .create()

        currentDialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        currentDialog.window?.setBackgroundDrawableResource(android.R.color.black)
        currentDialog.show()

        input.requestFocus()
    }

    /**
     * Show dialog when no playlist is configured
     */
    private fun showNoPlaylistDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_no_playlist))
            .setMessage(getString(R.string.dialog_message_no_playlist))
            .setPositiveButton(getString(R.string.button_open_settings)) { dialog, _ ->
                dialog.dismiss()
                settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(getString(R.string.button_later)) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .create()
            .apply {
                window?.setBackgroundDrawableResource(android.R.color.black)
                show()
            }
    }

    private fun registerTimeChangeReceiver() {
        if (timeChangeReceiver != null) return

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                Timber.i("System time broadcast received: $action")
                viewModel.onSystemTimeOrTimezoneChanged(action)
            }
        }

        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        timeChangeReceiver = receiver
        Timber.d("Registered system time change receiver")
    }

    private fun unregisterTimeChangeReceiver() {
        val receiver = timeChangeReceiver ?: return
        runCatching { unregisterReceiver(receiver) }
            .onFailure { Timber.w(it, "Failed to unregister time change receiver") }
        timeChangeReceiver = null
        Timber.d("Unregistered system time change receiver")
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



