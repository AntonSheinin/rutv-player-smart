package com.rutv.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.rutv.data.model.PlayerConfig
import com.rutv.data.model.PlaylistSource
import com.rutv.util.PlayerConstants
import com.rutv.util.logDebug
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Repository for app preferences using DataStore
 * Replaces SharedPreferences for better async handling
 *
 * Why do we still use SharedPreferences at all?
 * - Locale selection must be applied in `attachBaseContext(...)` before most Android components
 *   are created.
 * - DataStore is async; reading it synchronously at that point is awkward.
 * - Therefore, language is written to both:
 *   - SharedPreferences (sync read via [getAppLanguageSync])
 *   - DataStore (Flow-based observation for screens)
 */
@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dataStore = context.dataStore
    private val playlistCacheFile = File(context.filesDir, "playlist_cache.m3u8")

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

        val FAVORITE_URLS = stringSetPreferencesKey("favorite_urls")
        val FAVORITE_TVG_IDS = stringSetPreferencesKey("favorite_tvg_ids")

        val EPG_URL = stringPreferencesKey("epg_url")
        val EPG_DAYS_AHEAD = intPreferencesKey("epg_days_ahead")
        val EPG_DAYS_PAST = intPreferencesKey("epg_days_past")
        val EPG_PAGE_DAYS = intPreferencesKey("epg_page_days")

        val USE_FFMPEG_AUDIO = booleanPreferencesKey("use_ffmpeg_audio")
        val USE_FFMPEG_VIDEO = booleanPreferencesKey("use_ffmpeg_video")
        val BUFFER_SECONDS = intPreferencesKey("buffer_seconds")
        val CONTROLS_HIDE_DELAY_SECONDS = intPreferencesKey("controls_hide_delay_seconds")
        val SHOW_DEBUG_LOG = booleanPreferencesKey("show_debug_log")

        val AUTO_RETRY_ENABLED = booleanPreferencesKey("auto_retry_enabled")
        val AUTO_RETRY_MAX_ATTEMPTS = intPreferencesKey("auto_retry_max_attempts")
        val AUTO_RETRY_PERIOD_SECONDS = intPreferencesKey("auto_retry_period_seconds")

        // UI performance/UX: showing per-channel "current program" in the playlist requires
        // frequent state updates as time progresses. Some devices prefer a simpler list.
        val SHOW_CURRENT_PROGRAM_IN_CHANNEL_LIST = booleanPreferencesKey("show_current_program_in_channel_list")
        val CHANNEL_PREVIEW_ENABLED = booleanPreferencesKey("channel_preview_enabled")

        val LAST_PLAYED_INDEX = intPreferencesKey("last_played_index")

        val APP_LANGUAGE = stringPreferencesKey("app_language")

        val EXTERNAL_CONFIG_IMPORTED_HASH = stringPreferencesKey("external_config_imported_hash")
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
                    val displayName = preferences[PreferencesKeys.PLAYLIST_FILE_NAME]
                    val content = readPlaylistFile()
                    if (content != null) {
                        PlaylistSource.File(content, displayName)
                    } else {
                        // Migration: DataStore still has content from before file-based storage
                        val legacyContent = preferences[PreferencesKeys.PLAYLIST_CONTENT]
                        if (!legacyContent.isNullOrEmpty()) {
                            writePlaylistFile(legacyContent)
                            PlaylistSource.File(legacyContent, displayName)
                        } else {
                            PlaylistSource.None
                        }
                    }
                }
                PlaylistSource.TYPE_URL -> {
                    val url = preferences[PreferencesKeys.PLAYLIST_URL] ?: ""
                    PlaylistSource.Url(url)
                }
                else -> PlaylistSource.None
            }
        }

    suspend fun savePlaylistFromFile(content: String, displayName: String?) {
        writePlaylistFile(content)
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYLIST_TYPE] = PlaylistSource.TYPE_FILE
            preferences.remove(PreferencesKeys.PLAYLIST_CONTENT) // no longer stored in DataStore
            if (!displayName.isNullOrBlank()) {
                preferences[PreferencesKeys.PLAYLIST_FILE_NAME] = displayName
            } else {
                preferences.remove(PreferencesKeys.PLAYLIST_FILE_NAME)
            }
            preferences.remove(PreferencesKeys.PLAYLIST_URL)
        }
        logDebug { "Saved playlist from file (${content.length} chars)" }
    }

    suspend fun savePlaylistFromUrl(url: String) {
        playlistCacheFile.delete()
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYLIST_TYPE] = PlaylistSource.TYPE_URL
            preferences[PreferencesKeys.PLAYLIST_URL] = url
            preferences.remove(PreferencesKeys.PLAYLIST_CONTENT)
            preferences.remove(PreferencesKeys.PLAYLIST_FILE_NAME)
        }
        logDebug { "Saved playlist from URL" }
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
     * Favorite channels (backup storage; Room remains the primary source).
     */
    val favoriteUrls: Flow<Set<String>> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.FAVORITE_URLS] ?: emptySet()
        }

    val favoriteTvgIds: Flow<Set<String>> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.FAVORITE_TVG_IDS] ?: emptySet()
        }

    suspend fun updateFavorite(url: String, tvgId: String?, isFavorite: Boolean) {
        val normalizedUrl = url.trim()
        val normalizedTvgId = tvgId?.trim().orEmpty()
        dataStore.edit { preferences ->
            val urls = preferences[PreferencesKeys.FAVORITE_URLS]?.toMutableSet() ?: mutableSetOf()
            val tvgIds = preferences[PreferencesKeys.FAVORITE_TVG_IDS]?.toMutableSet() ?: mutableSetOf()
            if (isFavorite) {
                if (normalizedUrl.isNotBlank()) {
                    urls.add(normalizedUrl)
                }
                if (normalizedTvgId.isNotBlank()) {
                    tvgIds.add(normalizedTvgId)
                }
            } else {
                if (normalizedUrl.isNotBlank()) {
                    urls.remove(normalizedUrl)
                }
                if (normalizedTvgId.isNotBlank()) {
                    tvgIds.remove(normalizedTvgId)
                }
            }
            if (urls.isEmpty()) {
                preferences.remove(PreferencesKeys.FAVORITE_URLS)
            } else {
                preferences[PreferencesKeys.FAVORITE_URLS] = urls
            }
            if (tvgIds.isEmpty()) {
                preferences.remove(PreferencesKeys.FAVORITE_TVG_IDS)
            } else {
                preferences[PreferencesKeys.FAVORITE_TVG_IDS] = tvgIds
            }
        }
    }

    suspend fun replaceFavorites(urls: Set<String>, tvgIds: Set<String>) {
        val normalizedUrls = urls.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val normalizedTvgIds = tvgIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        dataStore.edit { preferences ->
            if (normalizedUrls.isEmpty()) {
                preferences.remove(PreferencesKeys.FAVORITE_URLS)
            } else {
                preferences[PreferencesKeys.FAVORITE_URLS] = normalizedUrls
            }
            if (normalizedTvgIds.isEmpty()) {
                preferences.remove(PreferencesKeys.FAVORITE_TVG_IDS)
            } else {
                preferences[PreferencesKeys.FAVORITE_TVG_IDS] = normalizedTvgIds
            }
        }
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
        logDebug { "Saved EPG URL" }
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
        logDebug { "Saved EPG days ahead: $days" }
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
        logDebug { "Saved EPG days past: $days" }
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
        logDebug { "Saved EPG page size (days): $days" }
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
                controlsHideDelaySeconds = (preferences[PreferencesKeys.CONTROLS_HIDE_DELAY_SECONDS]
                    ?: PlayerConstants.DEFAULT_CONTROLS_HIDE_DELAY_SECONDS).coerceIn(
                    PlayerConstants.MIN_CONTROLS_HIDE_DELAY_SECONDS,
                    PlayerConstants.MAX_CONTROLS_HIDE_DELAY_SECONDS
                ),
                showDebugLog = preferences[PreferencesKeys.SHOW_DEBUG_LOG] ?: false
            )
        }

    suspend fun savePlayerConfig(config: PlayerConfig) {
        val controlsHideDelaySeconds = config.controlsHideDelaySeconds.coerceIn(
            PlayerConstants.MIN_CONTROLS_HIDE_DELAY_SECONDS,
            PlayerConstants.MAX_CONTROLS_HIDE_DELAY_SECONDS
        )
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_FFMPEG_AUDIO] = config.useFfmpegAudio
            preferences[PreferencesKeys.USE_FFMPEG_VIDEO] = config.useFfmpegVideo
            preferences[PreferencesKeys.BUFFER_SECONDS] = config.bufferSeconds
            preferences[PreferencesKeys.CONTROLS_HIDE_DELAY_SECONDS] = controlsHideDelaySeconds
            preferences[PreferencesKeys.SHOW_DEBUG_LOG] = config.showDebugLog
        }
        logDebug { "Saved player config: $config" }
    }

    val autoRetryEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.AUTO_RETRY_ENABLED] ?: true
        }

    val autoRetryMaxAttempts: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.AUTO_RETRY_MAX_ATTEMPTS] ?: PlayerConstants.DEFAULT_AUTO_RETRY_MAX_ATTEMPTS
        }

    val autoRetryPeriodSeconds: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.AUTO_RETRY_PERIOD_SECONDS] ?: PlayerConstants.DEFAULT_AUTO_RETRY_PERIOD_SECONDS
        }

    suspend fun saveAutoRetryEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_RETRY_ENABLED] = enabled
        }
        logDebug { "Saved auto-retry enabled: $enabled" }
    }

    suspend fun saveAutoRetryMaxAttempts(attempts: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_RETRY_MAX_ATTEMPTS] = attempts
        }
        logDebug { "Saved auto-retry max attempts: $attempts" }
    }

    suspend fun saveAutoRetryPeriodSeconds(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_RETRY_PERIOD_SECONDS] = seconds
        }
        logDebug { "Saved auto-retry period seconds: $seconds" }
    }

    /**
     * Whether the playlist panel should show "currently airing program" under each channel.
     *
     * When disabled, the UI will skip rendering this text and the ViewModel will avoid pushing
     * frequent `currentProgramsMap` updates, reducing recomposition churn in large playlists.
     */
    val showCurrentProgramInChannelList: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.SHOW_CURRENT_PROGRAM_IN_CHANNEL_LIST] ?: true
        }

    suspend fun saveShowCurrentProgramInChannelList(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_CURRENT_PROGRAM_IN_CHANNEL_LIST] = enabled
        }
        logDebug { "Saved show current program in channel list: $enabled" }
    }

    val channelPreviewEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.CHANNEL_PREVIEW_ENABLED] ?: true
        }

    suspend fun saveChannelPreviewEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CHANNEL_PREVIEW_ENABLED] = enabled
        }
        logDebug { "Saved channel preview enabled: $enabled" }
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
     * Clear playlist cache
     */
    suspend fun clearPlaylistCache() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.PLAYLIST_HASH)
        }
        logDebug { "Cleared playlist cache" }
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
        // Use commit() instead of apply() to ensure it's written immediately.
        // (If the app process is killed right after leaving Settings, we still want the language saved.)
        val success = sharedPrefs.edit().putString(LANGUAGE_KEY, localeCode).commit()
        if (!success) {
            Timber.w("Failed to commit language preference to SharedPreferences")
        }

        // Also save to DataStore (for Flow-based observation)
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LANGUAGE] = localeCode
        }
        logDebug { "Saved app language: $localeCode (commit success: $success)" }
    }

    val externalConfigImportedHash: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.EXTERNAL_CONFIG_IMPORTED_HASH] ?: ""
        }

    suspend fun applyExternalConfig(values: Map<String, String>, normalizedHash: String) {
        val playlistUrl = values[ExternalConfigKeys.PLAYLIST_URL]
        val epgUrl = values[ExternalConfigKeys.EPG_URL]
        val epgDaysAhead = values[ExternalConfigKeys.EPG_DAYS_AHEAD]?.toIntOrNull()
        val epgDaysPast = values[ExternalConfigKeys.EPG_DAYS_PAST]?.toIntOrNull()
        val epgPageDays = values[ExternalConfigKeys.EPG_PAGE_DAYS]?.toIntOrNull()

        if (playlistUrl != null) {
            playlistCacheFile.delete()
        }

        dataStore.edit { preferences ->
            if (playlistUrl != null) {
                preferences[PreferencesKeys.PLAYLIST_TYPE] = PlaylistSource.TYPE_URL
                preferences[PreferencesKeys.PLAYLIST_URL] = playlistUrl
                preferences.remove(PreferencesKeys.PLAYLIST_CONTENT)
                preferences.remove(PreferencesKeys.PLAYLIST_FILE_NAME)
                preferences.remove(PreferencesKeys.PLAYLIST_HASH)
            }
            if (epgUrl != null) {
                preferences[PreferencesKeys.EPG_URL] = epgUrl
            }
            if (epgDaysAhead != null) {
                preferences[PreferencesKeys.EPG_DAYS_AHEAD] = epgDaysAhead
            }
            if (epgDaysPast != null) {
                preferences[PreferencesKeys.EPG_DAYS_PAST] = epgDaysPast
            }
            if (epgPageDays != null) {
                preferences[PreferencesKeys.EPG_PAGE_DAYS] = epgPageDays
            }
            preferences[PreferencesKeys.EXTERNAL_CONFIG_IMPORTED_HASH] = normalizedHash
        }
        logDebug { "Applied external config" }
    }

    private fun readPlaylistFile(): String? {
        return try {
            if (playlistCacheFile.exists()) playlistCacheFile.readText() else null
        } catch (e: IOException) {
            Timber.e(e, "Failed to read playlist cache file")
            null
        }
    }

    private fun writePlaylistFile(content: String) {
        try {
            val tempFile = File(playlistCacheFile.parentFile, "${playlistCacheFile.name}.tmp")
            tempFile.writeText(content)
            if (!tempFile.renameTo(playlistCacheFile)) {
                // renameTo can fail on some filesystems; fall back to direct write
                playlistCacheFile.writeText(content)
                tempFile.delete()
            }
        } catch (e: IOException) {
            Timber.e(e, "Failed to write playlist cache file")
        }
    }
}
