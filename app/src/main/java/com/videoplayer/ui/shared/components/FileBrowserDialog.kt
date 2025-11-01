package com.videoplayer.ui.shared.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoplayer.R
import com.videoplayer.ui.theme.ruTvColors
import com.videoplayer.util.DeviceHelper
import java.io.File
import kotlinx.coroutines.launch

/**
 * File browser dialog for remote file selection
 * Navigable with D-pad, displays files and directories
 */
@Composable
fun FileBrowserDialog(
    initialDirectory: File,
    onFileSelected: (File) -> Unit,
    onDismiss: () -> Unit,
    filter: (File) -> Boolean = { true },
    modifier: Modifier = Modifier
) {
    val isRemoteMode = DeviceHelper.isRemoteInputActive()
    var currentDirectory by remember { mutableStateOf(initialDirectory.canonicalFile) }
    var fileList by remember(currentDirectory) {
        mutableStateOf(
            runCatching {
                currentDirectory.listFiles()?.toList()?.sortedWith(
                    compareBy({ !it.isDirectory }, { it.name.lowercase() })
                ) ?: emptyList()
            }.getOrDefault(emptyList())
        )
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Focus requesters for file items
    val focusRequesters = remember(fileList.size) {
        List(fileList.size) { FocusRequester() }
    }

    // Focus requesters for buttons
    val selectButtonFocus = remember { FocusRequester() }
    val cancelButtonFocus = remember { FocusRequester() }
    val upButtonFocus = remember { FocusRequester() }

    // Request focus on first item when dialog opens
    LaunchedEffect(fileList.size, isRemoteMode) {
        if (isRemoteMode && fileList.isNotEmpty()) {
            focusRequesters[0].requestFocus()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth(0.8f)
            .fillMaxHeight(0.8f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.file_browser_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.ruTvColors.gold
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .focusable(enabled = isRemoteMode)
                        .focusRequester(cancelButtonFocus)
                        .then(focusIndicatorModifier(isFocused = false))
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter -> {
                                        onDismiss()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.file_browser_cancel),
                        tint = MaterialTheme.ruTvColors.textPrimary
                    )
                }
            }

            // Current directory path
            Text(
                text = currentDirectory.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.ruTvColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            HorizontalDivider(color = MaterialTheme.ruTvColors.textDisabled)

            // File list
            Box(modifier = Modifier.weight(1f)) {
                if (fileList.isEmpty()) {
                    Text(
                        text = stringResource(R.string.file_browser_no_files),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.ruTvColors.textSecondary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Parent directory item
                        if (currentDirectory.parent != null) {
                            item {
                                var isFocused by remember { mutableStateOf(false) }

                                FileBrowserItem(
                                    name = stringResource(R.string.file_browser_parent),
                                    isDirectory = true,
                                    onClick = {
                                        currentDirectory = currentDirectory.parentFile
                                    },
                                    focusRequester = upButtonFocus,
                                    isFocused = isFocused,
                                    onFocusChanged = { isFocused = it },
                                    modifier = Modifier
                                )
                            }
                        }

                        items(fileList.size, key = { fileList[it].absolutePath }) { index ->
                            val file = fileList[index]
                            var isFocused by remember { mutableStateOf(false) }

                            // Auto-scroll when item gets focus
                            LaunchedEffect(isFocused) {
                                if (isFocused && isRemoteMode) {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(index)
                                    }
                                }
                            }

                            FileBrowserItem(
                                name = file.name,
                                isDirectory = file.isDirectory,
                                onClick = {
                                    if (file.isDirectory) {
                                        currentDirectory = file
                                    } else if (filter(file)) {
                                        onFileSelected(file)
                                    }
                                },
                                focusRequester = focusRequesters[index],
                                isFocused = isFocused,
                                onFocusChanged = { isFocused = it },
                                modifier = Modifier
                            )
                        }
                    }
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentDirectory.parent != null) {
                    Button(
                        onClick = { currentDirectory = currentDirectory.parentFile },
                        modifier = Modifier
                            .weight(1f)
                            .focusable(enabled = isRemoteMode)
                            .focusRequester(upButtonFocus)
                            .then(focusIndicatorModifier(isFocused = false))
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionCenter, Key.Enter -> {
                                            currentDirectory = currentDirectory.parentFile
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.ruTvColors.cardBackground,
                            contentColor = MaterialTheme.ruTvColors.textPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = stringResource(R.string.file_browser_up),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.file_browser_up))
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .focusable(enabled = isRemoteMode)
                        .focusRequester(cancelButtonFocus)
                        .then(focusIndicatorModifier(isFocused = false)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.ruTvColors.cardBackground,
                        contentColor = MaterialTheme.ruTvColors.textPrimary
                    )
                ) {
                    Text(stringResource(R.string.file_browser_cancel))
                }
            }
        }
    }
}

@Composable
private fun FileBrowserItem(
    name: String,
    isDirectory: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isRemoteMode = DeviceHelper.isRemoteInputActive()

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .focusable(enabled = isRemoteMode)
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .then(focusIndicatorModifier(isFocused = isFocused))
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && isFocused && isRemoteMode) {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            onClick()
                            true
                        }
                        else -> false
                    }
                } else false
            },
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused && isRemoteMode) {
                MaterialTheme.ruTvColors.selectedBackground
            } else {
                MaterialTheme.ruTvColors.cardBackground
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = if (isDirectory) MaterialTheme.ruTvColors.gold else MaterialTheme.ruTvColors.textSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.ruTvColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

