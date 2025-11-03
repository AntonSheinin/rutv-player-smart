package com.rutv.data.remote

import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import com.rutv.util.Constants
import com.rutv.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

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
        } catch (e: javax.net.ssl.SSLException) {
            Timber.e(e, "SSL/TLS error loading playlist from URL: $url")
            val errorMessage = if (e.message?.contains("parse", ignoreCase = true) == true) {
                "SSL connection error. Please check your network connection or try again."
            } else {
                "SSL error: ${e.message ?: "Connection failed"}"
            }
            Result.Error(Exception(errorMessage, e))
        } catch (e: java.net.SocketException) {
            Timber.e(e, "Network error loading playlist from URL: $url")
            Result.Error(Exception("Network error. Please check your connection and try again.", e))
        } catch (e: java.net.UnknownHostException) {
            Timber.e(e, "Host resolution error for playlist URL: $url")
            Result.Error(Exception("Cannot reach server. Please check the URL and your internet connection.", e))
        } catch (e: Exception) {
            Timber.e(e, "Error loading playlist from URL: $url")
            // Check if it's an SSL-related error in the message
            val errorMessage = if (e.message?.contains("tls", ignoreCase = true) == true ||
                e.message?.contains("ssl", ignoreCase = true) == true ||
                e.message?.contains("parse", ignoreCase = true) == true) {
                "Network error: ${e.message ?: "Connection failed"}. Please try again."
            } else {
                e.message ?: "Failed to load playlist"
            }
            Result.Error(Exception(errorMessage, e))
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
