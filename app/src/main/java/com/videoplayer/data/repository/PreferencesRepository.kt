package com.videoplayer.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.videoplayer.data.model.PlayerConfig
import com.videoplayer.data.model.PlaylistSource
import com.videoplayer.util.Constants
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

    // Keys
    private object PreferencesKeys {
        val PLAYLIST_TYPE = stringPreferencesKey("playlist_type")
        val PLAYLIST_CONTENT = stringPreferencesKey("playlist_content")
        val PLAYLIST_URL = stringPreferencesKey("playlist_url")
        val PLAYLIST_HASH = stringPreferencesKey("playlist_hash")

        val EPG_URL = stringPreferencesKey("epg_url")

        val USE_FFMPEG_AUDIO = booleanPreferencesKey("use_ffmpeg_audio")
        val USE_FFMPEG_VIDEO = booleanPreferencesKey("use_ffmpeg_video")
        val BUFFER_SECONDS = intPreferencesKey("buffer_seconds")
        val SHOW_DEBUG_LOG = booleanPreferencesKey("show_debug_log")

        val LAST_PLAYED_INDEX = intPreferencesKey("last_played_index")
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
                    PlaylistSource.File(content)
                }
                PlaylistSource.TYPE_URL -> {
                    val url = preferences[PreferencesKeys.PLAYLIST_URL] ?: ""
                    PlaylistSource.Url(url)
                }
                else -> PlaylistSource.None
            }
        }

    suspend fun savePlaylistFromFile(content: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYLIST_TYPE] = PlaylistSource.TYPE_FILE
            preferences[PreferencesKeys.PLAYLIST_CONTENT] = content
            preferences.remove(PreferencesKeys.PLAYLIST_URL)
        }
        Timber.d("Saved playlist from file")
    }

    suspend fun savePlaylistFromUrl(url: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYLIST_TYPE] = PlaylistSource.TYPE_URL
            preferences[PreferencesKeys.PLAYLIST_URL] = url
            preferences.remove(PreferencesKeys.PLAYLIST_CONTENT)
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
     * Player configuration
     */
    val playerConfig: Flow<PlayerConfig> = dataStore.data
        .map { preferences ->
            PlayerConfig(
                useFfmpegAudio = preferences[PreferencesKeys.USE_FFMPEG_AUDIO] ?: false,
                useFfmpegVideo = preferences[PreferencesKeys.USE_FFMPEG_VIDEO] ?: false,
                bufferSeconds = preferences[PreferencesKeys.BUFFER_SECONDS] ?: Constants.DEFAULT_BUFFER_SECONDS,
                showDebugLog = preferences[PreferencesKeys.SHOW_DEBUG_LOG] ?: true
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
     * Clear all preferences
     */
    suspend fun clearAll() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
        Timber.d("Cleared all preferences")
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
}
