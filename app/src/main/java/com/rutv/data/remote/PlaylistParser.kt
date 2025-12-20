package com.rutv.data.remote

import androidx.media3.common.util.UnstableApi
import com.rutv.data.model.Channel
import com.rutv.util.logDebug
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parser for M3U/M3U8 playlists.
 *
 * Supported subset (common IPTV conventions):
 * - Each channel entry is described by an `#EXTINF:` line followed by a URL line.
 * - Recognized `#EXTINF` attributes:
 *   - `tvg-name`, `tvg-id`, `tvg-logo`, `group-title`
 *   - `catchup-days`, `catchup-source` (used for DVR/catch-up playback)
 *
 * Notes / limitations:
 * - This is intentionally tolerant: unknown tags are ignored, missing fields fall back to defaults.
 * - URLs are taken as-is (the player later handles redirects, headers, etc.).
 * - We assume one URL per `#EXTINF:`; if the playlist is malformed the entry may be skipped.
 */
@UnstableApi
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
        var currentCatchupSource = ""

        for (i in lines.indices) {
            val line = lines[i].trim()

            if (line.startsWith("#EXTINF:")) {
                // Parse the metadata line. We keep the regexes simple because IPTV playlists
                // vary a lot in attribute order and whitespace.
                val tvgNameMatch = Regex("""tvg-name="([^"]+)"""").find(line)
                val tvgIdMatch = Regex("""tvg-id="([^"]+)"""").find(line)
                val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(line)
                val groupMatch = Regex("""group-title="([^"]+)"""").find(line)
                val catchupDaysMatch = Regex("""catchup-days="([^"]+)"""").find(line)
                val catchupSourceMatch = Regex("""catchup-source="([^"]+)"""").find(line)
                val titleMatch = Regex(""",\s*(.+)$""").find(line)

                currentTitle = tvgNameMatch?.groupValues?.get(1)
                    ?: titleMatch?.groupValues?.get(1)
                    ?: "Unknown"
                currentLogo = logoMatch?.groupValues?.get(1) ?: ""
                currentGroup = groupMatch?.groupValues?.get(1) ?: "General"
                currentTvgId = tvgIdMatch?.groupValues?.get(1) ?: ""
                currentCatchupDays = catchupDaysMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                currentCatchupSource = catchupSourceMatch?.groupValues?.get(1) ?: ""

            } else if (line.isNotEmpty() && !line.startsWith("#") && currentTitle.isNotEmpty()) {
                // URL line following the last EXTINF: build a Channel domain object.
                channels.add(
                    Channel(
                        url = line,
                        title = currentTitle,
                        logo = currentLogo,
                        group = currentGroup,
                        tvgId = currentTvgId,
                        catchupDays = currentCatchupDays,
                        catchupSource = currentCatchupSource,
                        position = channels.size
                    )
                )

                // Reset for next channel. (We only reset once we successfully consumed a URL line.)
                currentTitle = ""
                currentLogo = ""
                currentGroup = ""
                currentTvgId = ""
                currentCatchupDays = 0
                currentCatchupSource = ""
            }
        }

        logDebug { "Parsed ${channels.size} channels from playlist" }
        return channels
    }

    /**
     * Calculate hash of playlist content for cache invalidation
     */
    fun calculateHash(content: String): String {
        return content.hashCode().toString()
    }
}
