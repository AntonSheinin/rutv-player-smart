package com.rutv.ui.mobile.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import com.rutv.R
import com.rutv.data.model.PlaylistSource
import com.rutv.presentation.settings.SettingsViewState
import com.rutv.ui.shared.components.RemoteDialog
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.Constants
import com.rutv.util.DeviceHelper
import com.rutv.util.PlayerConstants
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
    onClearEpgCache: () -> Unit,
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
                            .focusable()
                            .focusRequester(fileButtonFocus)
                            .onFocusChanged { fileButtonFocused = it.isFocused }
                            .then(focusIndicatorModifier(isFocused = fileButtonFocused))
                            .onKeyEvent { event ->
                                val remoteActive = DeviceHelper.isRemoteInputActive()
                                if (event.type == KeyEventType.KeyDown && fileButtonFocused && remoteActive) {
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
                            .focusable()
                            .focusRequester(urlButtonFocus)
                            .onFocusChanged { urlButtonFocused = it.isFocused }
                            .then(focusIndicatorModifier(isFocused = urlButtonFocused))
                            .onKeyEvent { event ->
                                val remoteActive = DeviceHelper.isRemoteInputActive()
                                if (event.type == KeyEventType.KeyDown && urlButtonFocused && remoteActive) {
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
                NumberInputSetting(
                    label = stringResource(R.string.settings_epg_page_days),
                    value = viewState.epgPageDays,
                    onValueChange = onEpgPageDaysChanged,
                    minValue = 1,
                    maxValue = 14
                )
            }

            item {
                Button(
                    onClick = onClearEpgCache,
                    modifier = Modifier.fillMaxWidth()
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
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusable()
            .onFocusChanged { isFocused = it.isFocused }
            .then(focusIndicatorModifier(isFocused))
            .padding(vertical = 8.dp)
            .onKeyEvent { event ->
                if (DeviceHelper.isRemoteInputActive() && isFocused && event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Enter, Key.DirectionCenter -> {
                            onCheckedChange(!checked)
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
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
                .focusable()
                .onFocusChanged { focusState ->
                    isFocused = focusState.isFocused
                }
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
                .focusable()
                .onFocusChanged { isFocused = it.isFocused }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { openDialog() }
                .onKeyEvent { event ->
                    if (
                        DeviceHelper.isRemoteInputActive() &&
                        event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.DirectionCenter)
                    ) {
                        openDialog()
                        true
                    } else {
                        false
                    }
                }
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

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    RemoteDialog(
        onDismissRequest = onDismiss,
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
                            if (commit()) {
                                onDismiss()
                            }
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
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
                onClick = {
                    if (commit()) {
                        onDismiss()
                    }
                }
            ) {
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
                    .focusable()
                    .onFocusChanged { isFocused = it.isFocused }
                    .then(focusIndicatorModifier(isFocused))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { toggleMenu() }
                    .onKeyEvent { event ->
                        if (
                            DeviceHelper.isRemoteInputActive() &&
                            event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.DirectionCenter)
                        ) {
                            toggleMenu()
                            true
                        } else {
                            false
                        }
                    },
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
