package com.videoplayer.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

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

        var showNoPlaylistDialog by remember { mutableStateOf(false) }
        var showChannelDialog by remember { mutableStateOf(false) }
        var channelInput by remember { mutableStateOf("") }

        // Show no-playlist dialog if needed
        var playlistCheckStarted by remember { mutableStateOf(false) }

        LaunchedEffect(viewState.hasChannels, viewState.isLoading) {
            if (viewState.isLoading) {
                playlistCheckStarted = true
            }
            if (playlistCheckStarted && !viewState.isLoading && !viewState.hasChannels && !hasShownNoPlaylistPrompt) {
                hasShownNoPlaylistPrompt = true
                showNoPlaylistDialog = true
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
            onGoToChannel = {
                if (viewState.hasChannels) {
                    channelInput = ""
                    showChannelDialog = true
                }
            },
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
            onLoadMoreEpgPast = { viewModel.loadMoreEpgPast() },
            onLoadMoreEpgFuture = { viewModel.loadMoreEpgFuture() },
            epgNotificationMessage = viewState.epgNotificationMessage,
            onClearEpgNotification = { viewModel.clearEpgNotification() },
            modifier = Modifier.fillMaxSize()
        )

        if (showNoPlaylistDialog) {
            AlertDialog(
                onDismissRequest = { showNoPlaylistDialog = false },
                title = { Text(text = getString(R.string.dialog_title_no_playlist)) },
                text = { Text(text = getString(R.string.dialog_message_no_playlist)) },
                confirmButton = {
                    TextButton(onClick = {
                        showNoPlaylistDialog = false
                        settingsLauncher.launch(Intent(context, SettingsActivity::class.java))
                    }) { Text(text = getString(R.string.button_open_settings)) }
                },
                dismissButton = {
                    TextButton(onClick = { showNoPlaylistDialog = false }) {
                        Text(text = getString(R.string.button_later))
                    }
                }
            )
        }

        if (showChannelDialog) {
            AlertDialog(
                onDismissRequest = { showChannelDialog = false },
                title = { Text(text = getString(R.string.dialog_title_go_to_channel)) },
                text = {
                    OutlinedTextField(
                        value = channelInput,
                        onValueChange = { new -> channelInput = new.filter { it.isDigit() }.take(4) },
                        label = { Text(getString(R.string.hint_channel_number, viewState.channels.size)) }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        channelInput.toIntOrNull()?.let { number ->
                            if (number in 1..viewState.channels.size) {
                                viewModel.playChannel(number - 1)
                            }
                        }
                        showChannelDialog = false
                    }) { Text(text = getString(R.string.button_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { showChannelDialog = false }) { Text(text = getString(R.string.button_cancel)) }
                }
            )
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
     * Show channel number dialog
     */
    private fun showChannelNumberDialog() { /* migrated to Compose */ }

    /**
     * Show dialog when no playlist is configured
     */
    private fun showNoPlaylistDialog() { /* migrated to Compose */ }

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



