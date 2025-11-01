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
        // Load saved language preference synchronously
        val localeCode = try {
            runBlocking {
                base.dataStore.data.first().let { preferences ->
                    preferences[stringPreferencesKey("app_language")] ?: "en"
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load language preference, defaulting to English")
            "en"
        }

        val context = LocaleHelper.setLocale(base, localeCode)
        super.attachBaseContext(context)
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (isDebuggable()) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.d("RuTV Application started")
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
