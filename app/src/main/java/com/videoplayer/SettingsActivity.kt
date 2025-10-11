package com.videoplayer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var btnLoadFile: Button
    private lateinit var btnLoadUrl: Button
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
        
        btnLoadFile = findViewById(R.id.btn_load_file)
        btnLoadUrl = findViewById(R.id.btn_load_url)
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
        
        setupButtons()
        setupDebugLogSwitch()
        setupFfmpegAudioSwitch()
        setupFfmpegVideoSwitch()
        setupBufferInput()
        setupEpgUrlInput()
        updatePlaylistInfo()
    }
    
    override fun onPause() {
        super.onPause()
        saveBufferValue()
        saveEpgUrl()
    }
    
    private fun saveBufferValue() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val text = inputBufferSeconds.text.toString()
        val value = text.toIntOrNull() ?: 15
        val clampedValue = value.coerceIn(5, 60)
        prefs.edit().putInt(KEY_BUFFER_SECONDS, clampedValue).apply()
    }
    
    private fun setupButtons() {
        btnLoadFile.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }
        
        btnLoadUrl.setOnClickListener {
            showUrlDialog()
        }
        
        btnBack.setOnClickListener {
            finish()
        }
    }
    
    private fun setupDebugLogSwitch() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        switchDebugLog.isChecked = prefs.getBoolean(KEY_SHOW_DEBUG_LOG, true)
        
        updateSwitchColor(switchDebugLog, switchDebugLog.isChecked)
        
        switchDebugLog.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SHOW_DEBUG_LOG, isChecked).apply()
            updateSwitchColor(switchDebugLog, isChecked)
        }
    }
    
    private fun setupFfmpegAudioSwitch() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        switchFfmpegAudio.isChecked = prefs.getBoolean(KEY_USE_FFMPEG_AUDIO, false)
        
        updateSwitchColor(switchFfmpegAudio, switchFfmpegAudio.isChecked)
        
        switchFfmpegAudio.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_USE_FFMPEG_AUDIO, isChecked).apply()
            updateSwitchColor(switchFfmpegAudio, isChecked)
        }
    }
    
    private fun setupFfmpegVideoSwitch() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        switchFfmpegVideo.isChecked = prefs.getBoolean(KEY_USE_FFMPEG_VIDEO, false)
        
        updateSwitchColor(switchFfmpegVideo, switchFfmpegVideo.isChecked)
        
        switchFfmpegVideo.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_USE_FFMPEG_VIDEO, isChecked).apply()
            updateSwitchColor(switchFfmpegVideo, isChecked)
        }
    }
    
    private fun updateSwitchColor(switch: Switch, isChecked: Boolean) {
        if (isChecked) {
            switch.thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFD700"))
            switch.trackTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#80FFD700"))
        } else {
            switch.thumbTintList = null
            switch.trackTintList = null
        }
    }
    
    private fun setupBufferInput() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bufferSeconds = prefs.getInt(KEY_BUFFER_SECONDS, 15)
        inputBufferSeconds.setText(bufferSeconds.toString())
        
        inputBufferSeconds.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = inputBufferSeconds.text.toString()
                val value = text.toIntOrNull() ?: 15
                val clampedValue = value.coerceIn(5, 60)
                
                inputBufferSeconds.setText(clampedValue.toString())
                prefs.edit().putInt(KEY_BUFFER_SECONDS, clampedValue).apply()
            }
        }
    }
    
    private fun setupEpgUrlInput() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val epgUrl = prefs.getString(KEY_EPG_URL, "")
        inputEpgUrl.setText(epgUrl)
    }
    
    private fun saveEpgUrl() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val url = inputEpgUrl.text.toString().trim()
        prefs.edit().putString(KEY_EPG_URL, url).apply()
    }
    
    private fun showUrlDialog() {
        val input = EditText(this)
        input.hint = "Enter M3U/M3U8 URL"
        
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentUrl = prefs.getString(KEY_PLAYLIST_URL, "")
        input.setText(currentUrl)
        
        AlertDialog.Builder(this)
            .setTitle("Load Playlist from URL")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString()
                if (url.isNotBlank()) {
                    savePlaylistUrl(url)
                    loadPlaylistFromUrl(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun loadPlaylistFromUri(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            content?.let { 
                savePlaylistContent(it)
                finish()
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Failed to load playlist: ${e.message}", e)
        }
    }
    
    private fun loadPlaylistFromUrl(url: String) {
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    URL(url).readText()
                }
                savePlaylistUrl(url)
                finish()
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Failed to load URL: ${e.message}", e)
            }
        }
    }
    
    private fun savePlaylistContent(content: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        if (content.length > 500000) {
            Log.e("SettingsActivity", "Playlist too large: ${content.length} bytes")
            return
        }
        
        prefs.edit().apply {
            putString(KEY_PLAYLIST_CONTENT, content)
            putString(KEY_PLAYLIST_TYPE, TYPE_FILE)
            remove(KEY_PLAYLIST_URL)
            apply()
        }
    }
    
    private fun savePlaylistUrl(url: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_PLAYLIST_URL, url)
            putString(KEY_PLAYLIST_TYPE, TYPE_URL)
            remove(KEY_PLAYLIST_CONTENT)
            apply()
        }
    }
    
    private fun updatePlaylistInfo() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val playlistType = prefs.getString(KEY_PLAYLIST_TYPE, null)
        
        when (playlistType) {
            TYPE_FILE -> {
                currentPlaylistInfo.text = "Loaded from file (stored locally)"
                currentPlaylistUrl.visibility = android.view.View.GONE
                currentFileName.text = "File"
                currentUrlName.text = "None"
            }
            TYPE_URL -> {
                val url = prefs.getString(KEY_PLAYLIST_URL, "")
                currentPlaylistInfo.text = "Loaded from URL"
                currentPlaylistUrl.text = url
                currentPlaylistUrl.visibility = android.view.View.VISIBLE
                currentFileName.text = "None"
                currentUrlName.text = url?.substringAfterLast('/')?.take(20) ?: "URL"
            }
            else -> {
                currentPlaylistInfo.text = "No playlist loaded"
                currentPlaylistUrl.visibility = android.view.View.GONE
                currentFileName.text = "None"
                currentUrlName.text = "None"
            }
        }
    }
    
    companion object {
        const val PREFS_NAME = "VideoPlayerPrefs"
        const val KEY_PLAYLIST_CONTENT = "playlist_content"
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_PLAYLIST_TYPE = "playlist_type"
        const val KEY_SHOW_DEBUG_LOG = "show_debug_log"
        const val KEY_USE_FFMPEG_AUDIO = "use_ffmpeg_audio"
        const val KEY_USE_FFMPEG_VIDEO = "use_ffmpeg_video"
        const val KEY_BUFFER_SECONDS = "buffer_seconds"
        const val KEY_EPG_URL = "epg_url"
        const val TYPE_FILE = "file"
        const val TYPE_URL = "url"
    }
}
