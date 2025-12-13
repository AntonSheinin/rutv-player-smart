package com.rutv.ui.shared.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import com.rutv.ui.shared.presentation.LayoutConstants
import com.rutv.ui.theme.ruTvColors

/**
 * Toast notification for EPG loading status
 */
@Composable
fun EpgNotificationToast(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        if (message.isNullOrBlank()) return@LaunchedEffect
        // Use Material's built-in duration instead of explicit delay().
        hostState.showSnackbar(
            message = message,
            duration = SnackbarDuration.Short
        )
        onDismiss()
    }

    Box(modifier = modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = hostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = LayoutConstants.NotificationTopPadding)
        ) { snackbarData ->
            Snackbar(
                snackbarData = snackbarData,
                shape = RoundedCornerShape(LayoutConstants.NotificationCornerRadius),
                containerColor = MaterialTheme.ruTvColors.darkBackground.copy(alpha = 0.9f),
                contentColor = MaterialTheme.ruTvColors.gold
            )
        }
    }
}
