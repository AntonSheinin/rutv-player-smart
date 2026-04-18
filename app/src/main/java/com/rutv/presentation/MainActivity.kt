package com.rutv.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.view.WindowManager
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rutv.R
import com.rutv.presentation.main.MainViewModel
import com.rutv.ui.mobile.screens.PlayerScreen
import com.rutv.ui.mobile.screens.PlayerUiActions
import com.rutv.ui.mobile.screens.rememberPlayerUiState
import com.rutv.ui.shared.components.RemotePressLifecycle
import com.rutv.ui.theme.RuTvTheme
import com.rutv.util.DeviceHelper
import com.rutv.util.LocaleHelper
import com.rutv.util.logDebug
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import android.view.KeyEvent
import android.content.res.Configuration
import kotlinx.coroutines.delay

/**
 * App main entry activity.
 *
 * Responsibilities:
 * - Hosts the Compose UI (`PlayerScreen`) and wires it to [MainViewModel].
 * - Applies immersive fullscreen configuration (especially important for TV devices).
 * - Handles *remote control* key events that should bypass Compose focus in “fullscreen playback”
 *   mode (channel up/down, open panels, back-to-close behavior).
 * - Listens to system time/timezone broadcasts and informs the ViewModel so EPG caches remain correct.
 *
 * Key input model (important for maintainers):
 * - Compose normally handles DPAD navigation via focus, so we only intercept keys when needed.
 * - When panels/controls are visible, we largely defer to Compose to avoid fighting the focus system.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Track if we've shown the no-playlist prompt
    private var hasShownNoPlaylistPrompt = false
    private var timeChangeReceiver: BroadcastReceiver? = null

    // State holder for PlayerScreen controls toggle
    private var toggleControlsCallback: (() -> Unit)? = null
    private var openChannelDialogCallback: (() -> Unit)? = null

    // Controls visibility is stored in viewModel.viewState.areControlsVisible
    private val fullscreenOkPress = RemotePressLifecycle()

    override fun attachBaseContext(newBase: Context) {
        // Locale must be applied before resources are loaded; we read synchronously.
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

        // Remote mode:
        // - TV/STB devices should default to remote mode immediately (fast check).
        // - Hardware enumeration for remotes can be slow on some STBs, so do it off the main thread.
        val isTvUiMode = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        val isLeanback = packageManager.hasSystemFeature("android.software.leanback")
        if (isTvUiMode || isLeanback) {
            DeviceHelper.setForceRemoteMode(true)
        }
        // Warm up remote detection in background (for non-TV devices and for caching).
        Thread {
            runCatching {
                val hasRemote = DeviceHelper.hasRemoteControl(applicationContext)
                if (hasRemote) {
                    DeviceHelper.setForceRemoteMode(true)
                }
            }
        }.apply { name = "RemoteDetect"; isDaemon = true }.start()

        // Switch from splash screen theme to regular theme
        setTheme(R.style.Theme_RuTV)

        setupFullscreen()

        setContent {
            RuTvTheme {
                MainScreen()
            }
        }

        logDebug { "MainActivity created with Compose UI" }
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
        var showGroupDialog by remember { mutableStateOf(false) }
        var channelInput by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            // Signal ViewModel after the first frame so startup player init can be deferred
            // outside the critical "activity displayed" path.
            withFrameNanos { }
            // Give the enter transition one more beat on slower STBs before kicking off
            // playlist/db work that can compete for startup CPU.
            delay(250L)
            viewModel.onStartupUiReady()
        }

        // Show no-playlist dialog if needed.
        //
        // Important: do NOT rely on observing `isLoading=true` first.
        // On a clean install, playlist init can complete extremely fast (PlaylistSource.None),
        // and Compose might start collecting state after the `isLoading` transition already happened.
        // That would prevent the prompt from ever showing.
        LaunchedEffect(viewState.hasChannels, viewState.isLoading, viewState.hasPlaylistSource, viewState.error) {
            // Dismiss dialog if channels become available
            if (showNoPlaylistDialog && viewState.hasChannels) {
                showNoPlaylistDialog = false
            }

            val shouldShowDialog =
                !viewState.isLoading &&
                !viewState.hasChannels &&
                (!viewState.hasPlaylistSource || viewState.error != null) &&
                !hasShownNoPlaylistPrompt

            if (shouldShowDialog) {
                hasShownNoPlaylistPrompt = true
                showNoPlaylistDialog = true
            }
        }

        val playerUiState = rememberPlayerUiState(viewState)
        val openChannelDialog = {
            if (viewState.filteredChannels.isNotEmpty() && !showChannelDialog) {
                channelInput = ""
                showChannelDialog = true
            }
        }
        DisposableEffect(openChannelDialog) {
            openChannelDialogCallback = openChannelDialog
            onDispose {
                if (openChannelDialogCallback === openChannelDialog) {
                    openChannelDialogCallback = null
                }
            }
        }
        val playerActions = PlayerUiActions(
            onPlayChannel = { index -> viewModel.playChannel(index) },
            onToggleFavorite = { url -> viewModel.toggleFavorite(url) },
            onShowEpgForChannel = { tvgId -> viewModel.showEpgForChannel(tvgId) },
            onTogglePlaylist = { viewModel.togglePlaylist() },
            onToggleFavorites = { viewModel.toggleFavorites() },
            onClosePlaylist = { viewModel.closePlaylist() },
            onCloseEpgPanel = { viewModel.closeEpgPanel() },
            onCycleAspectRatio = { viewModel.cycleAspectRatio() },
            onOpenSettings = {
                languageBeforeSettings = LocaleHelper.getSavedLanguage(this@MainActivity)
                settingsLauncher.launch(Intent(context, SettingsActivity::class.java))
            },
            onShowChannelGroups = {
                if (playerUiState.hasChannels) {
                    showGroupDialog = true
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
            onClearEpgNotification = { viewModel.clearEpgNotification() },
            onUpdatePlaylistScrollIndex = { index -> viewModel.updatePlaylistScrollIndex(index) },
            onRequestMoreChannels = { index -> viewModel.requestMoreChannels(index) },
            onEnsureEpgDateRange = { start, end -> viewModel.ensureEpgForDateRange(start, end) },
            onVisibleChannelsChanged = { tvgIds -> viewModel.onVisibleChannelsChanged(tvgIds) }
        )

        PlayerScreen(
            uiState = playerUiState,
            player = viewModel.getPlayer(),
            actions = playerActions,
            onRegisterToggleControls = { callback -> toggleControlsCallback = callback },
            onControlsVisibilityChanged = { visible -> viewModel.setControlsVisible(visible) },
            modifier = Modifier.fillMaxSize()
        )

        NoPlaylistDialog(
            show = showNoPlaylistDialog,
            onDismiss = { showNoPlaylistDialog = false },
            onOpenSettings = {
                showNoPlaylistDialog = false
                settingsLauncher.launch(Intent(context, SettingsActivity::class.java))
            },
            onExitApp = { finishAffinity() }
        )

        val onConfirmChannel = {
            channelInput.toIntOrNull()?.let { number ->
                if (number in 1..viewState.filteredChannels.size) {
                    viewModel.playChannel(number - 1)
                }
            }
            showChannelDialog = false
        }

        GoToChannelDialog(
            show = showChannelDialog,
            channelInput = channelInput,
            channelCount = viewState.filteredChannels.size,
            onChannelInputChange = { channelInput = it },
            onConfirm = onConfirmChannel,
            onDismiss = { showChannelDialog = false }
        )

        val channelGroups = remember(viewState.channels, showGroupDialog) {
            if (!showGroupDialog) {
                emptyList()
            } else {
                viewState.channels
                    .flatMap { it.allGroups }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
            }
        }

        ChannelGroupDialog(
            show = showGroupDialog,
            groups = channelGroups,
            selectedGroup = viewState.selectedGroup,
            onSelectGroup = { group ->
                viewModel.setChannelGroupFilter(group)
            },
            onReset = {
                viewModel.resetChannelGroupFilter()
            },
            onDismiss = { showGroupDialog = false }
        )

        CloseAppDialog(
            show = viewState.showCloseAppDialog,
            onConfirmExit = { finishAffinity() },
            onDismiss = { viewModel.setShowCloseAppDialog(false) }
        )
    }

    /**
     * Settings launcher
     */
    private var languageBeforeSettings: String = "en"

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Check if language was changed by comparing saved language
        val languageAfterSettings = LocaleHelper.getSavedLanguage(this)
        val languageChanged = languageBeforeSettings != languageAfterSettings

        logDebug { "Language check: before=$languageBeforeSettings, after=$languageAfterSettings, changed=$languageChanged" }

        if (languageChanged) {
            // Language changed, recreate MainActivity to apply new locale
            logDebug { "Language changed, recreating MainActivity" }
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
                // Delegate to ViewModel which decides whether caches need to be invalidated.
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
        logDebug { "Registered system time change receiver" }
    }

    private fun unregisterTimeChangeReceiver() {
        val receiver = timeChangeReceiver ?: return
        runCatching { unregisterReceiver(receiver) }
            .onFailure { Timber.w(it, "Failed to unregister time change receiver") }
        timeChangeReceiver = null
        logDebug { "Unregistered system time change receiver" }
    }

    /**
     * Setup fullscreen mode
     */
    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
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
     * Single source of truth for remote key routing.
     *
     * Fullscreen navigation (LEFT/RIGHT/UP/DOWN/OK) is owned here to avoid collisions with
     * PlayerView default DPAD behavior (which may auto-open controller before higher-level actions).
     * When panels/controls are visible we defer to Compose/View focus handlers.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!DeviceHelper.isRemoteInputActive()) {
            return super.dispatchKeyEvent(event)
        }

        val handled = when (event.action) {
            KeyEvent.ACTION_DOWN -> handleRemoteKeyDown(event)
            KeyEvent.ACTION_UP -> handleRemoteKeyUp(event)
            else -> false
        }
        return if (handled) true else super.dispatchKeyEvent(event)
    }

    private fun handleRemoteKeyDown(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val currentState = viewModel.viewState.value
        val isFullscreenPlayback = !currentState.areControlsVisible &&
            !currentState.showPlaylist &&
            !currentState.showEpgPanel &&
            currentState.selectedProgramDetails == null

        return when (keyCode) {
            // Channel navigation (direct, bypasses focus)
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (!shouldHandleChannelSwitchPress(event)) return true
                viewModel.switchChannelRelative(+1)
                true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (!shouldHandleChannelSwitchPress(event)) return true
                viewModel.switchChannelRelative(-1)
                true
            }
            // Menu button - context dependent (handled by focused Compose/View target)
            KeyEvent.KEYCODE_MENU -> false
            // Number pad input for channel selection (dialogs/text fields own this)
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9 -> false
            // Media controls - ExoPlayer handles these natively when focused
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> false
            // Info button - show program details
            KeyEvent.KEYCODE_INFO -> {
                val program = currentState.currentProgram ?: return false
                viewModel.showProgramDetails(program)
                true
            }
            // Context buttons handled by focused Compose target
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_1 -> false
            // OK/DPAD_CENTER in fullscreen toggles controls (short) or opens channel dialog (long)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (!isFullscreenPlayback) {
                    fullscreenOkPress.reset()
                    return false
                }
                fullscreenOkPress.onDown(
                    repeatCount = event.repeatCount,
                    isLongPress = event.isLongPress
                ) {
                    val callback = openChannelDialogCallback ?: return@onDown false
                    callback()
                    true
                }
            }
            // Fullscreen LEFT: open playlist immediately
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!isFullscreenPlayback || !currentState.hasChannels) return false
                if (!shouldHandleChannelSwitchPress(event)) return true
                viewModel.openPlaylist()
                true
            }
            // Fullscreen RIGHT: open EPG immediately
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isFullscreenPlayback) return false
                if (!shouldHandleChannelSwitchPress(event)) return true
                val tvgId = currentState.currentChannel?.tvgId
                if (!tvgId.isNullOrBlank()) {
                    viewModel.showEpgForChannel(tvgId)
                    true
                } else {
                    false
                }
            }
            // Fullscreen UP/DOWN: channel switch
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!isFullscreenPlayback) return false
                if (!shouldHandleChannelSwitchPress(event)) return true
                viewModel.switchChannelRelative(+1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!isFullscreenPlayback) return false
                if (!shouldHandleChannelSwitchPress(event)) return true
                viewModel.switchChannelRelative(-1)
                true
            }
            // BACK button - context dependent
            KeyEvent.KEYCODE_BACK -> {
                // Program details owns BACK handling in Compose (BackHandler/onKeyEvent).
                if (currentState.selectedProgramDetails != null) {
                    return false
                }
                if (currentState.showEpgPanel) {
                    viewModel.closeEpgPanel()
                    return true
                }
                if (currentState.showPlaylist) {
                    viewModel.closePlaylist()
                    return true
                }
                if (currentState.areControlsVisible) {
                    toggleControlsCallback?.invoke()
                    return true
                }
                if (currentState.showCloseAppDialog) {
                    false
                } else {
                    viewModel.setShowCloseAppDialog(true)
                    true
                }
            }
            else -> false
        }
    }

    private fun handleRemoteKeyUp(event: KeyEvent): Boolean {
        val currentState = viewModel.viewState.value
        val isFullscreenPlayback = !currentState.areControlsVisible &&
            !currentState.showPlaylist &&
            !currentState.showEpgPanel &&
            currentState.selectedProgramDetails == null

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (!isFullscreenPlayback) {
                    fullscreenOkPress.reset()
                    return false
                }
                fullscreenOkPress.onUp { toggleControlsCallback?.invoke() }
            }
            else -> false
        }
    }

    private fun shouldHandleChannelSwitchPress(event: KeyEvent): Boolean {
        return event.repeatCount == 0 && !event.isLongPress
    }
}
