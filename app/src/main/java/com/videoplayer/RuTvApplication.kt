package com.videoplayer

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.videoplayer.util.LocaleHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Application class for RuTV IPTV Player
 */
@HiltAndroidApp
class RuTvApplication : Application(), ImageLoaderFactory {

    override fun attachBaseContext(base: Context) {
        // Load saved language from SharedPreferences (synchronous, safe)
        val localeCode = LocaleHelper.getSavedLanguage(base)
        val context = LocaleHelper.setLocale(base, localeCode)
        super.attachBaseContext(context)
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (isDebuggable()) {
            Timber.plant(Timber.DebugTree())
        }

        // Migrate language from DataStore to SharedPreferences if needed (async, non-blocking)
        migrateLanguagePreference()

        Timber.d("RuTV Application started")
    }
    
    private fun migrateLanguagePreference() {
        // Run in background to avoid blocking startup
        Thread {
            try {
                val sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                // Only migrate if SharedPreferences doesn't have the value yet
                if (!sharedPrefs.contains("app_language")) {
                    // Try to read from DataStore and sync to SharedPreferences
                    // This is a one-time migration
                    val dataStore = applicationContext.dataStore
                    kotlinx.coroutines.runBlocking {
                        try {
                            val prefs = dataStore.data.first()
                            val language = prefs[stringPreferencesKey("app_language")]
                            if (language != null) {
                                sharedPrefs.edit().putString("app_language", language).apply()
                                Timber.d("Migrated language preference to SharedPreferences: $language")
                            }
                        } catch (e: Exception) {
                            // Ignore migration errors, will default to English
                            Timber.d("Language preference migration skipped: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.d("Language preference migration failed: ${e.message}")
            }
        }.start()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(false)
            .allowHardware(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    components {
                        add(ImageDecoderDecoder.Factory())
                    }
                }
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .build()
    }

    private fun isDebuggable(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
