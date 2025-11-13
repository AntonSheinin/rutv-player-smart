package com.rutv.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Utility class for managing locale configuration
 * Allows dynamic language switching independent of system locale
 */
object LocaleHelper {

    /**
     * Set locale for the given context
     * Returns a context wrapped with the specified locale
     */
    fun setLocale(context: Context, localeCode: String): Context {
        val locale = when (localeCode) {
            "ru" -> Locale("ru")
            "en" -> Locale("en")
            else -> Locale("en") // Default to English
        }
        return updateLocale(context, locale)
    }

    /**
     * Get saved language from SharedPreferences (synchronous)
     * For use in attachBaseContext where DataStore cannot be accessed
     */
    fun getSavedLanguage(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("app_language", "en") ?: "en"
    }

    /**
     * Update context with the specified locale
     */
    private fun updateLocale(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)

        val resources = context.resources
        val configuration = Configuration(resources.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
            return context.createConfigurationContext(configuration)
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
            return context
        }
    }

    /**
     * Get Locale from locale code string
     */
    fun getLocaleFromCode(localeCode: String): Locale {
        return when (localeCode) {
            "ru" -> Locale("ru")
            "en" -> Locale("en")
            else -> Locale("en")
        }
    }
}

