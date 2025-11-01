package com.videoplayer.ui.mobile.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.videoplayer.R
import com.videoplayer.data.model.PlaylistSource
import com.videoplayer.presentation.settings.SettingsViewState
import com.videoplayer.ui.shared.components.RemoteDialog
import com.videoplayer.ui.shared.components.focusIndicatorModifier
import com.videoplayer.ui.theme.ruTvColors
import com.videoplayer.util.Constants
import com.videoplayer.util.DeviceHelper
import com.videoplayer.util.PlayerConstants
import timber.log.Timber
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Settings Screen with Compose UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewState: SettingsViewState,
    onLoadFile: (String, String?) -> Unit,
    onLoadUrl: (String) -> Unit,
    onReloadPlaylist: () -> Unit,
    onDebugLogChanged: (Boolean) -> Unit,
    onFfmpegAudioChanged: (Boolean) -> Unit,
    onFfmpegVideoChanged: (Boolean) -> Unit,
    onBufferSecondsChanged: (Int) -> Unit,
    onEpgUrlChanged: (String) -> Unit,
    onEpgDaysAheadChanged: (Int) -> Unit,
    onEpgDaysPastChanged: (Int) -> Unit,
    onEpgPageDaysChanged: (Int) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showUrlDialog by remember { mutableStateOf(false) }
    var showReloadDialog by remember { mutableStateOf(false) }

    var showNoPlaylistDialog by remember { mutableStateOf(false) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val displayName = context.contentResolver.query(
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
                val content = context.contentResolver.openInputStream(it)
                    ?.bufferedReader()
                    ?.use { reader -> reader.readText() }
                content?.let { fileContent ->
                    onLoadFile(fileContent, displayName)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load playlist from URI")
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
                        TextButton(
                            onClick = onBack,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
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
                .padding(16.dp)
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
                val isRemoteMode = DeviceHelper.isRemoteInputActive()
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
                            .focusable(enabled = isRemoteMode)
                            .focusRequester(fileButtonFocus)
                            .onFocusChanged { fileButtonFocused = it.isFocused }
                            .then(if (isRemoteMode) focusIndicatorModifier(isFocused = fileButtonFocused) else Modifier)
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && fileButtonFocused && isRemoteMode) {
                                    when (event.key) {
                                        Key.DirectionCenter, Key.Enter -> {
                                            filePickerLauncher.launch("*/*")
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            urlButtonFocus.requestFocus()
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
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
                            .focusable(enabled = isRemoteMode)
                            .focusRequester(urlButtonFocus)
                            .onFocusChanged { urlButtonFocused = it.isFocused }
                            .then(if (isRemoteMode) focusIndicatorModifier(isFocused = urlButtonFocused) else Modifier)
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && urlButtonFocused && isRemoteMode) {
                                    when (event.key) {
                                        Key.DirectionCenter, Key.Enter -> {
                                            showUrlDialog = true
                                            true
                                        }
                                        Key.DirectionLeft -> {
                                            fileButtonFocus.requestFocus()
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
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
                    minValue = PlayerConstants.MIN_BUFFER_SECONDS,
                    maxValue = PlayerConstants.MAX_BUFFER_SECONDS
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
                NumberInputDelayedSetting(
                    label = stringResource(R.string.settings_epg_page_days),
                    value = viewState.epgPageDays,
                    minValue = 1,
                    maxValue = 14,
                    onCommit = onEpgPageDaysChanged
                )
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
private fun NumberInputDelayedSetting(
    label: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var local by remember(value) { mutableStateOf(value.toString()) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(isFocused) {
        if (!isFocused) {
            val parsed = local.toIntOrNull()
            if (parsed != null) {
                val clamped = parsed.coerceIn(minValue, maxValue)
                if (clamped != value) onCommit(clamped)
                local = clamped.toString()
            } else {
                // Revert to current value on invalid input
                local = value.toString()
            }
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
            value = local,
            onValueChange = { new ->
                // Allow empty while typing; validation on blur
                if (new.length <= 2) local = new.filter { it.isDigit() }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    isFocused = false
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
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

    RemoteDialog(
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
    RemoteDialog(
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

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.ruTvColors.textPrimary,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = languages.find { it.first == selectedLanguage }?.second ?: "",
                onValueChange = { },
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
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
