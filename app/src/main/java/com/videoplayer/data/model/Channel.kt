package com.videoplayer.data.model

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Domain model for a channel.
 * This is the model used throughout the app.
 */
@UnstableApi
data class Channel(
    val url: String,
    val title: String,
    val logo: String = "",
    val group: String = "General",
    val tvgId: String = "",
    val catchupDays: Int = 0,
    val catchupSource: String = "",
    val isFavorite: Boolean = false,
    val aspectRatio: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val position: Int = 0
) {
    val hasEpg: Boolean
        get() = tvgId.isNotBlank() && catchupDays > 0

    fun supportsCatchup(): Boolean = hasEpg

    fun buildArchiveUrl(program: EpgProgram): String? {
        if (!hasEpg) return null
        val template = if (catchupSource.isBlank()) "?utc={utc}&l={duration}" else catchupSource

        val startSeconds = (program.startTimeMillis / 1000L).coerceAtLeast(0)
        val durationSeconds = ((program.stopTimeMillis - program.startTimeMillis) / 1000L)
            .coerceAtLeast(60)

        val filled = template
            .replace("{utc}", startSeconds.toString())
            .replace("{start}", startSeconds.toString())
            .replace("{duration}", durationSeconds.toString())

        val baseUri = java.net.URI(url)
        val baseQuery = baseUri.rawQuery ?: ""
        val basePath = baseUri.rawPath ?: ""
        val baseDir = run {
            val lastSlash = basePath.lastIndexOf('/')
            when {
                lastSlash >= 0 -> basePath.substring(0, lastSlash + 1)
                basePath.isEmpty() -> "/"
                else -> "/"
            }
        }

        fun mergeQuery(primary: String, secondary: String): String {
            return listOf(primary, secondary)
                .map { it.trim('?', '&') }
                .filter { it.isNotEmpty() }
                .joinToString("&")
        }

        return when {
            filled.startsWith("http://", true) || filled.startsWith("https://", true) -> filled
            filled.startsWith("?") -> {
                val extra = filled.removePrefix("?")
                val mergedQuery = mergeQuery(baseQuery, extra)
                java.net.URI(
                    baseUri.scheme,
                    baseUri.authority,
                    baseUri.rawPath,
                    mergedQuery.ifEmpty { null },
                    null
                ).toString()
            }
            filled.startsWith("&") -> {
                val extra = filled.removePrefix("&")
                val mergedQuery = mergeQuery(baseQuery, extra)
                java.net.URI(
                    baseUri.scheme,
                    baseUri.authority,
                    baseUri.rawPath,
                    mergedQuery.ifEmpty { null },
                    null
                ).toString()
            }
            else -> {
                val pathWithTemplate = if (filled.startsWith("/")) {
                    filled
                } else {
                    val dir = if (baseDir.startsWith("/")) baseDir else "/$baseDir"
                    dir + filled
                }
                val pathParts = pathWithTemplate.split("?", limit = 2)
                val newPath = pathParts[0]
                val extraQuery = if (pathParts.size > 1) pathParts[1] else ""
                val mergedQuery = mergeQuery(extraQuery, baseQuery)

                java.net.URI(
                    baseUri.scheme,
                    baseUri.authority,
                    newPath,
                    mergedQuery.ifEmpty { null },
                    null
                ).toString()
            }
        }
    }
}

