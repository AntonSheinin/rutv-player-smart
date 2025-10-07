package com.videoplayer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
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
    private lateinit var switchFfmpeg: Switch
    private lateinit var inputBufferSeconds: EditText
    private var isSettingBufferText = false
    
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
        switchFfmpeg = findViewById(R.id.switch_ffmpeg)
        inputBufferSeconds = findViewById(R.id.input_buffer_seconds)
        
        setupButtons()
        setupDebugLogSwitch()
        setupFfmpegSwitch()
        setupBufferInput()
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
            Toast.makeText(this, if (isChecked) "Debug log enabled" else "Debug log disabled", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupFfmpegSwitch() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        switchFfmpeg.isChecked = prefs.getBoolean(KEY_USE_FFMPEG, true)
        
        updateSwitchColor(switchFfmpeg, switchFfmpeg.isChecked)
        
        switchFfmpeg.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_USE_FFMPEG, isChecked).apply()
            updateSwitchColor(switchFfmpeg, isChecked)
            Toast.makeText(this, if (isChecked) "FFmpeg audio decoder enabled" else "Hardware decoder only", Toast.LENGTH_SHORT).show()
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
        
        inputBufferSeconds.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isSettingBufferText) return
                
                val text = s?.toString() ?: ""
                if (text.isEmpty()) return
                
                val value = text.toIntOrNull() ?: 15
                val clampedValue = value.coerceIn(5, 60)
                
                if (clampedValue != value) {
                    isSettingBufferText = true
                    inputBufferSeconds.setText(clampedValue.toString())
                    inputBufferSeconds.setSelection(clampedValue.toString().length)
                    isSettingBufferText = false
                }
                
                prefs.edit().putInt(KEY_BUFFER_SECONDS, clampedValue).apply()
            }
        })
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
                Toast.makeText(this, "Playlist saved!", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load playlist: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun loadPlaylistFromUrl(url: String) {
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    URL(url).readText()
                }
                savePlaylistUrl(url)
                Toast.makeText(this@SettingsActivity, "Playlist URL saved!", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Failed to load URL: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun savePlaylistContent(content: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        if (content.length > 500000) {
            Toast.makeText(this, "⚠️ Playlist too large (${content.length} bytes). Use URL mode instead.", Toast.LENGTH_LONG).show()
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
    
    companion object {
        const val PREFS_NAME = "VideoPlayerPrefs"
        const val KEY_PLAYLIST_CONTENT = "playlist_content"
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_PLAYLIST_TYPE = "playlist_type"
        const val KEY_SHOW_DEBUG_LOG = "show_debug_log"
        const val KEY_USE_FFMPEG = "use_ffmpeg"
        const val KEY_BUFFER_SECONDS = "buffer_seconds"
        const val TYPE_FILE = "file"
        const val TYPE_URL = "url"
    }
}
