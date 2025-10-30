package com.videoplayer.presentation

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videoplayer.presentation.settings.SettingsViewModel
import com.videoplayer.ui.screens.SettingsScreen
import com.videoplayer.ui.theme.RuTvTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * Settings Activity - Refactored to use Jetpack Compose
 * Modern MVVM architecture with Compose UI
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        setContent {
            RuTvTheme {
                SettingsScreenWrapper()
            }
        }

        Timber.d("SettingsActivity created with Compose UI")
    }

    @Composable
    private fun SettingsScreenWrapper() {
        val viewState by viewModel.viewState.collectAsStateWithLifecycle()

        // Show error/success messages
        LaunchedEffect(viewState.error) {
            viewState.error?.let { error ->
                Toast.makeText(this@SettingsActivity, error, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        LaunchedEffect(viewState.successMessage) {
            viewState.successMessage?.let { message ->
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
                viewModel.clearSuccess()
            }
        }

        SettingsScreen(
            viewState = viewState,
            onLoadFile = { content, displayName ->
                viewModel.savePlaylistFromFile(content, displayName)
                finish()
            },
            onLoadUrl = { url ->
                viewModel.savePlaylistFromUrl(url)
                finish()
            },
            onReloadPlaylist = {
                viewModel.reloadPlaylist()
            },
            onDebugLogChanged = { enabled ->
                viewModel.setDebugLogEnabled(enabled)
            },
            onFfmpegAudioChanged = { enabled ->
                viewModel.setFfmpegAudioEnabled(enabled)
            },
            onFfmpegVideoChanged = { enabled ->
                viewModel.setFfmpegVideoEnabled(enabled)
            },
            onBufferSecondsChanged = { seconds ->
                viewModel.setBufferSeconds(seconds)
            },
            onEpgUrlChanged = { url ->
                viewModel.saveEpgUrl(url)
            },
            onEpgDaysAheadChanged = { days ->
                viewModel.setEpgDaysAhead(days)
                },
                onEpgDaysPastChanged = { days ->
                    viewModel.setEpgDaysPast(days)
                },
                onEpgPageDaysChanged = { days ->
                    viewModel.setEpgPageDays(days)
            },
            onBack = { finish() },
            modifier = Modifier.fillMaxSize()
        )
    }

}
