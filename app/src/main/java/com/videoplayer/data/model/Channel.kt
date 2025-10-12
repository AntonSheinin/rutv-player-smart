package com.videoplayer.data.model

import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Domain model for a channel.
 * This is the model used throughout the app.
 */
data class Channel(
    val url: String,
    val title: String,
    val logo: String = "",
    val group: String = "General",
    val tvgId: String = "",
    val catchupDays: Int = 0,
    val isFavorite: Boolean = false,
    val aspectRatio: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val position: Int = 0
) {
    val hasEpg: Boolean
        get() = tvgId.isNotBlank() && catchupDays > 0

    val isValid: Boolean
        get() = url.isNotBlank() && title.isNotBlank()

    companion object {
        fun empty() = Channel(
            url = "",
            title = "",
            position = 0
        )
    }
}
