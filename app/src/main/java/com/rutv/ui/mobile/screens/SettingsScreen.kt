package com.rutv.ui.mobile.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rutv.R
import com.rutv.data.model.PlaylistSource
import com.rutv.presentation.settings.SettingsViewState
import com.rutv.ui.shared.components.RemoteDialog
import com.rutv.ui.shared.components.remoteActivate
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.shared.components.requestFocusSafely
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.Constants
import com.rutv.util.DeviceHelper
import com.rutv.util.PlayerConstants
import com.rutv.util.decodePlaylistText
import timber.log.Timber
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.rutv.ui.shared.components.remoteBack
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Settings Screen with Compose UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewState: SettingsViewState,
    onLoadFile: (String, String?) -> Unit,
    onLoadUrl: (String) -> Unit,
    onShowError: (String) -> Unit,
    onReloadPlaylist: () -> Unit,
    onDebugLogChanged: (Boolean) -> Unit,
    onFfmpegAudioChanged: (Boolean) -> Unit,
    onFfmpegVideoChanged: (Boolean) -> Unit,
    onBufferSecondsChanged: (Int) -> Unit,
    onControlsHideDelaySecondsChanged: (Int) -> Unit,
    onAutoRetryEnabledChanged: (Boolean) -> Unit,
    onAutoRetryMaxAttemptsChanged: (Int) -> Unit,
    onAutoRetryPeriodSecondsChanged: (Int) -> Unit,
    onShowCurrentProgramInChannelListChanged: (Boolean) -> Unit,
    onChannelPreviewEnabledChanged: (Boolean) -> Unit,
    onChannelEpgListRatioChanged: (Int) -> Unit,
    onListPanelEdgeInsetChanged: (Int) -> Unit,
    onListPanelVerticalInsetChanged: (Int) -> Unit,
    onChannelPreviewSizePresetChanged: (Int) -> Unit,
    onSetParentalPassword: (String) -> Unit,
    onChangeParentalPassword: (String, String) -> Unit,
    onRemoveParentalPassword: (String) -> Unit,
    onEpgUrlChanged: (String) -> Unit,
    onEpgDaysAheadChanged: (Int) -> Unit,
    onEpgDaysPastChanged: (Int) -> Unit,
    onEpgPageDaysChanged: (Int) -> Unit,
    onClearEpgCache: () -> Unit,
    onLanguageChanged: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showUrlDialog by remember { mutableStateOf(false) }
    var showReloadDialog by remember { mutableStateOf(false) }
    var showSetParentalPinDialog by remember { mutableStateOf(false) }
    var showChangeParentalPinDialog by remember { mutableStateOf(false) }
    var showRemoveParentalPinDialog by remember { mutableStateOf(false) }

    var showNoPlaylistDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(viewState.parentalPinOperationVersion) {
        if (viewState.parentalPinOperationVersion > 0) {
            showSetParentalPinDialog = false
            showChangeParentalPinDialog = false
            showRemoveParentalPinDialog = false
        }
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val (displayName, content) = withContext(Dispatchers.IO) {
                        val name = context.contentResolver.query(
                            it,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index != -1 && cursor.moveToFirst()) {
                                cursor.getString(index)
                            } else {
                                null
                            }
                        }
                        val text = context.contentResolver.openInputStream(it)
                            ?.use { input ->
                                val maxBytes = Constants.MAX_PLAYLIST_SIZE_BYTES
                                val bytes = input.readAtMost(maxBytes + 1)
                                if (bytes.size > maxBytes) {
                                    throw IllegalArgumentException(
                                        "Playlist is too large (max ${maxBytes} bytes)"
                                    )
                                }
                                decodePlaylistText(bytes)
                            }
                        name to text
                    }
                    if (content.isNullOrEmpty()) {
                        onShowError("Failed to load playlist file (empty or unreadable)")
                    } else {
                        onLoadFile(content, displayName)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to load playlist from URI")
                    onShowError("Failed to load playlist file: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val backButtonFocus = remember { FocusRequester() }
                        var isBackFocused by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = onBack,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            modifier = Modifier
                                .focusRequester(backButtonFocus)
                                .onFocusChanged { isBackFocused = it.hasFocus }
                                .focusable(enabled = DeviceHelper.isRemoteInputActive())
                                .then(focusIndicatorModifier(isFocused = isBackFocused))
                                .remoteActivate(enabled = DeviceHelper.isRemoteInputActive(), onActivate = onBack)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                    tint = MaterialTheme.ruTvColors.gold,
                                    modifier = Modifier.size(32.dp) // Bigger icon
                                )
                                Text(
                                    text = stringResource(R.string.settings_back),
                                    color = MaterialTheme.ruTvColors.gold,
                                    style = MaterialTheme.typography.titleLarge // Bigger text
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.ruTvColors.darkBackground,
                    titleContentColor = MaterialTheme.ruTvColors.gold
                )
            )
        },
        containerColor = MaterialTheme.ruTvColors.darkBackground
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // Playlist Source Section
            item {
                SettingsSectionHeader(stringResource(R.string.settings_playlist_source))
            }

            item {
                    PlaylistInfoCard(
                        playlistSource = viewState.playlistSource,
                        playlistInfoResId = viewState.playlistInfoResId,
                        playlistUrl = viewState.playlistUrl.orEmpty(),
                        urlName = viewState.urlName,
                        fileName = viewState.fileName
                    )
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                val fileButtonFocus = remember { FocusRequester() }
                val urlButtonFocus = remember { FocusRequester() }
                var fileButtonFocused by remember { mutableStateOf(false) }
                var urlButtonFocused by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(fileButtonFocus)
                            .onFocusChanged { fileButtonFocused = it.hasFocus }
                            .focusable()
                            .then(focusIndicatorModifier(isFocused = fileButtonFocused))
                            .remoteActivate(
                                enabled = DeviceHelper.isRemoteInputActive(),
                                onActivate = { filePickerLauncher.launch("*/*") }
                            )
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown || !DeviceHelper.isRemoteInputActive()) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionRight -> {
                                        urlButtonFocus.requestFocusSafely()
                                        true
                                    }
                                    else -> false
                                }
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.ruTvColors.gold,
                            contentColor = MaterialTheme.ruTvColors.darkBackground
                        )
                    ) {
                        Text(stringResource(R.string.settings_load_from_file))
                    }

                    Button(
                        onClick = { showUrlDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(urlButtonFocus)
                            .onFocusChanged { urlButtonFocused = it.hasFocus }
                            .focusable()
                            .then(focusIndicatorModifier(isFocused = urlButtonFocused))
                            .remoteActivate(
                                enabled = DeviceHelper.isRemoteInputActive(),
                                onActivate = { showUrlDialog = true }
                            )
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown || !DeviceHelper.isRemoteInputActive()) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        fileButtonFocus.requestFocusSafely()
                                        true
                                    }
                                    else -> false
                                }
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.ruTvColors.gold,
                            contentColor = MaterialTheme.ruTvColors.darkBackground
                        )
                    ) {
                        Text(stringResource(R.string.settings_load_from_url))
                    }
                }
            }

            item {
                val reloadButtonFocus = remember { FocusRequester() }
                var isFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        if (viewState.playlistSource is PlaylistSource.None) {
                            showNoPlaylistDialog = true
                        } else {
                            showReloadDialog = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(reloadButtonFocus)
                        .onFocusChanged { isFocused = it.hasFocus }
                        .focusable(enabled = DeviceHelper.isRemoteInputActive())
                        .then(focusIndicatorModifier(isFocused = isFocused))
                        .remoteActivate(
                            enabled = DeviceHelper.isRemoteInputActive(),
                            onActivate = {
                                if (viewState.playlistSource is PlaylistSource.None) {
                                    showNoPlaylistDialog = true
                                } else {
                                    showReloadDialog = true
                                }
                            }
                        ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.ruTvColors.selectedBackground,
                        contentColor = MaterialTheme.ruTvColors.textPrimary
                    )
                ) {
                    Text(stringResource(R.string.settings_reload_playlist))
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            // Player Configuration Section
            item {
                SettingsSectionHeader(stringResource(R.string.settings_player_config))
            }

            item {
                SwitchSetting(
                    label = stringResource(R.string.settings_debug_log),
                    checked = viewState.playerConfig.showDebugLog,
                    onCheckedChange = onDebugLogChanged
                )
            }

            item {
                SwitchSetting(
                    label = stringResource(R.string.settings_ffmpeg_audio),
                    checked = viewState.playerConfig.useFfmpegAudio,
                    onCheckedChange = onFfmpegAudioChanged
                )
            }

            item {
                SwitchSetting(
                    label = stringResource(R.string.settings_ffmpeg_video),
                    checked = viewState.playerConfig.useFfmpegVideo,
                    onCheckedChange = onFfmpegVideoChanged
                )
            }

            item {
                NumberInputSetting(
                    label = stringResource(R.string.settings_buffer_seconds),
                    value = viewState.playerConfig.bufferSeconds,
                    onValueChange = onBufferSecondsChanged,
                    minValue = PlayerConstants.MIN_BUFFER_SECONDS,
                    maxValue = PlayerConstants.MAX_BUFFER_SECONDS
                )
            }

            item {
                NumberInputSetting(
                    label = stringResource(R.string.settings_controls_hide_delay_seconds),
                    value = viewState.playerConfig.controlsHideDelaySeconds,
                    onValueChange = onControlsHideDelaySecondsChanged,
                    minValue = PlayerConstants.MIN_CONTROLS_HIDE_DELAY_SECONDS,
                    maxValue = PlayerConstants.MAX_CONTROLS_HIDE_DELAY_SECONDS
                )
            }

            item {
                SwitchSetting(
                    label = stringResource(R.string.settings_auto_retry_enabled),
                    checked = viewState.autoRetryEnabled,
                    onCheckedChange = onAutoRetryEnabledChanged
                )
            }

            item {
                NumberInputSetting(
                    label = stringResource(R.string.settings_auto_retry_max_attempts),
                    value = viewState.autoRetryMaxAttempts,
                    onValueChange = onAutoRetryMaxAttemptsChanged,
                    minValue = PlayerConstants.MIN_AUTO_RETRY_MAX_ATTEMPTS,
                    maxValue = PlayerConstants.MAX_AUTO_RETRY_MAX_ATTEMPTS
                )
            }

            item {
                NumberInputSetting(
                    label = stringResource(R.string.settings_auto_retry_period_seconds),
                    value = viewState.autoRetryPeriodSeconds,
                    onValueChange = onAutoRetryPeriodSecondsChanged,
                    minValue = PlayerConstants.MIN_AUTO_RETRY_PERIOD_SECONDS,
                    maxValue = PlayerConstants.MAX_AUTO_RETRY_PERIOD_SECONDS
                )
            }

            item {
                SwitchSetting(
                    label = stringResource(R.string.settings_show_current_program_in_list),
                    checked = viewState.showCurrentProgramInChannelList,
                    onCheckedChange = onShowCurrentProgramInChannelListChanged
                )
            }

            item {
                SwitchSetting(
                    label = stringResource(R.string.settings_channel_preview_enabled),
                    checked = viewState.channelPreviewEnabled,
                    onCheckedChange = onChannelPreviewEnabledChanged
                )
            }

            item {
                ChannelEpgRatioSetting(
                    value = viewState.channelEpgListRatioPercent,
                    onValueChange = onChannelEpgListRatioChanged
                )
            }

            item {
                ListPanelEdgeInsetSetting(
                    value = viewState.listPanelEdgeInsetDp,
                    onValueChange = onListPanelEdgeInsetChanged
                )
            }

            item {
                ListPanelVerticalInsetSetting(
                    value = viewState.listPanelVerticalInsetDp,
                    onValueChange = onListPanelVerticalInsetChanged
                )
            }

            item {
                ChannelPreviewSizeSetting(
                    value = viewState.channelPreviewSizePreset,
                    onValueChange = onChannelPreviewSizePresetChanged
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            item {
                SettingsSectionHeader(stringResource(R.string.settings_parental_controls))
            }

            item {
                if (viewState.hasParentalPassword) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsButton(
                            label = stringResource(R.string.settings_change_parental_pin),
                            onClick = { showChangeParentalPinDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                        SettingsButton(
                            label = stringResource(R.string.settings_remove_parental_pin),
                            onClick = { showRemoveParentalPinDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    SettingsButton(
                        label = stringResource(R.string.settings_set_parental_pin),
                        onClick = { showSetParentalPinDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.settings_parental_pin_forgotten_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.ruTvColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            // EPG Configuration Section
            item {
                SettingsSectionHeader(stringResource(R.string.settings_epg_config))
            }

            item {
                TextInputSetting(
                    label = stringResource(R.string.settings_epg_url),
                    value = viewState.epgUrl,
                    onValueChange = onEpgUrlChanged
                )
            }

            item {
                NumberInputSetting(
                    label = stringResource(R.string.settings_epg_days_ahead),
                    value = viewState.epgDaysAhead,
                    onValueChange = onEpgDaysAheadChanged,
                    minValue = 1,
                    maxValue = 30
                )
            }

            item {
                NumberInputSetting(
                    label = stringResource(R.string.settings_epg_days_past),
                    value = viewState.epgDaysPast,
                    onValueChange = onEpgDaysPastChanged,
                    minValue = 1,
                    maxValue = 60
                )
            }

            item {
                NumberInputSetting(
                    label = stringResource(R.string.settings_epg_page_days),
                    value = viewState.epgPageDays,
                    onValueChange = onEpgPageDaysChanged,
                    minValue = 1,
                    maxValue = 14
                )
            }

            item {
                val clearCacheButtonFocus = remember { FocusRequester() }
                var isFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = onClearEpgCache,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(clearCacheButtonFocus)
                        .onFocusChanged { isFocused = it.hasFocus }
                        .focusable(enabled = DeviceHelper.isRemoteInputActive())
                        .then(focusIndicatorModifier(isFocused = isFocused))
                        .remoteActivate(enabled = DeviceHelper.isRemoteInputActive(), onActivate = onClearEpgCache),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.ruTvColors.selectedBackground,
                        contentColor = MaterialTheme.ruTvColors.textPrimary
                    )
                ) {
                    Text(text = stringResource(R.string.settings_clear_epg_cache))
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            // Language Selection Section
            item {
                SettingsSectionHeader(stringResource(R.string.settings_language))
            }

            item {
                LanguageSelectorSetting(
                    selectedLanguage = viewState.selectedLanguage,
                    onLanguageSelected = onLanguageChanged
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    // Dialogs
    if (showUrlDialog) {
        UrlInputDialog(
            currentUrl = (viewState.playlistSource as? PlaylistSource.Url)?.url ?: "",
            onDismiss = { showUrlDialog = false },
            onConfirm = { url ->
                onLoadUrl(url)
                showUrlDialog = false
            }
        )
    }

    if (showReloadDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.dialog_title_reload_playlist),
            message = stringResource(R.string.dialog_message_reload_playlist),
            onDismiss = { showReloadDialog = false },
            onConfirm = {
                onReloadPlaylist()
                showReloadDialog = false
            }
        )
    }

    if (showSetParentalPinDialog) {
        SetParentalPinDialog(
            onDismiss = { showSetParentalPinDialog = false },
            onConfirm = { pin ->
                onSetParentalPassword(pin)
                showSetParentalPinDialog = false
            }
        )
    }

    if (showChangeParentalPinDialog) {
        ChangeParentalPinDialog(
            externalError = viewState.parentalPinError,
            onDismiss = { showChangeParentalPinDialog = false },
            onConfirm = { currentPin, newPin ->
                onChangeParentalPassword(currentPin, newPin)
            }
        )
    }

    if (showRemoveParentalPinDialog) {
        RemoveParentalPinDialog(
            externalError = viewState.parentalPinError,
            onDismiss = { showRemoveParentalPinDialog = false },
            onConfirm = { currentPin ->
                onRemoveParentalPassword(currentPin)
            }
        )
    }

    if (showNoPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showNoPlaylistDialog = false },
            title = { Text(stringResource(R.string.dialog_title_no_playlist_loaded)) },
            text = { Text(stringResource(R.string.dialog_message_no_playlist_loaded)) },
            confirmButton = {
                TextButton(onClick = { showNoPlaylistDialog = false }) {
                    Text(stringResource(R.string.button_ok))
                }
            }
        )
    }
}

@Composable
private fun SettingsButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { isFocused = it.hasFocus }
            .focusable()
            .then(focusIndicatorModifier(isFocused))
            .remoteActivate(enabled = DeviceHelper.isRemoteInputActive(), onActivate = onClick),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.ruTvColors.selectedBackground,
            contentColor = MaterialTheme.ruTvColors.textPrimary
        )
    ) {
        Text(label)
    }
}

private fun InputStream.readAtMost(limit: Int): ByteArray {
    if (limit <= 0) return ByteArray(0)
    val buffer = ByteArray(8 * 1024)
    val output = ByteArrayOutputStream(min(8 * 1024, limit))
    var total = 0
    while (total < limit) {
        val toRead = min(buffer.size, limit - total)
        val read = read(buffer, 0, toRead)
        if (read <= 0) break
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.ruTvColors.gold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun PlaylistInfoCard(
    playlistSource: PlaylistSource,
    playlistInfoResId: Int,
    playlistUrl: String,
    urlName: String,
    fileName: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.cardBackground
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(playlistInfoResId),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.ruTvColors.textPrimary
            )

            when (playlistSource) {
                is PlaylistSource.Url -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    val urlDisplay = playlistUrl.ifEmpty { urlName }
                    Text(
                        text = "${stringResource(R.string.settings_current_url)}: $urlDisplay",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ruTvColors.textSecondary
                    )
                }
                is PlaylistSource.File -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${stringResource(R.string.settings_current_file)}: ${fileName ?: stringResource(R.string.settings_unknown_file)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ruTvColors.textSecondary
                    )
                }
                is PlaylistSource.None -> {
                    // No additional info
                }
            }
        }
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.hasFocus }
            .focusable()
            .then(focusIndicatorModifier(isFocused))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .remoteActivate(
                enabled = DeviceHelper.isRemoteInputActive(),
                onActivate = { onCheckedChange(!checked) }
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.ruTvColors.textPrimary
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.ruTvColors.gold,
                checkedTrackColor = MaterialTheme.ruTvColors.goldAlpha50
            )
        )
    }
}

@Composable
private fun ChannelEpgRatioSetting(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val minValue = PlayerConstants.MIN_CHANNEL_LIST_RATIO_PERCENT
    val maxValue = 100 - PlayerConstants.MIN_EPG_LIST_RATIO_PERCENT
    val step = PlayerConstants.CHANNEL_EPG_RATIO_STEP_PERCENT
    val positionCount = ((maxValue - minValue) / step) + 1
    val maxPosition = positionCount - 1
    var isFocused by remember { mutableStateOf(false) }

    fun positionForPercent(percent: Int): Int {
        return ((percent.coerceIn(minValue, maxValue) - minValue) / step)
            .coerceIn(0, maxPosition)
    }

    fun percentForPosition(position: Int): Int {
        return minValue + position.coerceIn(0, maxPosition) * step
    }

    var sliderPosition by remember {
        mutableFloatStateOf(positionForPercent(value).toFloat())
    }
    var pendingValue by remember { mutableStateOf<Int?>(null) }
    val displayedPosition = sliderPosition.roundToInt().coerceIn(0, maxPosition)
    val displayedValue = pendingValue ?: percentForPosition(displayedPosition)

    LaunchedEffect(value) {
        val externalValue = percentForPosition(positionForPercent(value))
        if (pendingValue == null || pendingValue == externalValue) {
            pendingValue = null
            val externalPosition = positionForPercent(externalValue).toFloat()
            if (sliderPosition != externalPosition) {
                sliderPosition = externalPosition
            }
        }
    }

    fun updatePosition(position: Int) {
        val boundedPosition = position.coerceIn(0, maxPosition)
        val steppedValue = percentForPosition(boundedPosition)
        sliderPosition = boundedPosition.toFloat()
        if (steppedValue != displayedValue) {
            pendingValue = steppedValue
            onValueChange(steppedValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.hasFocus }
            .focusable()
            .then(focusIndicatorModifier(isFocused))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .onKeyEvent { event ->
                if (!DeviceHelper.isRemoteInputActive()) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        updatePosition(displayedPosition - 1)
                        true
                    }
                    Key.DirectionRight -> {
                        updatePosition(displayedPosition + 1)
                        true
                    }
                    else -> false
                }
            }
    ) {
        Text(
            text = stringResource(R.string.settings_channel_epg_ratio),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.ruTvColors.textPrimary
        )
        Text(
            text = stringResource(
                R.string.settings_channel_epg_ratio_value,
                displayedValue,
                100 - displayedValue
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.ruTvColors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
        )
        Slider(
            value = sliderPosition,
            onValueChange = { rawPosition ->
                updatePosition(rawPosition.roundToInt())
            },
            modifier = Modifier.focusProperties { canFocus = false },
            valueRange = 0f..maxPosition.toFloat(),
            steps = (positionCount - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.ruTvColors.gold,
                activeTrackColor = MaterialTheme.ruTvColors.gold,
                inactiveTrackColor = MaterialTheme.ruTvColors.textDisabled
            )
        )
    }
}

@Composable
private fun ListPanelEdgeInsetSetting(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val minValue = PlayerConstants.MIN_LIST_PANEL_EDGE_INSET_DP
    val maxValue = PlayerConstants.MAX_LIST_PANEL_EDGE_INSET_DP
    val step = PlayerConstants.LIST_PANEL_EDGE_INSET_STEP_DP
    var isFocused by remember { mutableStateOf(false) }

    fun snap(rawValue: Float): Int {
        val rounded = rawValue.roundToInt().coerceIn(minValue, maxValue)
        return minValue + (((rounded - minValue) + (step / 2)) / step) * step
    }

    var sliderPosition by remember { mutableFloatStateOf(snap(value.toFloat()).toFloat()) }
    var pendingValue by remember { mutableStateOf<Int?>(null) }
    val displayedValue = pendingValue ?: snap(sliderPosition)

    LaunchedEffect(value) {
        val externalValue = snap(value.toFloat())
        if (pendingValue == null || pendingValue == externalValue) {
            pendingValue = null
            val externalPosition = externalValue.toFloat()
            if (sliderPosition != externalPosition) {
                sliderPosition = externalPosition
            }
        }
    }

    fun update(rawValue: Float) {
        val snappedValue = snap(rawValue)
        sliderPosition = snappedValue.toFloat()
        if (snappedValue != displayedValue) {
            pendingValue = snappedValue
            onValueChange(snappedValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.hasFocus }
            .focusable()
            .then(focusIndicatorModifier(isFocused))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .onKeyEvent { event ->
                if (!DeviceHelper.isRemoteInputActive()) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        update(displayedValue - step.toFloat())
                        true
                    }
                    Key.DirectionRight -> {
                        update(displayedValue + step.toFloat())
                        true
                    }
                    else -> false
                }
            }
    ) {
        Text(
            text = stringResource(R.string.settings_list_panel_edge_inset),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.ruTvColors.textPrimary
        )
        Text(
            text = stringResource(R.string.settings_list_panel_edge_inset_value, displayedValue),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.ruTvColors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
        )
        Slider(
            value = sliderPosition,
            onValueChange = { update(it) },
            modifier = Modifier.focusProperties { canFocus = false },
            valueRange = minValue.toFloat()..maxValue.toFloat(),
            steps = ((maxValue - minValue) / step) - 1,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.ruTvColors.gold,
                activeTrackColor = MaterialTheme.ruTvColors.gold,
                inactiveTrackColor = MaterialTheme.ruTvColors.textDisabled
            )
        )
    }
}

@Composable
private fun ListPanelVerticalInsetSetting(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val minValue = PlayerConstants.MIN_LIST_PANEL_VERTICAL_INSET_DP
    val maxValue = PlayerConstants.MAX_LIST_PANEL_VERTICAL_INSET_DP
    val step = PlayerConstants.LIST_PANEL_VERTICAL_INSET_STEP_DP
    var isFocused by remember { mutableStateOf(false) }

    fun snap(rawValue: Float): Int {
        val rounded = rawValue.roundToInt().coerceIn(minValue, maxValue)
        return minValue + (((rounded - minValue) + (step / 2)) / step) * step
    }

    var sliderPosition by remember { mutableFloatStateOf(snap(value.toFloat()).toFloat()) }
    var pendingValue by remember { mutableStateOf<Int?>(null) }
    val displayedValue = pendingValue ?: snap(sliderPosition)

    LaunchedEffect(value) {
        val externalValue = snap(value.toFloat())
        if (pendingValue == null || pendingValue == externalValue) {
            pendingValue = null
            val externalPosition = externalValue.toFloat()
            if (sliderPosition != externalPosition) {
                sliderPosition = externalPosition
            }
        }
    }

    fun update(rawValue: Float) {
        val snappedValue = snap(rawValue)
        sliderPosition = snappedValue.toFloat()
        if (snappedValue != displayedValue) {
            pendingValue = snappedValue
            onValueChange(snappedValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.hasFocus }
            .focusable()
            .then(focusIndicatorModifier(isFocused))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .onKeyEvent { event ->
                if (!DeviceHelper.isRemoteInputActive()) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        update(displayedValue - step.toFloat())
                        true
                    }
                    Key.DirectionRight -> {
                        update(displayedValue + step.toFloat())
                        true
                    }
                    else -> false
                }
            }
    ) {
        Text(
            text = stringResource(R.string.settings_list_panel_vertical_inset),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.ruTvColors.textPrimary
        )
        Text(
            text = stringResource(R.string.settings_list_panel_vertical_inset_value, displayedValue),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.ruTvColors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
        )
        Slider(
            value = sliderPosition,
            onValueChange = { update(it) },
            modifier = Modifier.focusProperties { canFocus = false },
            valueRange = minValue.toFloat()..maxValue.toFloat(),
            steps = ((maxValue - minValue) / step) - 1,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.ruTvColors.gold,
                activeTrackColor = MaterialTheme.ruTvColors.gold,
                inactiveTrackColor = MaterialTheme.ruTvColors.textDisabled
            )
        )
    }
}

@Composable
private fun ChannelPreviewSizeSetting(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val presets = PlayerConstants.CHANNEL_PREVIEW_WIDTH_PRESETS_DP
    val minPosition = 0
    val maxPosition = presets.lastIndex
    val selectedPosition = value.coerceIn(minPosition, maxPosition)
    var isFocused by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(selectedPosition.toFloat()) }
    var pendingValue by remember { mutableStateOf<Int?>(null) }
    val displayedPosition = pendingValue ?: sliderPosition.roundToInt().coerceIn(minPosition, maxPosition)
    val selectedWidth = presets[displayedPosition]
    val selectedHeight = selectedWidth * 9 / 16

    fun updatePosition(position: Int) {
        val boundedPosition = position.coerceIn(minPosition, maxPosition)
        sliderPosition = boundedPosition.toFloat()
        if (boundedPosition != displayedPosition) {
            pendingValue = boundedPosition
            onValueChange(boundedPosition)
        }
    }

    LaunchedEffect(value) {
        if (pendingValue == null || pendingValue == selectedPosition) {
            pendingValue = null
            val externalPosition = selectedPosition.toFloat()
            if (sliderPosition != externalPosition) {
                sliderPosition = externalPosition
            }
        }
    }

    val presetName = when (displayedPosition) {
        0 -> stringResource(R.string.settings_channel_preview_size_small)
        1 -> stringResource(R.string.settings_channel_preview_size_compact)
        2 -> stringResource(R.string.settings_channel_preview_size_default)
        3 -> stringResource(R.string.settings_channel_preview_size_large)
        else -> stringResource(R.string.settings_channel_preview_size_tablet)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.hasFocus }
            .focusable()
            .then(focusIndicatorModifier(isFocused))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .onKeyEvent { event ->
                if (!DeviceHelper.isRemoteInputActive()) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        updatePosition(displayedPosition - 1)
                        true
                    }
                    Key.DirectionRight -> {
                        updatePosition(displayedPosition + 1)
                        true
                    }
                    else -> false
                }
            }
    ) {
        Text(
            text = stringResource(R.string.settings_channel_preview_size),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.ruTvColors.textPrimary
        )
        Text(
            text = stringResource(
                R.string.settings_channel_preview_size_value,
                presetName,
                selectedWidth,
                selectedHeight
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.ruTvColors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
        )
        Slider(
            value = sliderPosition,
            onValueChange = { rawPosition ->
                updatePosition(rawPosition.roundToInt())
            },
            modifier = Modifier.focusProperties { canFocus = false },
            valueRange = minPosition.toFloat()..maxPosition.toFloat(),
            steps = (presets.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.ruTvColors.gold,
                activeTrackColor = MaterialTheme.ruTvColors.gold,
                inactiveTrackColor = MaterialTheme.ruTvColors.textDisabled
            )
        )
    }
}

@Composable
private fun TextInputSetting(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Use local state to avoid immediate updates while typing
    var localValue by remember(value) { mutableStateOf(value) }
    var isFocused by remember { mutableStateOf(false) }

    // Save when focus is lost
    LaunchedEffect(isFocused) {
        if (!isFocused && localValue != value) {
            onValueChange(localValue)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.ruTvColors.textPrimary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        OutlinedTextField(
            value = localValue,
            onValueChange = { localValue = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    isFocused = focusState.hasFocus
                }
                .focusable()
                .then(focusIndicatorModifier(isFocused)),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.ruTvColors.gold,
                unfocusedBorderColor = MaterialTheme.ruTvColors.textDisabled,
                focusedTextColor = MaterialTheme.ruTvColors.textPrimary,
                unfocusedTextColor = MaterialTheme.ruTvColors.textPrimary
            ),
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (localValue != value) {
                        onValueChange(localValue)
                    }
                    isFocused = false
                }
            )
        )
    }
}

@Composable
private fun NumberInputSetting(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    minValue: Int,
    maxValue: Int,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val openDialog = { showDialog = true }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.ruTvColors.textPrimary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { },
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.hasFocus }
                .focusable()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { openDialog() }
                .remoteActivate(enabled = DeviceHelper.isRemoteInputActive(), onActivate = openDialog)
                .then(focusIndicatorModifier(isFocused)),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.ruTvColors.gold,
                unfocusedBorderColor = MaterialTheme.ruTvColors.textDisabled,
                focusedTextColor = MaterialTheme.ruTvColors.textPrimary,
                unfocusedTextColor = MaterialTheme.ruTvColors.textPrimary
            )
        )
    }

    if (showDialog) {
        NumberInputDialog(
            label = label,
            initialValue = value,
            minValue = minValue,
            maxValue = maxValue,
            onConfirm = {
                onValueChange(it)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun NumberInputDialog(
    label: String,
    initialValue: Int,
    minValue: Int,
    maxValue: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(initialValue.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val confirmFocusRequester = remember { FocusRequester() }
    val errorMessage = stringResource(R.string.settings_number_error, minValue, maxValue)
    val rangeHint = stringResource(R.string.settings_number_range_hint, minValue, maxValue)

    fun commit(): Boolean {
        val parsed = input.toIntOrNull()
        return if (parsed != null && parsed in minValue..maxValue) {
            onConfirm(parsed)
            true
        } else {
            error = errorMessage
            false
        }
    }

    val confirmAction = {
        if (commit()) {
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocusSafely()
    }

    RemoteDialog(
        onDismissRequest = onDismiss,
        autoFocusConfirm = false,
        confirmButtonFocusRequester = confirmFocusRequester,
        textFocusRequester = focusRequester,
        onConfirm = confirmAction,
        title = { Text(label) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { new ->
                        input = new.filter { it.isDigit() }
                        error = null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            confirmAction()
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .remoteBack { onDismiss() }
                        .onKeyEvent { event ->
                            if (!DeviceHelper.isRemoteInputActive()) return@onKeyEvent false
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    confirmFocusRequester.requestFocusSafely()
                                    true
                                }
                                else -> false
                            }
                        },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.ruTvColors.gold,
                        unfocusedBorderColor = MaterialTheme.ruTvColors.textDisabled,
                        focusedTextColor = MaterialTheme.ruTvColors.textPrimary,
                        unfocusedTextColor = MaterialTheme.ruTvColors.textPrimary
                    ),
                    supportingText = {
                        Text(
                            text = error ?: rangeHint,
                            color = if (error != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.ruTvColors.textSecondary
                            }
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = confirmAction,
                // Keep focus on RemoteDialog's wrapper (gold border) for consistency
                modifier = Modifier.focusable(false)
            ) {
                Text(stringResource(R.string.button_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusable(false)) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@Composable
private fun SetParentalPinDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val buttonFocus = remember { FocusRequester() }
    val mismatchError = stringResource(R.string.parental_pin_mismatch)
    val invalidError = stringResource(R.string.parental_pin_invalid)

    fun commit() {
        when {
            pin.length != 4 -> error = invalidError
            pin != confirmPin -> error = mismatchError
            else -> onConfirm(pin)
        }
    }

    LaunchedEffect(Unit) {
        firstFocus.requestFocusSafely()
    }

    RemoteDialog(
        onDismissRequest = onDismiss,
        autoFocusConfirm = false,
        confirmButtonFocusRequester = buttonFocus,
        textFocusRequester = firstFocus,
        onConfirm = { commit() },
        title = { Text(stringResource(R.string.settings_set_parental_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinTextField(
                    value = pin,
                    onValueChange = {
                        pin = it
                        error = null
                    },
                    label = stringResource(R.string.parental_pin_new),
                    focusRequester = firstFocus,
                    nextFocusRequester = confirmFocus,
                    onDismiss = onDismiss
                )
                PinTextField(
                    value = confirmPin,
                    onValueChange = {
                        confirmPin = it
                        error = null
                    },
                    label = stringResource(R.string.parental_pin_confirm),
                    focusRequester = confirmFocus,
                    nextFocusRequester = buttonFocus,
                    onDismiss = onDismiss
                )
                Text(
                    text = error ?: stringResource(R.string.parental_pin_hint),
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.ruTvColors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { commit() }, modifier = Modifier.focusable(false)) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusable(false)) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@Composable
private fun ChangeParentalPinDialog(
    externalError: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val currentFocus = remember { FocusRequester() }
    val newFocus = remember { FocusRequester() }
    val confirmPinFocus = remember { FocusRequester() }
    val buttonFocus = remember { FocusRequester() }
    val mismatchError = stringResource(R.string.parental_pin_mismatch)
    val invalidError = stringResource(R.string.parental_pin_invalid)

    fun commit() {
        when {
            currentPin.length != 4 || newPin.length != 4 -> error = invalidError
            newPin != confirmPin -> error = mismatchError
            else -> onConfirm(currentPin, newPin)
        }
    }

    LaunchedEffect(Unit) {
        currentFocus.requestFocusSafely()
    }

    RemoteDialog(
        onDismissRequest = onDismiss,
        autoFocusConfirm = false,
        confirmButtonFocusRequester = buttonFocus,
        textFocusRequester = currentFocus,
        onConfirm = { commit() },
        title = { Text(stringResource(R.string.settings_change_parental_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinTextField(currentPin, {
                    currentPin = it
                    error = null
                }, stringResource(R.string.parental_pin_current), currentFocus, newFocus, onDismiss)
                PinTextField(newPin, {
                    newPin = it
                    error = null
                }, stringResource(R.string.parental_pin_new), newFocus, confirmPinFocus, onDismiss)
                PinTextField(confirmPin, {
                    confirmPin = it
                    error = null
                }, stringResource(R.string.parental_pin_confirm), confirmPinFocus, buttonFocus, onDismiss)
                Text(
                    text = error ?: externalError ?: stringResource(R.string.parental_pin_hint),
                    color = if (error != null || externalError != null) MaterialTheme.colorScheme.error else MaterialTheme.ruTvColors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { commit() }, modifier = Modifier.focusable(false)) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusable(false)) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@Composable
private fun RemoveParentalPinDialog(
    externalError: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val inputFocus = remember { FocusRequester() }
    val buttonFocus = remember { FocusRequester() }
    val invalidError = stringResource(R.string.parental_pin_invalid)

    fun commit() {
        if (currentPin.length != 4) {
            error = invalidError
        } else {
            onConfirm(currentPin)
        }
    }

    LaunchedEffect(Unit) {
        inputFocus.requestFocusSafely()
    }

    RemoteDialog(
        onDismissRequest = onDismiss,
        autoFocusConfirm = false,
        confirmButtonFocusRequester = buttonFocus,
        textFocusRequester = inputFocus,
        onConfirm = { commit() },
        title = { Text(stringResource(R.string.settings_remove_parental_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.parental_remove_warning),
                    color = MaterialTheme.ruTvColors.textPrimary
                )
                PinTextField(currentPin, {
                    currentPin = it
                    error = null
                }, stringResource(R.string.parental_pin_current), inputFocus, buttonFocus, onDismiss)
                Text(
                    text = error ?: externalError ?: stringResource(R.string.parental_pin_hint),
                    color = if (error != null || externalError != null) MaterialTheme.colorScheme.error else MaterialTheme.ruTvColors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { commit() }, modifier = Modifier.focusable(false)) {
                Text(stringResource(R.string.settings_remove_parental_pin))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusable(false)) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@Composable
private fun PinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    focusRequester: FocusRequester,
    nextFocusRequester: FocusRequester,
    onDismiss: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { ch -> ch.isDigit() }.take(4)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                nextFocusRequester.requestFocusSafely()
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .remoteBack { onDismiss() }
            .onKeyEvent { event ->
                if (!DeviceHelper.isRemoteInputActive()) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        nextFocusRequester.requestFocusSafely()
                        true
                    }
                    else -> false
                }
            },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.ruTvColors.gold,
            unfocusedBorderColor = MaterialTheme.ruTvColors.textDisabled,
            focusedTextColor = MaterialTheme.ruTvColors.textPrimary,
            unfocusedTextColor = MaterialTheme.ruTvColors.textPrimary
        )
    )
}

@Composable
private fun UrlInputDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }
    val inputFocusRequester = remember { FocusRequester() }
    val confirmFocusRequester = remember { FocusRequester() }

    val confirmAction = {
        if (url.isNotBlank()) {
            onConfirm(url)
        }
    }

    LaunchedEffect(Unit) {
        inputFocusRequester.requestFocusSafely()
    }

    RemoteDialog(
        onDismissRequest = onDismiss,
        autoFocusConfirm = false,
        confirmButtonFocusRequester = confirmFocusRequester,
        textFocusRequester = inputFocusRequester,
        onConfirm = confirmAction,
        title = { Text(stringResource(R.string.dialog_title_load_playlist_url)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text(stringResource(R.string.hint_m3u_url)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocusRequester)
                    .remoteBack { onDismiss() }
                    .onKeyEvent { event ->
                        if (!DeviceHelper.isRemoteInputActive()) return@onKeyEvent false
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> {
                                confirmFocusRequester.requestFocusSafely()
                                true
                            }
                            else -> false
                        }
                    },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.ruTvColors.gold,
                    unfocusedBorderColor = MaterialTheme.ruTvColors.textDisabled
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = confirmAction,
                modifier = Modifier.focusable(false)
            ) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusable(false)) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val confirmAction = { onConfirm() }
    RemoteDialog(
        onDismissRequest = onDismiss,
        onConfirm = confirmAction,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = confirmAction, modifier = Modifier.focusable(false)) {
                Text(stringResource(R.string.button_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusable(false)) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelectorSetting(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val languages = listOf(
        "en" to stringResource(R.string.settings_language_english),
        "ru" to stringResource(R.string.settings_language_russian)
    )

    var expanded by remember { mutableStateOf(false) }
    val toggleMenu = { expanded = !expanded }
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.ruTvColors.textPrimary,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { toggleMenu() },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = languages.find { it.first == selectedLanguage }?.second ?: "",
                onValueChange = { },
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.hasFocus }
                    .focusable()
                    .then(focusIndicatorModifier(isFocused))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { toggleMenu() }
                    .remoteActivate(enabled = DeviceHelper.isRemoteInputActive(), onActivate = toggleMenu),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.ruTvColors.gold,
                    unfocusedBorderColor = MaterialTheme.ruTvColors.textDisabled,
                    focusedTextColor = MaterialTheme.ruTvColors.textPrimary,
                    unfocusedTextColor = MaterialTheme.ruTvColors.textPrimary
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                languages.forEach { (code, displayName) ->
                    DropdownMenuItem(
                        text = { Text(displayName) },
                        onClick = {
                            onLanguageSelected(code)
                            expanded = false
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.ruTvColors.textPrimary
                        )
                    )
                }
            }
        }
    }
}
