package com.videoplayer.data.remote

import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import com.videoplayer.util.Constants
import com.videoplayer.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads playlist content from various sources
 */
@Singleton
class PlaylistLoader @Inject constructor(
    private val httpFactory: DefaultHttpDataSource.Factory
) {

    /**
     * Load playlist from URL
     */
    suspend fun loadFromUrl(url: String): Result<String> = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext Result.Error(IllegalArgumentException("URL is empty"))

        val dataSource = httpFactory.createDataSource()
        return@withContext try {
            Timber.d("Loading playlist from URL: $url")
            val spec = DataSpec.Builder()
                .setUri(url)
                .setHttpMethod(DataSpec.HTTP_METHOD_GET)
                .build()

            DataSourceInputStream(dataSource, spec).use { stream ->
                val max = Constants.MAX_PLAYLIST_SIZE_BYTES
                val buffer = ByteArray(8 * 1024)
                val out = StringBuilder()
                var total = 0
                while (true) {
                    val toRead = buffer.size.coerceAtMost(max - total)
                    if (toRead <= 0) break
                    val read = stream.read(buffer, 0, toRead)
                    if (read <= 0) break
                    out.append(String(buffer, 0, read, Charsets.UTF_8))
                    total += read
                }
                if (total >= max) {
                    Timber.w("Playlist content reached size cap: $max bytes")
                }
                Timber.d("Loaded ${out.length} bytes from URL")
                Result.Success(out.toString())
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading playlist from URL: $url")
            Result.Error(e)
        } finally {
            runCatching { dataSource.close() }
        }
    }

    /**
     * Validate playlist content size
     */
    fun validateSize(content: String, maxSize: Int = 500_000): Boolean {
        return content.length <= maxSize
    }
}
