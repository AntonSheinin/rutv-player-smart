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
        
        setupButtons()
        setupDebugLogSwitch()
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
        
        switchDebugLog.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SHOW_DEBUG_LOG, isChecked).apply()
            Toast.makeText(this, if (isChecked) "Debug log enabled" else "Debug log disabled", Toast.LENGTH_SHORT).show()
        }
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
        const val TYPE_FILE = "file"
        const val TYPE_URL = "url"
    }
}
