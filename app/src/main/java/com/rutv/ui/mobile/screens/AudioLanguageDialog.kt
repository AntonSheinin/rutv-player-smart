package com.rutv.ui.mobile.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rutv.R
import com.rutv.ui.shared.components.focusIndicatorModifier
import com.rutv.ui.shared.components.requestFocusSafely
import com.rutv.ui.theme.ruTvColors
import com.rutv.util.DeviceHelper
import kotlinx.coroutines.launch

@Composable
internal fun AudioLanguageDialog(
    trackLabels: List<String>,
    selectedTrackLabel: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequesters = remember(trackLabels) { List(trackLabels.size) { FocusRequester() } }
    val emptyStateFocusRequester = remember { FocusRequester() }
    val initialIndex = trackLabels.indexOf(selectedTrackLabel).takeIf { it >= 0 } ?: 0
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var emptyStateFocused by remember { mutableStateOf(false) }
    val requestLanguageFocus: (Int) -> Unit = { targetIndex ->
        if (listState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) {
            focusRequesters[targetIndex].requestFocusSafely()
        } else {
            coroutineScope.launch {
                listState.scrollToItem(targetIndex)
                withFrameNanos { }
                focusRequesters[targetIndex].requestFocusSafely()
            }
        }
    }

    LaunchedEffect(trackLabels, selectedTrackLabel) {
        if (trackLabels.isEmpty()) {
            withFrameNanos { }
            emptyStateFocusRequester.requestFocusSafely()
            return@LaunchedEffect
        }
        listState.scrollToItem(initialIndex)
        withFrameNanos { }
        focusRequesters.getOrNull(initialIndex)?.requestFocusSafely()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.97f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.dialog_title_audio_language),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.ruTvColors.gold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )

                if (trackLabels.isEmpty()) {
                    Text(
                        text = stringResource(R.string.player_audio_language_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.ruTvColors.textPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                    )
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.End)
                            .focusRequester(emptyStateFocusRequester)
                            .onFocusChanged { emptyStateFocused = it.isFocused }
                            .focusable()
                            .then(focusIndicatorModifier(emptyStateFocused))
                            .onKeyEvent { event ->
                                if (!emptyStateFocused || !DeviceHelper.isRemoteInputActive() ||
                                    event.type != KeyEventType.KeyDown
                                ) {
                                    return@onKeyEvent false
                                }
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter, Key.Back -> {
                                        onDismiss()
                                        true
                                    }
                                    Key.DirectionUp,
                                    Key.DirectionDown,
                                    Key.DirectionLeft,
                                    Key.DirectionRight -> true
                                    else -> false
                                }
                            }
                    ) {
                        Text(stringResource(R.string.button_close))
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(trackLabels, key = { _, label -> label }) { index, label ->
                            var isFocused by remember(label) { mutableStateOf(false) }
                            val select = { onSelect(label) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = select)
                                    .focusRequester(focusRequesters[index])
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .focusable()
                                    .then(focusIndicatorModifier(isFocused))
                                    .onKeyEvent { event ->
                                        if (!isFocused || !DeviceHelper.isRemoteInputActive() ||
                                            event.type != KeyEventType.KeyDown
                                        ) {
                                            return@onKeyEvent false
                                        }
                                        when (event.key) {
                                            Key.DirectionCenter, Key.Enter -> {
                                                select()
                                                true
                                            }
                                            Key.DirectionUp -> {
                                                val targetIndex = index - 1
                                                if (targetIndex >= 0) {
                                                    requestLanguageFocus(targetIndex)
                                                }
                                                true
                                            }
                                            Key.DirectionDown -> {
                                                val targetIndex = index + 1
                                                if (targetIndex < trackLabels.size) {
                                                    requestLanguageFocus(targetIndex)
                                                }
                                                true
                                            }
                                            Key.DirectionLeft, Key.DirectionRight -> true
                                            Key.Back -> {
                                                onDismiss()
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = label == selectedTrackLabel,
                                    onClick = null
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.ruTvColors.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
