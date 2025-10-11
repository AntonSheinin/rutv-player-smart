package com.videoplayer

data class VideoItem(
    val title: String,
    val url: String,
    var isPlaying: Boolean = false,
    val logo: String = "",
    val group: String = "",
    var isFavorite: Boolean = false,
    var aspectRatio: Int = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val tvgId: String = "",
    val catchupDays: Int = 0
)
