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
        val template = if (catchupSource.isBlank()) "?utc={utc}" else catchupSource

        val startSeconds = (program.startTimeMillis / 1000L).coerceAtLeast(0)
        val durationSeconds = ((program.stopTimeMillis - program.startTimeMillis) / 1000L)
            .coerceAtLeast(60)

        val filled = template
            .replace("{utc}", startSeconds.toString())
            .replace("{start}", startSeconds.toString())
            .replace("{duration}", durationSeconds.toString())

        return when {
            filled.startsWith("http://", true) || filled.startsWith("https://", true) -> filled
            filled.startsWith("?") || filled.startsWith("&") -> {
                val separator = if (url.contains("?")) {
                    if (filled.startsWith("&")) "" else ""
                } else {
                    if (filled.startsWith("&")) "?" else ""
                }
                url + separator + filled.removePrefix(if (filled.startsWith("&") && !url.contains("?")) "&" else "")
            }
            filled.startsWith("/") -> {
                val base = url.substringBeforeLast("/", url)
                base + filled
            }
            else -> {
                // Assume relative
                val base = url.substringBeforeLast("/", url)
                "$base/$filled"
            }
        }
    }
}

