package com.videoplayer.data.remote

import com.videoplayer.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads playlist content from various sources
 */
@Singleton
class PlaylistLoader @Inject constructor() {

    /**
     * Load playlist from URL
     */
    suspend fun loadFromUrl(url: String): Result<String> = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            return@withContext Result.Error(Exception("URL is empty"))
        }

        try {
            Timber.d("Loading playlist from URL: $url")
            val content = URL(url).readText()
            Timber.d("Loaded ${content.length} bytes from URL")
            Result.Success(content)
        } catch (e: Exception) {
            Timber.e(e, "Error loading playlist from URL: $url")
            Result.Error(e)
        }
    }

    /**
     * Validate playlist content size
     */
    fun validateSize(content: String, maxSize: Int = 500_000): Boolean {
        return content.length <= maxSize
    }
}
