package com.videoplayer

data class VideoItem(
    val title: String,
    val url: String,
    var isPlaying: Boolean = false
)
