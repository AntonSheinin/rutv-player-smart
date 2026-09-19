package com.rutv.presentation.player

import android.app.Application
import android.net.Uri
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AllAudioHlsPlaylistParserFactoryTest {

    @Test
    fun buildsAlternateAudioMasterAndPreservesToken() {
        val request = requireNotNull(
            FlussonicAudioRequest.from(
                Uri.parse("http://example.com/stream/tracks-v1a1a2/mono.ts.m3u8?token=secret")
            )
        )

        val playlist = requireNotNull(
            buildAllAudioMultivariantPlaylist(
                request,
                listOf(AudioRendition("a1", "eng"), AudioRendition("a2", "rus")),
                bandwidthBitsPerSecond = 18_720_000L
            )
        )

        assertTrue(playlist.contains("LANGUAGE=\"eng\""))
        assertTrue(playlist.contains("LANGUAGE=\"rus\""))
        assertTrue(playlist.contains("NAME=\"ENG\""))
        assertTrue(playlist.contains("NAME=\"RUS\""))
        assertTrue(playlist.contains("BANDWIDTH=18720000"))
        assertTrue(playlist.contains("tracks-a2/mono.ts.m3u8?token=secret"))
        assertTrue(playlist.contains("tracks-v1a1/mono.ts.m3u8?token=secret"))
    }

    @Test
    fun labelsSingleAudioTrack() {
        val request = requireNotNull(
            FlussonicAudioRequest.from(
                Uri.parse("http://example.com/stream/tracks-v1a1/mono.ts.m3u8")
            )
        )

        val playlist = requireNotNull(
            buildAllAudioMultivariantPlaylist(
                request,
                listOf(AudioRendition("a1", "rus")),
                bandwidthBitsPerSecond = 4_000_000L
            )
        )
        assertTrue(playlist.contains("NAME=\"RUS\""))
        assertTrue(playlist.contains("tracks-v1a1/mono.ts.m3u8"))
    }

    @Test
    fun ignoresOrdinaryHlsUrls() {
        assertEquals(null, FlussonicAudioRequest.from(Uri.parse("http://example.com/live/stream.m3u8")))
    }

    @Test
    fun recognizesUnlabeledFlussonicMasterPlaylist() {
        val request = requireNotNull(
            FlussonicAudioRequest.from(
                Uri.parse("http://example.com/LoveNature4K/video.m3u8?token=secret")
            )
        )

        assertEquals(
            "http://example.com/LoveNature4K/tracks-v1a1/mono.ts.m3u8?token=secret",
            request.variantUri("a1").toString()
        )
        assertEquals(
            "http://example.com/LoveNature4K/tracks-a5/mono.ts.m3u8?token=secret",
            request.audioUri("a5").toString()
        )
    }

    @Test
    fun recognizesStandardFlussonicArchivePlaylist() {
        val request = requireNotNull(
            FlussonicAudioRequest.from(
                Uri.parse("http://example.com/stream/archive-1000-600.m3u8?token=secret")
            )
        )

        assertEquals(
            "http://example.com/stream/tracks-v1a3/archive-1000-600.m3u8?token=secret",
            request.variantUri("a3").toString()
        )
        assertEquals(
            "http://example.com/stream/tracks-a3/archive-1000-600.m3u8?token=secret",
            request.audioUri("a3").toString()
        )
    }

    @Test
    fun readsPeakBandwidthFromSourceMaster() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=15010000,BANDWIDTH=18720000
            tracks-v1a1/mono.ts.m3u8
        """.trimIndent().toByteArray()

        assertEquals(18_720_000L, declaredBandwidth(playlist))
    }

    @Test
    fun media3DiscoversLabelsFromGeneratedArchiveMaster() {
        val archiveUri = Uri.parse(
            "http://example.com/stream/archive-1000-600.m3u8?token=secret"
        )
        val request = requireNotNull(FlussonicAudioRequest.from(archiveUri))
        val playlistText = requireNotNull(
            buildAllAudioMultivariantPlaylist(
                request,
                listOf(AudioRendition("a1", "eng"), AudioRendition("a2", "rus")),
                bandwidthBitsPerSecond = 8_000_000L
            )
        )

        val parsed = HlsPlaylistParser().parse(
            archiveUri,
            ByteArrayInputStream(playlistText.toByteArray())
        ) as HlsMultivariantPlaylist
        val labels = listOfNotNull(parsed.muxedAudioFormat?.label) +
            parsed.audios.mapNotNull { it.format.label }

        assertEquals(listOf("ENG", "RUS"), labels)
    }
}
