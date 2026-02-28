package com.rutv.util

/**
 * Utility for formatting strings with placeholders
 * Used for formatting debug messages and error messages that need string resources
 */
object StringFormatter {
    /**
     * Format debug message strings with parameters
     * These are used in ViewModels where Context is not directly available
     * The formatted strings will be displayed in UI where string resources can be used
     */
    fun formatEpgPlaylistLoaded(channels: Int, catchupSupported: String): String {
        return "DVR: Playlist loaded ($channels channels, catch-up: $catchupSupported)"
    }

    fun formatEpgPlaylistEmpty(): String {
        return "EPG: Playlist empty"
    }

    fun formatEpgPlaylistFailed(error: String): String {
        return "EPG: Playlist load failed ($error)"
    }

    fun formatEpgUrlNotConfigured(): String {
        return "EPG: URL not configured"
    }

    fun formatEpgShowingPrograms(count: Int, tvgId: String, currentTitle: String? = null): String {
        return if (currentTitle != null) {
            "EPG: Showing $count programs for $tvgId (current: $currentTitle)"
        } else {
            "EPG: Showing $count programs for $tvgId"
        }
    }

    fun formatEpgLoadFailed(tvgId: String, error: String): String {
        return "EPG: Failed to load for $tvgId - $error"
    }

    fun formatEpgTimezoneChanged(): String {
        return "EPG: System timezone changed, refreshing data"
    }

    fun formatEpgClockChanged(): String {
        return "EPG: System clock changed, refreshing current programs"
    }

    fun formatDvrRestarting(title: String): String {
        return "DVR: Restarting $title from beginning"
    }

    fun formatDvrNoCurrentProgram(): String {
        return "DVR: No current program to restart"
    }

    fun formatDvrRequest(
        channelTitle: String,
        programTitle: String,
        startTime: String,
        duration: Int,
        age: Int,
        template: String
    ): String {
        return "DVR: Request $channelTitle • $programTitle (start=$startTime, duration=${duration}m, age=${age}m, template=$template)"
    }

    fun formatDvrChannelNotFound(programTitle: String): String {
        return "DVR: Channel not found for program $programTitle"
    }

    fun formatDvrValidationFailed(message: String): String {
        return "DVR: $message"
    }

    fun formatErrorUnknown(): String {
        return "unknown error"
    }

    fun formatErrorFailedLoadPlaylist(): String {
        return "Failed to load playlist"
    }

    fun formatErrorInitFailed(error: String): String {
        return "Initialization failed: $error"
    }

}
