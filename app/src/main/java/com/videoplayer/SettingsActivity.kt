package com.videoplayer

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.videoplayer.data.model.PlaylistSource
import com.videoplayer.presentation.settings.SettingsViewModel
import com.videoplayer.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Settings Activity - Refactored to use MVVM architecture
 * Reduced from 330 lines to ~250 lines using ViewModel
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    // UI Components
    private lateinit var btnLoadFile: Button
    private lateinit var btnLoadUrl: Button
    private lateinit var btnReloadPlaylist: Button
    private lateinit var btnBack: Button
    private lateinit var switchDebugLog: Switch
    private lateinit var switchFfmpegAudio: Switch
    private lateinit var switchFfmpegVideo: Switch
    private lateinit var inputBufferSeconds: EditText
    private lateinit var currentPlaylistInfo: TextView
    private lateinit var currentPlaylistUrl: TextView
    private lateinit var currentFileName: TextView
    private lateinit var currentUrlName: TextView
    private lateinit var inputEpgUrl: EditText

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadPlaylistFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initializeViews()
        setupButtons()
        setupSwitches()
        setupInputs()
        observeViewModel()

        Timber.d("SettingsActivity created")
    }

    /**
     * Initialize all views
     */
    private fun initializeViews() {
        btnLoadFile = findViewById(R.id.btn_load_file)
        btnLoadUrl = findViewById(R.id.btn_load_url)
        btnReloadPlaylist = findViewById(R.id.btn_reload_playlist)
        btnBack = findViewById(R.id.btn_back)
        switchDebugLog = findViewById(R.id.switch_debug_log)
        switchFfmpegAudio = findViewById(R.id.switch_ffmpeg_audio)
        switchFfmpegVideo = findViewById(R.id.switch_ffmpeg_video)
        inputBufferSeconds = findViewById(R.id.input_buffer_seconds)
        currentPlaylistInfo = findViewById(R.id.current_playlist_info)
        currentPlaylistUrl = findViewById(R.id.current_playlist_url)
        currentFileName = findViewById(R.id.current_file_name)
        currentUrlName = findViewById(R.id.current_url_name)
        inputEpgUrl = findViewById(R.id.input_epg_url)
    }

    /**
     * Setup button click listeners
     */
    private fun setupButtons() {
        btnLoadFile.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        btnLoadUrl.setOnClickListener {
            showUrlDialog()
        }

        btnReloadPlaylist.setOnClickListener {
            showReloadDialog()
        }

        btnBack.setOnClickListener {
            finish()
        }
    }

    /**
     * Setup switch listeners
     */
    private fun setupSwitches() {
        switchDebugLog.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDebugLogEnabled(isChecked)
            updateSwitchColor(switchDebugLog, isChecked)
        }

        switchFfmpegAudio.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFfmpegAudioEnabled(isChecked)
            updateSwitchColor(switchFfmpegAudio, isChecked)
        }

        switchFfmpegVideo.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFfmpegVideoEnabled(isChecked)
            updateSwitchColor(switchFfmpegVideo, isChecked)
        }
    }

    /**
     * Setup input listeners
     */
    private fun setupInputs() {
        inputBufferSeconds.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = inputBufferSeconds.text.toString()
                val value = text.toIntOrNull() ?: Constants.DEFAULT_BUFFER_SECONDS
                val clampedValue = value.coerceIn(
                    Constants.MIN_BUFFER_SECONDS,
                    Constants.MAX_BUFFER_SECONDS
                )

                inputBufferSeconds.setText(clampedValue.toString())
                viewModel.setBufferSeconds(clampedValue)
            }
        }
    }

    /**
     * Observe ViewModel state
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.viewState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    /**
     * Update UI based on state
     */
    private fun updateUI(state: com.videoplayer.presentation.settings.SettingsViewState) {
        // Update playlist info
        currentPlaylistInfo.text = state.playlistInfo

        when (state.playlistSource) {
            is PlaylistSource.Url -> {
                currentPlaylistUrl.visibility = android.view.View.VISIBLE
                currentPlaylistUrl.text = state.playlistUrl
                currentFileName.text = "None"
                currentUrlName.text = state.urlName
            }
            is PlaylistSource.File -> {
                currentPlaylistUrl.visibility = android.view.View.GONE
                currentFileName.text = "File"
                currentUrlName.text = "None"
            }
            is PlaylistSource.None -> {
                currentPlaylistUrl.visibility = android.view.View.GONE
                currentFileName.text = "None"
                currentUrlName.text = "None"
            }
        }

        // Update player config - Disable listeners to prevent infinite loop
        val config = state.playerConfig

        switchDebugLog.setOnCheckedChangeListener(null)
        switchDebugLog.isChecked = config.showDebugLog
        updateSwitchColor(switchDebugLog, config.showDebugLog)
        switchDebugLog.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDebugLogEnabled(isChecked)
            updateSwitchColor(switchDebugLog, isChecked)
        }

        switchFfmpegAudio.setOnCheckedChangeListener(null)
        switchFfmpegAudio.isChecked = config.useFfmpegAudio
        updateSwitchColor(switchFfmpegAudio, config.useFfmpegAudio)
        switchFfmpegAudio.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFfmpegAudioEnabled(isChecked)
            updateSwitchColor(switchFfmpegAudio, isChecked)
        }

        switchFfmpegVideo.setOnCheckedChangeListener(null)
        switchFfmpegVideo.isChecked = config.useFfmpegVideo
        updateSwitchColor(switchFfmpegVideo, config.useFfmpegVideo)
        switchFfmpegVideo.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFfmpegVideoEnabled(isChecked)
            updateSwitchColor(switchFfmpegVideo, isChecked)
        }

        if (inputBufferSeconds.text.toString() != config.bufferSeconds.toString()) {
            inputBufferSeconds.setText(config.bufferSeconds.toString())
        }

        // Update EPG URL - Only if different to prevent cursor jump
        if (inputEpgUrl.text.toString() != state.epgUrl) {
            inputEpgUrl.setText(state.epgUrl)
        }

        // Show error or success messages
        state.error?.let { error ->
            showToast(error)
            viewModel.clearError()
        }

        state.successMessage?.let { message ->
            showToast(message)
            viewModel.clearSuccess()
        }
    }

    /**
     * Update switch color based on state
     */
    private fun updateSwitchColor(switch: Switch, isChecked: Boolean) {
        if (isChecked) {
            switch.thumbTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#FFD700")
            )
            switch.trackTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#80FFD700")
            )
        } else {
            switch.thumbTintList = null
            switch.trackTintList = null
        }
    }

    /**
     * Load playlist from file URI
     */
    private fun loadPlaylistFromUri(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            content?.let {
                viewModel.savePlaylistFromFile(it)
                finish()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load playlist from URI")
            showToast("Failed to load file: ${e.message}")
        }
    }

    /**
     * Show URL input dialog
     */
    private fun showUrlDialog() {
        val input = EditText(this)
        input.hint = "Enter M3U/M3U8 URL"

        val currentUrl = when (val source = viewModel.viewState.value.playlistSource) {
            is PlaylistSource.Url -> source.url
            else -> ""
        }
        input.setText(currentUrl)

        AlertDialog.Builder(this)
            .setTitle("Load Playlist from URL")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString()
                if (url.isNotBlank()) {
                    viewModel.savePlaylistFromUrl(url)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show reload confirmation dialog
     */
    private fun showReloadDialog() {
        val playlistSource = viewModel.viewState.value.playlistSource

        if (playlistSource is PlaylistSource.None) {
            AlertDialog.Builder(this)
                .setTitle("No Playlist")
                .setMessage("No playlist is currently loaded. Please load a playlist first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Reload Playlist?")
            .setMessage("This will reload the playlist from the original source and refresh all EPG data (tvg-id, catchup-days, etc.).")
            .setPositiveButton("Reload") { _, _ ->
                viewModel.reloadPlaylist()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show toast message
     */
    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        // Save EPG URL
        val epgUrl = inputEpgUrl.text.toString()
        viewModel.saveEpgUrl(epgUrl)

        // Save buffer value
        val bufferText = inputBufferSeconds.text.toString()
        val bufferValue = bufferText.toIntOrNull() ?: Constants.DEFAULT_BUFFER_SECONDS
        viewModel.setBufferSeconds(bufferValue)
    }
}
