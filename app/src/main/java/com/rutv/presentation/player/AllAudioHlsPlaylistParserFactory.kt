package com.rutv.presentation.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import timber.log.Timber

/**
 * Converts Flussonic live and archive playlists into a proper HLS alternate-audio master.
 * Media3 otherwise exposes only the first embedded audio PID from these transport streams.
 */
@UnstableApi
internal class AllAudioHlsPlaylistParserFactory(
    private val dataSourceFactory: DataSource.Factory
) : HlsPlaylistParserFactory {

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        return ParsingLoadable.Parser { uri, inputStream ->
            val bytes = inputStream.readBytes()
            val rewritten = try {
                FlussonicAudioRequest.from(uri)?.let { request ->
                    val metadata = loadAudioStreamMetadata(request.mediaInfoUri)
                    val bandwidth = declaredBandwidth(bytes)
                        ?: metadata.estimatedBandwidthBitsPerSecond
                    val rewrittenPlaylist = buildAllAudioMultivariantPlaylist(
                        request,
                        metadata.renditions,
                        bandwidth
                    )
                    if (rewrittenPlaylist == null) {
                        Timber.w("No usable audio track metadata for %s", uri.path)
                    }
                    rewrittenPlaylist
                }
            } catch (error: Exception) {
                Timber.w(
                    "Audio track metadata unavailable for %s (%s)",
                    uri.path,
                    error.javaClass.simpleName
                )
                null
            }
            HlsPlaylistParser().parse(
                uri,
                ByteArrayInputStream(rewritten?.toByteArray(StandardCharsets.UTF_8) ?: bytes)
            )
        }
    }

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?
    ): ParsingLoadable.Parser<HlsPlaylist> {
        return HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
    }

    private fun loadAudioStreamMetadata(mediaInfoUri: Uri): AudioStreamMetadata {
        val stream = DataSourceInputStream(
            dataSourceFactory.createDataSource(),
            DataSpec(mediaInfoUri)
        )
        val json = stream.use { input ->
            input.open()
            input.bufferedReader(StandardCharsets.UTF_8).readText()
        }
        val tracks = JSONObject(json).optJSONArray("tracks")
            ?: return AudioStreamMetadata(emptyList(), null)
        val languages = linkedMapOf<String, AudioRendition>()
        var videoBitrateKbps = 0L
        var maxAudioBitrateKbps = 0L
        for (index in 0 until tracks.length()) {
            val track = tracks.optJSONObject(index) ?: continue
            val content = track.optString("content")
            val bitrateKbps = track.optLong("bitrate").coerceAtLeast(0L)
            if (content == "video") {
                videoBitrateKbps = maxOf(videoBitrateKbps, bitrateKbps)
                continue
            }
            if (content != "audio") continue
            val trackId = track.optString("track_id").trim()
            val language = track.optString("language").trim()
            if (!trackId.matches(Regex("a\\d+"))) continue
            if (!language.matches(Regex("[a-z]{2,8}(?:-[a-z0-9]{2,8})*", RegexOption.IGNORE_CASE))) continue
            languages.putIfAbsent(language.lowercase(Locale.ROOT), AudioRendition(trackId, language))
            maxAudioBitrateKbps = maxOf(maxAudioBitrateKbps, bitrateKbps)
        }
        val estimatedBandwidth = (videoBitrateKbps + maxAudioBitrateKbps)
            .takeIf { it > 0L }
            ?.times(1_000L)
        return AudioStreamMetadata(languages.values.toList(), estimatedBandwidth)
    }
}

internal data class AudioRendition(val trackId: String, val language: String)
internal data class AudioStreamMetadata(
    val renditions: List<AudioRendition>,
    val estimatedBandwidthBitsPerSecond: Long?
)

internal data class FlussonicAudioRequest(
    val mediaInfoUri: Uri,
    val variantUri: (String) -> Uri,
    val audioUri: (String) -> Uri
) {
    companion object {
        private val TRACKS_PATH = Regex("/tracks-[^/]+/")
        private val FLUSSONIC_ARCHIVE_PLAYLIST = Regex(
            "archive-\\d+-\\d+\\.m3u8",
            RegexOption.IGNORE_CASE
        )
        private const val FLUSSONIC_MASTER_PLAYLIST = "video.m3u8"
        private const val FLUSSONIC_MEDIA_PLAYLIST = "mono.ts.m3u8"

        fun from(uri: Uri): FlussonicAudioRequest? {
            val javaUri = runCatching { java.net.URI(uri.toString()) }.getOrNull() ?: return null
            val path = javaUri.rawPath ?: return null
            val hasExplicitTracksPath = TRACKS_PATH.containsMatchIn(path)
            val requestedPlaylistName = path.substringAfterLast('/')
            val isFlussonicMaster = requestedPlaylistName.equals(
                FLUSSONIC_MASTER_PLAYLIST,
                ignoreCase = true
            )
            val isFlussonicArchive = FLUSSONIC_ARCHIVE_PLAYLIST.matches(requestedPlaylistName)
            if (!hasExplicitTracksPath && !isFlussonicMaster && !isFlussonicArchive) return null

            val streamRoot = if (hasExplicitTracksPath) {
                path.substringBefore("/tracks-")
            } else {
                path.substringBeforeLast('/', missingDelimiterValue = "")
            }
            if (streamRoot.isEmpty()) return null
            val playlistName = if (isFlussonicMaster) {
                FLUSSONIC_MEDIA_PLAYLIST
            } else {
                requestedPlaylistName
            }
            if (!playlistName.endsWith(".m3u8", ignoreCase = true)) return null

            fun build(pathValue: String): Uri = Uri.parse(
                java.net.URI(
                    javaUri.scheme,
                    javaUri.authority,
                    pathValue,
                    javaUri.rawQuery,
                    null
                ).toString()
            )

            return FlussonicAudioRequest(
                mediaInfoUri = build("$streamRoot/media_info.json"),
                variantUri = { trackId -> build("$streamRoot/tracks-v1$trackId/$playlistName") },
                audioUri = { trackId -> build("$streamRoot/tracks-$trackId/$playlistName") }
            )
        }
    }
}

internal fun buildAllAudioMultivariantPlaylist(
    request: FlussonicAudioRequest,
    renditions: List<AudioRendition>,
    bandwidthBitsPerSecond: Long?
): String? {
    if (renditions.isEmpty()) return null
    val bandwidth = bandwidthBitsPerSecond?.takeIf { it > 0L } ?: return null
    val normalized = renditions.distinctBy { it.language.lowercase(Locale.ROOT) }
    if (normalized.isEmpty()) return null

    return buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        normalized.forEachIndexed { index, rendition ->
            append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"")
            append(rendition.language.uppercase(Locale.ROOT))
            append("\",LANGUAGE=\"")
            append(rendition.language)
            append("\",AUTOSELECT=YES")
            if (index == 0) {
                appendLine(",DEFAULT=YES")
            } else {
                append(",DEFAULT=NO,URI=\"")
                append(request.audioUri(rendition.trackId))
                appendLine("\"")
            }
        }
        appendLine("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,AUDIO=\"audio\"")
        appendLine(request.variantUri(normalized.first().trackId).toString())
    }
}

internal fun declaredBandwidth(playlistBytes: ByteArray): Long? {
    val bandwidthAttribute = Regex("(?:^|,)\\s*BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
    return playlistBytes
        .toString(StandardCharsets.UTF_8)
        .lineSequence()
        .filter { it.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) }
        .mapNotNull { line ->
            bandwidthAttribute
                .find(line.substringAfter(':'))
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
        }
        .maxOrNull()
}
