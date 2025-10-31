package com.videoplayer.ui.shared.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.videoplayer.R
import com.videoplayer.ui.shared.presentation.LayoutConstants
import com.videoplayer.ui.theme.ruTvColors
import kotlinx.coroutines.delay

/**
 * Toast notification for EPG loading status
 */
@Composable
fun EpgNotificationToast(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (message != null) {
            LaunchedEffect(message) {
                delay(2000)
                onDismiss()
            }
            AnimatedVisibility(
                visible = true,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = LayoutConstants.NotificationTopPadding)
            ) {
            Surface(
                shape = RoundedCornerShape(LayoutConstants.NotificationCornerRadius),
                color = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.9f)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.ruTvColors.gold,
                    modifier = Modifier.padding(
                        horizontal = LayoutConstants.NotificationHorizontalPadding,
                        vertical = LayoutConstants.NotificationVerticalPadding
                    )
                )
            }
        }
    }
    }
}
