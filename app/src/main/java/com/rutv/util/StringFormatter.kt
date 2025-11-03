package com.rutv.util

import android.content.Context
import com.rutv.R

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
    fun formatEpgLoadedCached(programs: Int, channels: Int): String {
        return "EPG: Loaded cached data ($programs programs for $channels channels)"
    }

    fun formatEpgNoCached(): String {
        return "EPG: No cached data found"
    }

    fun formatEpgFailedLoad(error: String): String {
        return "EPG: Failed to load cached data ($error)"
    }

    fun formatEpgPlaylistLoaded(channels: Int, catchupSupported: String): String {
        return "DVR: Playlist loaded ($channels channels, catch-up: $catchupSupported)"
    }

    fun formatEpgPlaylistEmpty(): String {
        return "EPG: Playlist empty"
    }

    fun formatEpgPlaylistFailed(error: String): String {
        return "EPG: Playlist load failed ($error)"
    }

    fun formatEpgFetchStarted(): String {
        return "EPG: Fetch started"
    }

    fun formatEpgFetchManual(): String {
        return "EPG: Manual fetch requested"
    }

    fun formatEpgFetchComplete(programs: Int, channelsFound: Int, channelsRequested: Int): String {
        return "EPG: Fetch complete ($programs programs, $channelsFound/$channelsRequested channels)"
    }

    fun formatEpgFetchFailed(error: String): String {
        return "EPG: Fetch failed ($error)"
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

    fun formatEpgCachedCoveringWindow(hoursAgo: Int, programs: Int): String {
        return "EPG: Using cached data (fetched ${hoursAgo}h ago, $programs programs)"
    }

    fun formatEpgWindowNotCovered(): String {
        return "EPG: Cached data does not cover desired window, fetching missing data"
    }

    fun formatEpgNoCachedFetching(): String {
        return "EPG: No cached data, fetching from service"
    }

    fun formatEpgCachedOldRefresh(hoursAgo: Int): String {
        return "EPG: Cached data is ${hoursAgo}h old, refreshing"
    }

    fun formatEpgNoChannels(): String {
        return "EPG: No channels loaded yet, skipping fetch"
    }

    fun formatEpgNoChannelsSupport(): String {
        return "EPG: No channels with EPG support, skipping fetch"
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

    fun formatErrorFailedLoadPlaylistWithMsg(error: String): String {
        return "Failed to load playlist: $error"
    }
}
