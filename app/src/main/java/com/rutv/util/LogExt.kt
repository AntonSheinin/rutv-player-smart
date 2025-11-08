package com.rutv.util

import com.rutv.BuildConfig
import timber.log.Timber

/**
 * Centralizes verbose logging checks so release builds avoid constructing
 * debug strings altogether.
 */
inline fun logDebug(message: () -> String) {
    if (BuildConfig.DEBUG) {
        Timber.d(message())
    }
}
