package com.videoplayer.presentation

import android.app.AlertDialog
import android.content.Intent
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import coil.Coil
import coil.compose.LocalImageLoader
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupFullscreen()

        setContent {
            RuTvTheme {
                MainScreen()
            }
        }

        Timber.d("MainActivity created with Compose UI")
    }

    @Composable
    private fun MainScreen() {
        val viewState by viewModel.viewState.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val imageLoader = remember(context) { Coil.imageLoader(context) }

        // Show no-playlist dialog if needed
        LaunchedEffect(viewState.hasChannels, viewState.isLoading) {
            if (!viewState.isLoading && !viewState.hasChannels && !hasShownNoPlaylistPrompt) {
                hasShownNoPlaylistPrompt = true
                showNoPlaylistDialog()
            }
        }

        CompositionLocalProvider(LocalImageLoader provides imageLoader) {
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
                modifier = Modifier.fillMaxSize()
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
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
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
