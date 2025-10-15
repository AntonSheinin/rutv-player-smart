package com.videoplayer.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.videoplayer.R
import com.videoplayer.data.model.PlaylistSource
import com.videoplayer.presentation.settings.SettingsViewState
import com.videoplayer.ui.theme.ruTvColors
import com.videoplayer.util.Constants
import timber.log.Timber

/**
 * Settings Screen with Compose UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewState: SettingsViewState,
    onLoadFile: (String) -> Unit,
    onLoadUrl: (String) -> Unit,
    onReloadPlaylist: () -> Unit,
    onForceEpgFetch: () -> Unit,
    onDebugLogChanged: (Boolean) -> Unit,
    onFfmpegAudioChanged: (Boolean) -> Unit,
    onFfmpegVideoChanged: (Boolean) -> Unit,
    onBufferSecondsChanged: (Int) -> Unit,
    onShowCurrentProgramChanged: (Boolean) -> Unit,
    onEpgUrlChanged: (String) -> Unit,
    onEpgDaysAheadChanged: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showUrlDialog by remember { mutableStateOf(false) }
    var showReloadDialog by remember { mutableStateOf(false) }
    var showForceEpgDialog by remember { mutableStateOf(false) }
    var showNoPlaylistDialog by remember { mutableStateOf(false) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val content = context.contentResolver.openInputStream(it)
                    ?.bufferedReader()
                    ?.use { reader -> reader.readText() }
                content?.let { fileContent ->
                    onLoadFile(fileContent)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load playlist from URI")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_back)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back)
                        )
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
                .padding(16.dp)
        ) {
            // Playlist Source Section
            item {
                SettingsSectionHeader(stringResource(R.string.settings_playlist_source))
            }

            item {
                    PlaylistInfoCard(
                        playlistSource = viewState.playlistSource,
                        playlistInfo = viewState.playlistInfo,
                        playlistUrl = viewState.playlistUrl.orEmpty(),
                        urlName = viewState.urlName
                    )
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.ruTvColors.gold,
                            contentColor = MaterialTheme.ruTvColors.darkBackground
                        )
                    ) {
                        Text(stringResource(R.string.settings_load_from_file))
                    }

                    Button(
                        onClick = { showUrlDialog = true },
                        modifier = Modifier.weight(1f),
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
                Button(
                    onClick = {
                        if (viewState.playlistSource is PlaylistSource.None) {
                            showNoPlaylistDialog = true
                        } else {
                            showReloadDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
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
                    minValue = Constants.MIN_BUFFER_SECONDS,
                    maxValue = Constants.MAX_BUFFER_SECONDS
                )
            }

            item {
                SwitchSetting(
                    label = "Show Current Program",
                    checked = viewState.playerConfig.showCurrentProgram,
                    onCheckedChange = onShowCurrentProgramChanged
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
                    onValueChange = onEpgUrlChanged,
                    placeholder = "https://example.com/epg.xml"
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
                Button(
                    onClick = { showForceEpgDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.ruTvColors.selectedBackground,
                        contentColor = MaterialTheme.ruTvColors.textPrimary
                    )
                ) {
                    Text(stringResource(R.string.settings_force_epg_fetch))
                }
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

    if (showForceEpgDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.dialog_title_force_epg_fetch),
            message = stringResource(R.string.dialog_message_force_epg_fetch),
            onDismiss = { showForceEpgDialog = false },
            onConfirm = {
                onForceEpgFetch()
                showForceEpgDialog = false
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
    playlistInfo: String,
    playlistUrl: String,
    urlName: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.cardBackground
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = playlistInfo,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.ruTvColors.textPrimary
            )

            when (playlistSource) {
                is PlaylistSource.Url -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${stringResource(R.string.settings_current_url)}: $urlName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ruTvColors.textSecondary
                    )
                    if (playlistUrl.isNotEmpty()) {
                        Text(
                            text = playlistUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.ruTvColors.textHint
                        )
                    }
                }
                is PlaylistSource.File -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${stringResource(R.string.settings_current_file)}: ${stringResource(R.string.status_file)}",
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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
private fun TextInputSetting(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
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
            placeholder = { Text(placeholder) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    isFocused = focusState.isFocused
                },
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
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.ruTvColors.textPrimary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { newValue ->
                newValue.toIntOrNull()?.let { intValue ->
                    val clampedValue = intValue.coerceIn(minValue, maxValue)
                    onValueChange(clampedValue)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.ruTvColors.gold,
                unfocusedBorderColor = MaterialTheme.ruTvColors.textDisabled,
                focusedTextColor = MaterialTheme.ruTvColors.textPrimary,
                unfocusedTextColor = MaterialTheme.ruTvColors.textPrimary
            )
        )
    }
}

@Composable
private fun UrlInputDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_title_load_playlist_url)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text(stringResource(R.string.hint_m3u_url)) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.ruTvColors.gold,
                    unfocusedBorderColor = MaterialTheme.ruTvColors.textDisabled
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isNotBlank()) {
                        onConfirm(url)
                    }
                }
            ) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.button_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}
