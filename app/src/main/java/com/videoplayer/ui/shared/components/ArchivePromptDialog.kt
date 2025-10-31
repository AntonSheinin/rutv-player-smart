package com.videoplayer.ui.shared.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.videoplayer.R
import com.videoplayer.presentation.main.ArchivePrompt
import com.videoplayer.ui.theme.ruTvColors
import androidx.compose.material3.MaterialTheme

/**
 * Dialog shown when archive playback finishes, offering to continue with next program
 */
@Composable
fun ArchivePromptDialog(
    prompt: ArchivePrompt,
    onContinue: () -> Unit,
    onBackToLive: () -> Unit
) {
    val hasNext = prompt.nextProgram != null
    val message = if (hasNext) {
        stringResource(R.string.archive_prompt_message, prompt.nextProgram?.title ?: "")
    } else {
        stringResource(R.string.archive_prompt_message_no_next)
    }

    AlertDialog(
        onDismissRequest = onBackToLive,
        title = {
            Text(
                text = stringResource(R.string.archive_prompt_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.ruTvColors.gold
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.ruTvColors.textPrimary
            )
        },
        confirmButton = {
            if (hasNext) {
                TextButton(onClick = onContinue) {
                    Text(
                        text = stringResource(R.string.archive_prompt_continue),
                        color = MaterialTheme.ruTvColors.gold
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onBackToLive) {
                Text(
                    text = stringResource(R.string.player_return_to_live),
                    color = MaterialTheme.ruTvColors.textPrimary
                )
            }
        },
        containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.95f)
    )
}
