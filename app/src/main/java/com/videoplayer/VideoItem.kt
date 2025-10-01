package com.videoplayer

data class VideoItem(
    val title: String,
    val url: String,
    var isPlaying: Boolean = false,
    val logo: String = "",
    val group: String = ""
)
