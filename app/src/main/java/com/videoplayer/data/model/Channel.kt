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

        // Validate EPG times
        if (program.startTimeMillis <= 0 || program.stopTimeMillis <= 0) {
            return null // Invalid EPG data
        }
        if (program.stopTimeMillis <= program.startTimeMillis) {
            return null // Invalid program duration
        }

        // Flussonic DVR default format: ?from={utc}&duration={duration}
        var template = if (catchupSource.isBlank()) "?from={utc}&duration={duration}" else catchupSource

        // Fix common malformed templates
        if (template.startsWith("{") && !template.startsWith("http")) {
            // Template like "{utc}" or "{utc}&duration={duration}" (missing ? or & prefix)
            template = "?from=$template"
        }

        val currentTimeMillis = System.currentTimeMillis()
        val startSeconds = (program.startTimeMillis / 1000L).coerceAtLeast(0)
        val endSeconds = (program.stopTimeMillis / 1000L).coerceAtLeast(0)
        val durationSeconds = ((program.stopTimeMillis - program.startTimeMillis) / 1000L)
            .coerceAtLeast(1)
        val offsetSeconds = ((program.startTimeMillis - currentTimeMillis) / 1000L)

        val filled = template
            .replace("{utc}", startSeconds.toString())
            .replace("{start}", startSeconds.toString())
            .replace("{duration}", durationSeconds.toString())
            .replace("{end}", endSeconds.toString())
            .replace("{stop}", endSeconds.toString())
            .replace("{offset}", offsetSeconds.toString())
            .replace("{timestamp}", startSeconds.toString())
            .replace("{lutc}", startSeconds.toString())

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
            // Primary params come first, secondary params override duplicates
            val allParams = listOf(primary, secondary)
                .map { it.trim('?', '&') }
                .filter { it.isNotEmpty() }
                .joinToString("&")
            return allParams
        }

        return when {
            filled.startsWith("http://", true) || filled.startsWith("https://", true) -> filled
            filled.startsWith("?") -> {
                val extra = filled.removePrefix("?")
                val mergedQuery = mergeQuery(extra, baseQuery)
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
                val mergedQuery = mergeQuery(extra, baseQuery)
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
                val mergedQuery = mergeQuery(extraQuery, baseQuery) // DVR params first

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

