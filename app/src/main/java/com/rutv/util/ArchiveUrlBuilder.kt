package com.rutv.util

import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram

/**
 * Builds archive/catch-up URLs for DVR playback.
 * Extracted from Channel data class for separation of concerns and testability.
 */
object ArchiveUrlBuilder {

    fun buildArchiveUrl(
        channel: Channel,
        program: EpgProgram,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): String? {
        if (!channel.supportsCatchup()) return null

        // Defensive validation: some playlists/EPG providers can return missing or malformed times.
        if (program.startTimeMillis <= 0 || program.stopTimeMillis <= 0) {
            return null // Invalid EPG data
        }
        if (program.stopTimeMillis <= program.startTimeMillis) {
            return null // Invalid program duration
        }

        val startUtcSeconds = (program.startUtcMillis / 1000L).coerceAtLeast(0)
        val stopUtcSeconds = (program.stopUtcMillis / 1000L).coerceAtLeast(startUtcSeconds)
        val durationSeconds = program.durationUtcSeconds
        val offsetSeconds = ((program.startUtcMillis - currentTimeMillis) / 1000L)

        // If custom `catchup-source` is provided, treat it as a server-specific template.
        // (Many IPTV providers use placeholders like {utc}/{duration}/{offset}.)
        if (channel.catchupSource.isNotBlank()) {
            return buildCustomArchiveUrl(
                channel.url, channel.catchupSource, startUtcSeconds, stopUtcSeconds,
                durationSeconds, offsetSeconds
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // Flussonic DVR: Official path-based format (not query params!)
        // Format: http://server/stream/archive-{from}-{duration}.m3u8
        // Docs: https://flussonic.com/doc/add-support-for-dvr-to-middleware/
        // ═══════════════════════════════════════════════════════════════

        val baseUri = java.net.URI(channel.url)
        val basePath = baseUri.rawPath ?: ""
        val baseQuery = baseUri.rawQuery ?: ""

        // Extract stream name and directory from base URL
        // e.g., "/TNTHD/index.m3u8" -> streamDir="/TNTHD", fileName="index.m3u8"
        val lastSlash = basePath.lastIndexOf('/')
        val streamDir = if (lastSlash > 0) basePath.substring(0, lastSlash) else ""

        // Build archive path: /STREAM/archive-{from}-{duration}.m3u8
        val archivePath = "$streamDir/archive-$startUtcSeconds-$durationSeconds.m3u8"

        // Build query parameters.
        //
        // We always add `event=true` for compatibility with providers that serve EVENT playlists
        // even for "catch-up". If your server requires different behavior for completed programs,
        // prefer using `catchup-source` to customize it precisely.
        val queryParams = mutableListOf<String>()

        // Preserve existing query params (like token, etc.)
        if (baseQuery.isNotBlank()) {
            queryParams.add(baseQuery)
        }

        // EVENT: Playlist grows as new content arrives (useful for timeshift on currently airing program).
        // VOD: Static playlist (fully archived content).
        queryParams.add("event=true")

        val finalQuery = queryParams.joinToString("&")

        // Construct final URL
        return java.net.URI(
            baseUri.scheme,
            baseUri.authority,
            archivePath,
            finalQuery.ifEmpty { null },
            null
        ).toString()
    }

    /**
     * Build archive URL using custom catchup-source template
     * (for non-Flussonic or custom DVR servers)
     */
    private fun buildCustomArchiveUrl(
        channelUrl: String,
        template: String,
        startSeconds: Long,
        endSeconds: Long,
        durationSeconds: Long,
        offsetSeconds: Long,
    ): String? {
        var filled = template

        // Fix common malformed templates
        if (filled.startsWith("{") && !filled.startsWith("http")) {
            filled = "?from=$filled"
        }

        // Replace all placeholder variables
        filled = filled
            .replace("{utc}", startSeconds.toString())
            .replace("{start}", startSeconds.toString())
            .replace("{duration}", durationSeconds.toString())
            .replace("{end}", endSeconds.toString())
            .replace("{stop}", endSeconds.toString())
            .replace("{offset}", offsetSeconds.toString())
            .replace("{timestamp}", startSeconds.toString())
            .replace("{lutc}", startSeconds.toString())

        val baseUri = java.net.URI(channelUrl)
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
