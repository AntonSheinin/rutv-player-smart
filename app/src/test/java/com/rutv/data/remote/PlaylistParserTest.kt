package com.rutv.data.remote

import org.junit.Assert.*
import org.junit.Test

class PlaylistParserTest {
    @Test fun parsesGroupsCatchupUnicodeAndMixedLineEndings() {
        val channels = PlaylistParser().parse("#EXTM3U\r\n#EXTINF:-1 tvg-id=\"one\" group-title=\"News;Sports\" catchup-days=\"7\",\u041d\u043e\u0432\u043e\u0441\u0442\u0438\nhttp://example.com/live\r\n")
        assertEquals("\u041d\u043e\u0432\u043e\u0441\u0442\u0438", channels.single().title)
        assertEquals(listOf("News", "Sports"), channels.single().groups)
        assertEquals(7, channels.single().catchupDays)
    }
    @Test fun cacheHashDoesNotReuseJavaStringHashCollisions() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(PlaylistParser().calculateHash("Aa"), PlaylistParser().calculateHash("BB"))
    }
}
