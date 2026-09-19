package com.rutv.util

import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveUrlBuilderTest {

    @Test
    fun defaultFlussonicArchiveUrlUsesStandardPlaylist() {
        val channel = flussonicChannel("http://example.com/stream/video.m3u8?token=secret")
        val program = EpgProgram(
            id = "program",
            startTime = "1970-01-01T00:16:40Z",
            stopTime = "1970-01-01T00:26:40Z",
            title = "Program",
            startTimeMillis = 1_000_000L,
            stopTimeMillis = 1_600_000L
        )

        assertEquals(
            "http://example.com/stream/archive-1000-600.m3u8?token=secret",
            ArchiveUrlBuilder.buildArchiveUrl(channel, program, currentTimeMillis = 2_000_000L)
        )
    }

    private fun flussonicChannel(url: String) = Channel(
        url = url,
        title = "Channel",
        tvgId = "channel",
        catchupDays = 7
    )
}
