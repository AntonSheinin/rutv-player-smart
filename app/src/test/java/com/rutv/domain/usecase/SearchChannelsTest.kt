package com.rutv.domain.usecase

import com.rutv.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchChannelsTest {
    private val friday = Channel(
        url = "https://example.test/pyatnitsa/index.m3u8",
        title = "Пятница",
        tvgId = "piatnica"
    )
    private val news = Channel(
        url = "https://example.test/live/world-news.m3u8",
        title = "World News",
        tvgId = "world.news"
    )

    @Test fun matchesTitleStreamNameAndTvgIdBySubstringIgnoringCase() {
        assertEquals(listOf(friday), searchChannels(listOf(friday, news), "ПЯТ"))
        assertEquals(listOf(friday), searchChannels(listOf(friday, news), "YATNI"))
        assertEquals(listOf(friday), searchChannels(listOf(friday, news), "IATNI"))
    }

    @Test fun preservesSourceOrderAndDoesNotDuplicateMultiFieldMatches() {
        val firstMatch = friday.copy(
            url = "https://example.test/piatnica/index.m3u8",
            title = "Piatnica",
            tvgId = "piatnica"
        )
        val secondMatch = news.copy(tvgId = "piatnica-news")

        assertEquals(
            listOf(secondMatch, firstMatch),
            searchChannels(listOf(secondMatch, firstMatch), "piatnica")
        )
        assertEquals(1, searchChannels(listOf(firstMatch), "piatnica").size)
    }

    @Test fun blankQueryReturnsOriginalList() {
        val channels = listOf(friday, news)
        assertTrue(searchChannels(channels, "  ") === channels)
    }

    @Test fun extractsCommonStreamUrlShapesWithoutSearchingHostOrQuery() {
        assertEquals(listOf("pyatnitsa", "index"), streamNamesFromUrl("https://host/pyatnitsa/index.m3u8"))
        assertEquals(listOf("live", "pyatnitsa"), streamNamesFromUrl("https://host/live/pyatnitsa.m3u8"))
        assertEquals(listOf("pyatnitsa"), streamNamesFromUrl("https://host/pyatnitsa"))
        assertEquals(listOf("pyatnitsa", "index"), streamNamesFromUrl("https://host/%70yatnitsa/index.m3u8?token=secret"))
        assertEquals(listOf("pyatnitsa"), streamNamesFromUrl("https://host/pyatnitsa.m3u8|User-Agent=RuTV"))
        assertEquals(listOf("PyatnitsaHD", "video"), streamNamesFromUrl("https://host/PyatnitsaHD/video.m3u8"))
        assertEquals(emptyList<String>(), streamNamesFromUrl("not a valid URL"))

        val unrelated = Channel("https://pyatnitsa.example/live/index.m3u8?token=piatnica", "Other")
        assertTrue(searchChannels(listOf(unrelated), "pyatnitsa").isEmpty())
        assertTrue(searchChannels(listOf(unrelated), "piatnica").isEmpty())
    }
}
