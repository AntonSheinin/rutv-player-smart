package com.videoplayer.data.remote

import com.videoplayer.data.model.Channel
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parser for M3U/M3U8 playlists
 * Refactored from M3U8Parser object to injectable class
 */
@Singleton
class PlaylistParser @Inject constructor() {

    /**
     * Parse M3U/M3U8 content into Channel list
     */
    fun parse(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lines()

        var currentTitle = ""
        var currentLogo = ""
        var currentGroup = ""
        var currentTvgId = ""
        var currentCatchupDays = 0

        for (i in lines.indices) {
            val line = lines[i].trim()

            if (line.startsWith("#EXTINF:")) {
                // Parse EXTINF line
                val tvgNameMatch = Regex("""tvg-name="([^"]+)"""").find(line)
                val tvgIdMatch = Regex("""tvg-id="([^"]+)"""").find(line)
                val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(line)
                val groupMatch = Regex("""group-title="([^"]+)"""").find(line)
                val catchupDaysMatch = Regex("""catchup-days="([^"]+)"""").find(line)
                val titleMatch = Regex(""",\s*(.+)$""").find(line)

                currentTitle = tvgNameMatch?.groupValues?.get(1)
                    ?: titleMatch?.groupValues?.get(1)
                    ?: "Unknown"
                currentLogo = logoMatch?.groupValues?.get(1) ?: ""
                currentGroup = groupMatch?.groupValues?.get(1) ?: "General"
                currentTvgId = tvgIdMatch?.groupValues?.get(1) ?: ""
                currentCatchupDays = catchupDaysMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            } else if (line.isNotEmpty() && !line.startsWith("#") && currentTitle.isNotEmpty()) {
                // This is the URL line
                channels.add(
                    Channel(
                        url = line,
                        title = currentTitle,
                        logo = currentLogo,
                        group = currentGroup,
                        tvgId = currentTvgId,
                        catchupDays = currentCatchupDays,
                        position = channels.size
                    )
                )

                // Reset for next channel
                currentTitle = ""
                currentLogo = ""
                currentGroup = ""
                currentTvgId = ""
                currentCatchupDays = 0
            }
        }

        Timber.d("Parsed ${channels.size} channels from playlist")
        return channels
    }

    /**
     * Calculate hash of playlist content for cache invalidation
     */
    fun calculateHash(content: String): String {
        return content.hashCode().toString()
    }
}
