package com.rutv.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.rutv.data.model.PlayerConfig
import com.rutv.data.model.PlaylistSource
import com.rutv.util.PlayerConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Repository for app preferences using DataStore
 * Replaces SharedPreferences for better async handling
 */
@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dataStore = context.dataStore

    // SharedPreferences for language setting (synchronous access in attachBaseContext)
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val LANGUAGE_KEY = "app_language"

    // Keys
    private object PreferencesKeys {
        val PLAYLIST_TYPE = stringPreferencesKey("playlist_type")
        val PLAYLIST_CONTENT = stringPreferencesKey("playlist_content")
        val PLAYLIST_URL = stringPreferencesKey("playlist_url")
        val PLAYLIST_FILE_NAME = stringPreferencesKey("playlist_file_name")
        val PLAYLIST_HASH = stringPreferencesKey("playlist_hash")

        val EPG_URL = stringPreferencesKey("epg_url")
        val EPG_DAYS_AHEAD = intPreferencesKey("epg_days_ahead")
        val EPG_DAYS_PAST = intPreferencesKey("epg_days_past")
        val EPG_PAGE_DAYS = intPreferencesKey("epg_page_days")

        val USE_FFMPEG_AUDIO = booleanPreferencesKey("use_ffmpeg_audio")
        val USE_FFMPEG_VIDEO = booleanPreferencesKey("use_ffmpeg_video")
        val BUFFER_SECONDS = intPreferencesKey("buffer_seconds")
        val SHOW_DEBUG_LOG = booleanPreferencesKey("show_debug_log")

        val LAST_PLAYED_INDEX = intPreferencesKey("last_played_index")
        val LAST_EPG_FETCH_TIMESTAMP = longPreferencesKey("last_epg_fetch_timestamp")

        val APP_LANGUAGE = stringPreferencesKey("app_language")
    }

    /**
     * Playlist source
     */
    val playlistSource: Flow<PlaylistSource> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading playlist source preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val type = preferences[PreferencesKeys.PLAYLIST_TYPE]
            when (type) {
                PlaylistSource.TYPE_FILE -> {
                    val content = preferences[PreferencesKeys.PLAYLIST_CONTENT] ?: ""
                    val displayName = preferences[PreferencesKeys.PLAYLIST_FILE_NAME]
                    PlaylistSource.File(content, displayName)
                }
                PlaylistSource.TYPE_URL -> {
                    val url = preferences[PreferencesKeys.PLAYLIST_URL] ?: ""
                    PlaylistSource.Url(url)
                }
                else -> PlaylistSource.None
            }
        }

    suspend fun savePlaylistFromFile(content: String, displayName: String?) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYLIST_TYPE] = PlaylistSource.TYPE_FILE
            preferences[PreferencesKeys.PLAYLIST_CONTENT] = content
            if (!displayName.isNullOrBlank()) {
                preferences[PreferencesKeys.PLAYLIST_FILE_NAME] = displayName
            } else {
                preferences.remove(PreferencesKeys.PLAYLIST_FILE_NAME)
            }
            preferences.remove(PreferencesKeys.PLAYLIST_URL)
        }
        Timber.d("Saved playlist from file")
    }

    suspend fun savePlaylistFromUrl(url: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYLIST_TYPE] = PlaylistSource.TYPE_URL
            preferences[PreferencesKeys.PLAYLIST_URL] = url
            preferences.remove(PreferencesKeys.PLAYLIST_CONTENT)
            preferences.remove(PreferencesKeys.PLAYLIST_FILE_NAME)
        }
        Timber.d("Saved playlist from URL: $url")
    }

    suspend fun savePlaylistHash(hash: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYLIST_HASH] = hash
        }
    }

    val playlistHash: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.PLAYLIST_HASH] ?: ""
        }

    /**
     * EPG URL
     */
    val epgUrl: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.EPG_URL] ?: ""
        }

    suspend fun saveEpgUrl(url: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EPG_URL] = url
        }
        Timber.d("Saved EPG URL: $url")
    }

    /**
     * EPG Days Ahead - Maximum days ahead for future EPG programs
     * Default: 7 days
     */
    val epgDaysAhead: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.EPG_DAYS_AHEAD] ?: 7
        }

    suspend fun saveEpgDaysAhead(days: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EPG_DAYS_AHEAD] = days
        }
        Timber.d("Saved EPG days ahead: $days")
    }

    /**
     * EPG Days Past (depth) - Maximum past days to show EPG for all channels
     * Default: 14 days
     */
    val epgDaysPast: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.EPG_DAYS_PAST] ?: 14
        }

    suspend fun saveEpgDaysPast(days: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EPG_DAYS_PAST] = days
        }
        Timber.d("Saved EPG days past: $days")
    }

    /**
     * EPG Page Size (days per page) for lazy paging
     * Default: 1 day
     */
    val epgPageDays: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.EPG_PAGE_DAYS] ?: 1
        }

    suspend fun saveEpgPageDays(days: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EPG_PAGE_DAYS] = days
        }
        Timber.d("Saved EPG page size (days): $days")
    }

    /**
     * Player configuration
     */
    val playerConfig: Flow<PlayerConfig> = dataStore.data
        .map { preferences ->
            PlayerConfig(
                useFfmpegAudio = preferences[PreferencesKeys.USE_FFMPEG_AUDIO] ?: false,
                useFfmpegVideo = preferences[PreferencesKeys.USE_FFMPEG_VIDEO] ?: false,
                bufferSeconds = preferences[PreferencesKeys.BUFFER_SECONDS] ?: PlayerConstants.DEFAULT_BUFFER_SECONDS,
                showDebugLog = preferences[PreferencesKeys.SHOW_DEBUG_LOG] ?: false
            )
        }

    suspend fun savePlayerConfig(config: PlayerConfig) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_FFMPEG_AUDIO] = config.useFfmpegAudio
            preferences[PreferencesKeys.USE_FFMPEG_VIDEO] = config.useFfmpegVideo
            preferences[PreferencesKeys.BUFFER_SECONDS] = config.bufferSeconds
            preferences[PreferencesKeys.SHOW_DEBUG_LOG] = config.showDebugLog
        }
        Timber.d("Saved player config: $config")
    }

    /**
     * Last played index
     */
    val lastPlayedIndex: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.LAST_PLAYED_INDEX] ?: 0
        }

    suspend fun saveLastPlayedIndex(index: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_PLAYED_INDEX] = index
        }
    }

    /**
     * Last EPG fetch timestamp
     */
    val lastEpgFetchTimestamp: Flow<Long> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.LAST_EPG_FETCH_TIMESTAMP] ?: 0L
        }

    suspend fun saveLastEpgFetchTimestamp(timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_EPG_FETCH_TIMESTAMP] = timestamp
        }
        Timber.d("Saved last EPG fetch timestamp: $timestamp")
    }

    /**
     * Clear playlist cache
     */
    suspend fun clearPlaylistCache() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.PLAYLIST_HASH)
        }
        Timber.d("Cleared playlist cache")
    }

    /**
     * App language preference
     * Default: "en" (English)
     *
     * Also stored in SharedPreferences for synchronous access in attachBaseContext
     */
    val appLanguage: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.APP_LANGUAGE] ?: "en"
        }

    /**
     * Get app language synchronously (for use in attachBaseContext)
     * Reads from SharedPreferences for immediate access
     */
    fun getAppLanguageSync(): String {
        return sharedPrefs.getString(LANGUAGE_KEY, "en") ?: "en"
    }

    suspend fun saveAppLanguage(localeCode: String) {
        // Save to SharedPreferences first (synchronous, for attachBaseContext)
        // Use commit() instead of apply() to ensure it's written immediately
        val success = sharedPrefs.edit().putString(LANGUAGE_KEY, localeCode).commit()
        if (!success) {
            Timber.w("Failed to commit language preference to SharedPreferences")
        }

        // Also save to DataStore (for Flow-based observation)
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LANGUAGE] = localeCode
        }
        Timber.d("Saved app language: $localeCode (commit success: $success)")
    }
}
