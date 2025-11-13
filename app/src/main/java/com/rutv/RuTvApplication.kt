package com.rutv

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.rutv.util.LocaleHelper
import dagger.hilt.android.HiltAndroidApp
import com.rutv.util.logDebug
import timber.log.Timber

/**
 * Application class for RuTV
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

        logDebug { "RuTV Application started" }
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
                // OPTIMIZATION: Reduced from 25% to 10% for STB devices
                // Saves 50-150MB on devices with 1GB RAM
                MemoryCache.Builder(this)
                    .maxSizePercent(0.10)
                    .maxSizeBytes(50 * 1024 * 1024) // Cap at 50MB absolute
                    .build()
            }
            .diskCache {
                // OPTIMIZATION: Increased disk cache to compensate
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.05) // Increased from 2% to 5%
                    .build()
            }
            .build()
    }

    private fun isDebuggable(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
