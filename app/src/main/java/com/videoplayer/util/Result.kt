package com.videoplayer.util

/**
 * Lightweight sealed result type used across the player codebase.
 * Provides type-safe error handling for asynchronous operations.
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Exception, val message: String? = exception.message) : Result<Nothing>()
    object Loading : Result<Nothing>()
}
