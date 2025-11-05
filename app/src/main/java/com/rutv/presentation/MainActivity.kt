package com.rutv.presentation

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.rutv.ui.theme.ruTvColors
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.rutv.R
import com.rutv.data.model.EpgProgram
import com.rutv.presentation.main.MainViewModel
import com.rutv.ui.mobile.screens.PlayerScreen
import com.rutv.ui.theme.RuTvTheme
import com.rutv.ui.shared.components.RemoteDialog
import com.rutv.util.DeviceHelper
import com.rutv.util.LocaleHelper
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import android.view.KeyEvent

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

    // State holder for PlayerScreen controls toggle
    private var toggleControlsCallback: (() -> Unit)? = null

    // State for Close App dialog (accessible from both composable and onKeyDown)
    private var showCloseAppDialogState: MutableState<Boolean>? = null

    // State for controls visibility
    private var areControlsVisible = false

    override fun attachBaseContext(newBase: Context) {
        // Load saved language from SharedPreferences (synchronous, safe)
        val localeCode = LocaleHelper.getSavedLanguage(newBase)
        languageBeforeSettings = localeCode // Initialize the tracking variable
        val context = LocaleHelper.setLocale(newBase, localeCode)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        // Switch from splash screen theme to regular theme
        setTheme(R.style.Theme_RuTV)

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

        // Close App dialog state (shared between composable and onKeyDown)
        val showCloseAppDialogState = remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            this@MainActivity.showCloseAppDialogState = showCloseAppDialogState
        }
        DisposableEffect(Unit) {
            onDispose {
                this@MainActivity.showCloseAppDialogState = null
            }
        }

        // Show no-playlist dialog if needed
        // Only show if there's no playlist source configured (not just if loading failed)
        var playlistCheckStarted by remember { mutableStateOf(false) }

        LaunchedEffect(viewState.hasChannels, viewState.isLoading, viewState.hasPlaylistSource, viewState.error) {
            if (viewState.isLoading) {
                playlistCheckStarted = true
            }

            // Dismiss dialog if channels become available
            if (showNoPlaylistDialog && viewState.hasChannels) {
                showNoPlaylistDialog = false
            }

            // Show dialog if:
            // 1. Loading has started and finished
            // 2. No channels loaded
            // 3. Either no playlist source OR playlist loading failed with error
            // 4. Haven't shown the prompt yet
            val shouldShowDialog = playlistCheckStarted &&
                !viewState.isLoading &&
                !viewState.hasChannels &&
                (!viewState.hasPlaylistSource || viewState.error != null) &&
                !hasShownNoPlaylistPrompt

            if (shouldShowDialog) {
                hasShownNoPlaylistPrompt = true
                showNoPlaylistDialog = true
            }
        }

        // Coil will automatically use the ImageLoader from RuTvApplication's ImageLoaderFactory
        // Store toggle controls callback
        var toggleControlsCallbackState by remember { mutableStateOf<(() -> Unit)?>(null) }
        toggleControlsCallback = toggleControlsCallbackState

        PlayerScreen(
            viewState = viewState,
            player = viewModel.getPlayer(),
            onPlayChannel = { index: Int -> viewModel.playChannel(index) },
            onToggleFavorite = { url: String -> viewModel.toggleFavorite(url) },
            onShowEpgForChannel = { tvgId: String -> viewModel.showEpgForChannel(tvgId) },
            onTogglePlaylist = { viewModel.togglePlaylist() },
            onRegisterToggleControls = { callback -> toggleControlsCallbackState = callback },
            onControlsVisibilityChanged = { visible -> areControlsVisible = visible },
            onToggleFavorites = { viewModel.toggleFavorites() },
            onClosePlaylist = { viewModel.closePlaylist() },
            onCloseEpgPanel = { viewModel.closeEpgPanel() },
            onCycleAspectRatio = { viewModel.cycleAspectRatio() },
            onOpenSettings = {
                // Save current language before opening settings
                languageBeforeSettings = LocaleHelper.getSavedLanguage(this@MainActivity)
                settingsLauncher.launch(Intent(context, SettingsActivity::class.java))
            },
            onGoToChannel = {
                if (viewState.hasChannels) {
                    channelInput = ""
                    showChannelDialog = true
                }
            },
            onShowProgramDetails = { program: EpgProgram -> viewModel.showProgramDetails(program) },
            onPlayArchiveProgram = { program: EpgProgram -> viewModel.playArchiveProgram(program) },
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
            onLogDebug = { message -> viewModel.logDebug(message) },
            modifier = Modifier.fillMaxSize()
        )

        if (showNoPlaylistDialog) {
            AlertDialog(
                onDismissRequest = { showNoPlaylistDialog = false },
                title = {
                    Text(
                        text = getString(R.string.dialog_title_no_playlist),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.ruTvColors.gold
                    )
                },
                text = {
                    Text(
                        text = getString(R.string.dialog_message_no_playlist),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.ruTvColors.textPrimary
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showNoPlaylistDialog = false
                            settingsLauncher.launch(Intent(context, SettingsActivity::class.java))
                        }
                    ) {
                        Text(
                            text = getString(R.string.button_open_settings),
                            color = MaterialTheme.ruTvColors.gold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        // Close the app when Exit is pressed
                        finishAffinity()
                    }) {
                        Text(
                            text = getString(R.string.button_exit),
                            color = MaterialTheme.ruTvColors.textPrimary
                        )
                    }
                },
                containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.border(
                    2.dp,
                    MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f),
                    RoundedCornerShape(16.dp)
                )
            )
        }

        if (showChannelDialog) {
            // Handle number pad input for channel selection
            LaunchedEffect(showChannelDialog) {
                // Number input is handled via MainActivity.onKeyDown() which maps KEYCODE_0-9
                // When playlist panel is open or channel dialog is shown, number keys append digits
            }

            RemoteDialog(
                onDismissRequest = { showChannelDialog = false },
                containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
                title = {
                    Text(
                        text = getString(R.string.dialog_title_go_to_channel),
                        color = MaterialTheme.ruTvColors.gold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    OutlinedTextField(
                        value = channelInput,
                        onValueChange = { new -> channelInput = new.filter { it.isDigit() }.take(4) },
                        label = { Text(getString(R.string.hint_channel_number, viewState.channels.size)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.ruTvColors.gold,
                            unfocusedBorderColor = MaterialTheme.ruTvColors.textDisabled,
                            focusedTextColor = MaterialTheme.ruTvColors.textPrimary,
                            unfocusedTextColor = MaterialTheme.ruTvColors.textPrimary,
                            focusedLabelColor = MaterialTheme.ruTvColors.gold,
                            unfocusedLabelColor = MaterialTheme.ruTvColors.textSecondary
                        )
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
                    }) {
                        Text(
                            text = getString(R.string.button_ok),
                            color = MaterialTheme.ruTvColors.gold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showChannelDialog = false }) {
                        Text(
                            text = getString(R.string.button_cancel),
                            color = MaterialTheme.ruTvColors.textPrimary
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.border(
                    2.dp,
                    MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f),
                    RoundedCornerShape(16.dp)
                )
            )
        }

        // Close App Dialog
        if (showCloseAppDialogState.value) {
            RemoteDialog(
                onDismissRequest = {
                    showCloseAppDialogState.value = false
                },
                containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f),
                title = {
                    Text(
                        text = getString(R.string.dialog_title_close_app),
                        color = MaterialTheme.ruTvColors.gold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Text(
                        text = getString(R.string.dialog_message_close_app),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.ruTvColors.textPrimary
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        finishAffinity()
                    }) {
                        Text(
                            text = getString(R.string.button_exit),
                            color = MaterialTheme.ruTvColors.gold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showCloseAppDialogState.value = false
                    }) {
                        Text(
                            text = getString(R.string.button_cancel),
                            color = MaterialTheme.ruTvColors.textPrimary
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.border(
                    2.dp,
                    MaterialTheme.ruTvColors.gold.copy(alpha = 0.7f),
                    RoundedCornerShape(16.dp)
                )
            )
        }
    }

    /**
     * Settings launcher
     */
    private var languageBeforeSettings: String = "en"

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Check if language was changed by comparing saved language
        val languageAfterSettings = LocaleHelper.getSavedLanguage(this)
        val languageChanged = languageBeforeSettings != languageAfterSettings

        Timber.d("Language check: before=$languageBeforeSettings, after=$languageAfterSettings, changed=$languageChanged")

        if (languageChanged) {
            // Language changed, recreate MainActivity to apply new locale
            Timber.d("Language changed, recreating MainActivity")
            recreate()
            return@registerForActivityResult
        }
        // Settings changed, reload playlist
        hasShownNoPlaylistPrompt = false // Reset flag to show prompt again if still no playlist
        viewModel.loadPlaylist(forceReload = false)
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

    /**
     * Handle remote control key events
     * Maps standard Android KeyEvent codes to app actions
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Detect and track input method
        val isRemote = event?.let { DeviceHelper.detectInputMethod(it) } ?: false
        if (isRemote && event != null) {
            DeviceHelper.updateLastInputMethod(event)
        }

        // Only handle remote keys if remote is active or detected
        if (isRemote && DeviceHelper.hasRemoteControl(this)) {
            when (keyCode) {
                // Channel navigation (direct, bypasses focus)
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    switchChannelUp()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    switchChannelDown()
                    return true
                }
                // Menu button - context dependent
                KeyEvent.KEYCODE_MENU -> {
                    // Toggle controls overlay or open settings
                    // This will be handled in PlayerScreen composable
                    return false // Let Compose handle it
                }
                // Number pad input for channel selection
                KeyEvent.KEYCODE_0,
                KeyEvent.KEYCODE_1,
                KeyEvent.KEYCODE_2,
                KeyEvent.KEYCODE_3,
                KeyEvent.KEYCODE_4,
                KeyEvent.KEYCODE_5,
                KeyEvent.KEYCODE_6,
                KeyEvent.KEYCODE_7,
                KeyEvent.KEYCODE_8,
                KeyEvent.KEYCODE_9 -> {
                    // Handle number input when channel dialog is shown
                    // Extract digit and append to channelInput
                    val digit = when (keyCode) {
                        KeyEvent.KEYCODE_0 -> '0'
                        KeyEvent.KEYCODE_1 -> '1'
                        KeyEvent.KEYCODE_2 -> '2'
                        KeyEvent.KEYCODE_3 -> '3'
                        KeyEvent.KEYCODE_4 -> '4'
                        KeyEvent.KEYCODE_5 -> '5'
                        KeyEvent.KEYCODE_6 -> '6'
                        KeyEvent.KEYCODE_7 -> '7'
                        KeyEvent.KEYCODE_8 -> '8'
                        KeyEvent.KEYCODE_9 -> '9'
                        else -> null
                    }
                    digit?.let {
                        // This will be handled in the composable via state
                        // For now, pass through to let Compose handle it
                    }
                    return false // Let Compose handle it - channelInput state will update via TextField
                }
                // Media controls - ExoPlayer handles these natively when player has focus
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    // ExoPlayer will handle these if player has focus
                    return false // Let ExoPlayer handle if focused, otherwise will fall through
                }
                // Info button - show program details
                KeyEvent.KEYCODE_INFO -> {
                    val viewState = viewModel.viewState.value
                    viewState.currentProgram?.let {
                        viewModel.showProgramDetails(it)
                        return true
                    }
                }
                // Button Y or Menu - context actions (favorite toggle, archive play, etc.)
                // This will be handled contextually based on what's focused
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_1 -> {
                    // Handle in composable based on focus - pass through
                    return false
                }
                // OK/DPAD_CENTER button - context dependent
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    val currentState = viewModel.viewState.value
                    // If channel list or EPG panel is open, let focused item handle OK
                    // (e.g., play channel from channel list, or play archive/show details in EPG)
                    if (currentState.showPlaylist || currentState.showEpgPanel) {
                        return false // Let ChannelListItem or EpgProgramItem handle it
                    }
                    // If controls are visible, let the focused control handle OK
                    // (e.g., custom control button or ExoPlayer control)
                    if (areControlsVisible) {
                        return false // Let CustomControlButtons or ExoPlayer handle it
                    }
                    // Otherwise, toggle controls when video is playing in full screen
                    toggleControlsCallback?.invoke()
                    return true
                }
                // Left arrow - open channel list from fullscreen view
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val currentState = viewModel.viewState.value
                    if (areControlsVisible) {
                        return false // Let ExoPlayer or CustomControlButtons handle LEFT navigation
                    }
                    // Open playlist only when in fullscreen (no panels visible)
                    if (!currentState.showPlaylist && !currentState.showEpgPanel && currentState.hasChannels) {
                        viewModel.openPlaylist()
                        return true
                    }
                    // If playlist or EPG is open, let Compose focus system handle LEFT navigation
                    return false
                }
                // Right arrow - open channel list AND EPG panel from fullscreen view
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val currentState = viewModel.viewState.value
                    if (areControlsVisible) {
                        return false // Let ExoPlayer handle RIGHT navigation
                    }
                    // Open both playlist and EPG when in fullscreen mode
                    if (!currentState.showPlaylist && !currentState.showEpgPanel) {
                        val tvgId = currentState.currentChannel?.tvgId
                        if (!tvgId.isNullOrBlank()) {
                            viewModel.openPlaylist()
                            viewModel.showEpgForChannel(tvgId)
                            return true
                        }
                    }
                    // If panels are already open, let Compose focus system handle RIGHT navigation
                    return false
                }
                // Up/Down arrows - navigate channel list/EPG if open, otherwise change channels
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val currentState = viewModel.viewState.value
                    viewModel.logDebug("▲ UP: list=${currentState.showPlaylist}, epg=${currentState.showEpgPanel}, ctrl=$areControlsVisible")
                    // If controls are visible, let them handle navigation
                    if (areControlsVisible) {
                        viewModel.logDebug("▲ UP → controls")
                        return false
                    }
                    // Only intercept UP when in fullscreen mode (no panels open)
                    if (!currentState.showPlaylist && !currentState.showEpgPanel) {
                        viewModel.logDebug("▲ UP → switch channel")
                        switchChannelUp()
                        return true
                    }
                    // DON'T handle UP when panels are open - let it propagate naturally
                    viewModel.logDebug("▲ UP → super (not intercepting)")
                    // Fall through to default handling
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val currentState = viewModel.viewState.value
                    viewModel.logDebug("▼ DOWN: list=${currentState.showPlaylist}, epg=${currentState.showEpgPanel}, ctrl=$areControlsVisible")
                    // If controls are visible, let them handle navigation
                    if (areControlsVisible) {
                        viewModel.logDebug("▼ DOWN → controls")
                        return false
                    }
                    // Only intercept DOWN when in fullscreen mode (no panels open)
                    if (!currentState.showPlaylist && !currentState.showEpgPanel) {
                        viewModel.logDebug("▼ DOWN → switch channel")
                        switchChannelDown()
                        return true
                    }
                    // DON'T handle DOWN when panels are open - let it propagate naturally
                    viewModel.logDebug("▼ DOWN → super (not intercepting)")
                    // Fall through to default handling
                }
                // BACK button - context dependent
                KeyEvent.KEYCODE_BACK -> {
                    val currentState = viewModel.viewState.value
                    // Close EPG panel first if visible
                    if (currentState.showEpgPanel) {
                        viewModel.closeEpgPanel()
                        return true
                    }
                    // Then close playlist if visible
                    if (currentState.showPlaylist) {
                        viewModel.closePlaylist()
                        return true
                    }
                    // Hide controls overlay if visible
                    if (areControlsVisible) {
                        toggleControlsCallback?.invoke()
                        return true
                    }
                    // If full screen playing, show Close App dialog
                    val dialogState = showCloseAppDialogState
                    if (dialogState != null) {
                        if (dialogState.value) {
                            // Dialog already shown, let it handle the back press
                            return false
                        } else {
                            dialogState.value = true
                            return true
                        }
                    }
                    return false
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    /**
     * Handle channel switching
     */
    private fun switchChannelUp() {
        val currentIndex = viewModel.viewState.value.currentChannelIndex
        val channels = viewModel.viewState.value.channels
        if (channels.isNotEmpty()) {
            val nextIndex = if (currentIndex < channels.size - 1) currentIndex + 1 else 0
            viewModel.playChannel(nextIndex)
        }
    }

    private fun switchChannelDown() {
        val currentIndex = viewModel.viewState.value.currentChannelIndex
        val channels = viewModel.viewState.value.channels
        if (channels.isNotEmpty()) {
            val prevIndex = if (currentIndex > 0) currentIndex - 1 else channels.size - 1
            viewModel.playChannel(prevIndex)
        }
    }
}


