package com.rutv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalConfigParserTest {
    @Test
    fun parse_ignoresCommentsUnknownBlankValuesAndStripsQuotes() {
        val parsed = ExternalConfigParser.parse(
            """
            # comment
            UNKNOWN=value
            PLAYLIST_URL = " https://example.test/playlist.m3u8 "
            EPG_URL=
            EPG_DAYS_AHEAD=' 7 '
            EPG_DAYS_PAST=14
            EPG_PAGE_DAYS=1
            """.trimIndent()
        )

        assertTrue(parsed.invalidMessages.isEmpty())
        assertEquals(
            mapOf(
                ExternalConfigKeys.PLAYLIST_URL to "https://example.test/playlist.m3u8",
                ExternalConfigKeys.EPG_DAYS_AHEAD to "7",
                ExternalConfigKeys.EPG_DAYS_PAST to "14",
                ExternalConfigKeys.EPG_PAGE_DAYS to "1"
            ),
            parsed.values
        )
    }

    @Test
    fun parse_rejectsInvalidNumericValuesAtomically() {
        val parsed = ExternalConfigParser.parse(
            """
            PLAYLIST_URL=https://example.test/playlist.m3u8
            EPG_PAGE_DAYS=0
            """.trimIndent()
        )

        assertTrue(parsed.invalidMessages.any { it.contains("EPG_PAGE_DAYS") })
    }

    @Test
    fun parse_rejectsDuplicateSupportedNonblankKeys() {
        val parsed = ExternalConfigParser.parse(
            """
            EPG_DAYS_PAST=7
            EPG_DAYS_PAST=14
            """.trimIndent()
        )

        assertTrue(parsed.invalidMessages.any { it.contains("duplicate EPG_DAYS_PAST") })
    }

    @Test
    fun hashNormalized_isStableForOrderOnlyChanges() {
        val first = ExternalConfigParser.parse(
            """
            PLAYLIST_URL=https://example.test/playlist.m3u8
            EPG_DAYS_AHEAD=7
            """.trimIndent()
        )
        val second = ExternalConfigParser.parse(
            """
            # order and comments do not affect hash
            EPG_DAYS_AHEAD=7
            UNKNOWN=ignored
            PLAYLIST_URL=https://example.test/playlist.m3u8
            """.trimIndent()
        )

        assertEquals(
            ExternalConfigParser.hashNormalized(first.values),
            ExternalConfigParser.hashNormalized(second.values)
        )
    }
}
