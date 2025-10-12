package com.videoplayer.util

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Context extensions
 */
fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

/**
 * SharedPreferences extensions
 */
inline fun SharedPreferences.edit(block: SharedPreferences.Editor.() -> Unit) {
    edit().apply(block).apply()
}

fun SharedPreferences.getStringOrEmpty(key: String): String {
    return getString(key, "") ?: ""
}

fun SharedPreferences.getIntOrDefault(key: String, default: Int): Int {
    return getInt(key, default)
}

fun SharedPreferences.getBooleanOrDefault(key: String, default: Boolean): Boolean {
    return getBoolean(key, default)
}

/**
 * Flow extensions
 */
fun <T> Flow<T>.catchAndLog(tag: String): Flow<T> = this.catch { exception ->
    Timber.tag(tag).e(exception, "Flow error")
}

/**
 * Number extensions
 */
fun Int.coerceInRange(min: Int, max: Int): Int = coerceIn(min, max)

/**
 * String extensions
 */
fun String?.orEmpty(): String = this ?: ""

fun String.isValidUrl(): Boolean {
    return try {
        java.net.URL(this)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Collection extensions
 */
fun <T> List<T>.getOrDefault(index: Int, default: T): T {
    return getOrNull(index) ?: default
}

/**
 * Safe execution
 */
inline fun <T> safeExecute(block: () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Exception) {
        Timber.e(e, "Safe execution failed")
        Result.Error(e)
    }
}

suspend inline fun <T> safeExecuteSuspend(crossinline block: suspend () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Exception) {
        Timber.e(e, "Safe suspend execution failed")
        Result.Error(e)
    }
}
