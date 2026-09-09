package com.rutv.data.remote

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
@Singleton
class PlaylistParser @Inject constructor() {

    /**
     * Parse M3U/M3U8 content into Channel list
     */
    fun parse(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lineSequence()

        var currentTitle = ""
        var currentLogo = ""
        var currentGroup = ""
        var currentGroups: List<String> = emptyList()
        var currentTvgId = ""
        var currentCatchupDays = 0
        var currentCatchupSource = ""

        for (rawLine in lines) {
            val line = rawLine.trim()

            if (line.startsWith("#EXTINF:")) {
                // Parse the metadata line. We keep the regexes simple because IPTV playlists
                // vary a lot in attribute order and whitespace.
                val tvgNameMatch = tvgNamePattern.find(line)
                val tvgIdMatch = tvgIdPattern.find(line)
                val logoMatch = logoPattern.find(line)
                val groupMatch = groupPattern.find(line)
                val catchupDaysMatch = catchupDaysPattern.find(line)
                val catchupSourceMatch = catchupSourcePattern.find(line)
                val titleMatch = titlePattern.find(line)

                currentTitle = tvgNameMatch?.groupValues?.get(1)
                    ?: titleMatch?.groupValues?.get(1)
                    ?: "Unknown"
                currentLogo = logoMatch?.groupValues?.get(1) ?: ""
                val rawGroup = groupMatch?.groupValues?.get(1).orEmpty()
                currentGroups = parseGroups(rawGroup)
                currentGroup = currentGroups.firstOrNull() ?: (rawGroup.ifBlank { "General" })
                currentTvgId = tvgIdMatch?.groupValues?.get(1) ?: ""
                currentCatchupDays = catchupDaysMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                currentCatchupSource = catchupSourceMatch?.groupValues?.get(1) ?: ""

            } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                // Alternative IPTV convention: #EXTGRP:Group Name
                // If it appears after #EXTINF, treat as additional grouping info for the pending item.
                val raw = line.substringAfter(':', "").trim()
                if (raw.isNotBlank()) {
                    val extra = parseGroups(raw)
                    currentGroups = mergeGroups(currentGroups, extra)
                    if (currentGroup.isBlank() || currentGroup == "General") {
                        currentGroup = currentGroups.firstOrNull() ?: currentGroup
                    }
                }
            } else if (line.isNotEmpty() && !line.startsWith("#") && currentTitle.isNotEmpty()) {
                // URL line following the last EXTINF: build a Channel domain object.
                channels.add(
                    Channel(
                        url = line,
                        title = currentTitle,
                        logo = currentLogo,
                        group = currentGroup,
                        groups = currentGroups,
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
                currentGroups = emptyList()
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
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun parseGroups(raw: String): List<String> {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return emptyList()
        // Common separators: "News;Sports", "News|Sports", "News, Sports", "News / Sports"
        val parts = cleaned.split(';', '|', ',', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        // Keep order, de-dupe
        return buildList {
            parts.forEach { g -> if (!contains(g)) add(g) }
        }
    }

    private fun mergeGroups(primary: List<String>, extra: List<String>): List<String> {
        if (primary.isEmpty()) return extra
        if (extra.isEmpty()) return primary
        return buildList {
            primary.forEach { if (!contains(it)) add(it) }
            extra.forEach { if (!contains(it)) add(it) }
        }
    }
    private companion object {
        private val tvgNamePattern = Regex("""tvg-name="([^"]+)"""")
        private val tvgIdPattern = Regex("""tvg-id="([^"]+)"""")
        private val logoPattern = Regex("""tvg-logo="([^"]+)"""")
        private val groupPattern = Regex("""group-title="([^"]+)"""")
        private val catchupDaysPattern = Regex("""catchup-days="([^"]+)"""")
        private val catchupSourcePattern = Regex("""catchup-source="([^"]+)"""")
        private val titlePattern = Regex(""",\s*(.+)$""")
    }
}
