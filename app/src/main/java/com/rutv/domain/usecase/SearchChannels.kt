package com.rutv.domain.usecase

import com.rutv.data.model.Channel
import java.net.URI

/** Returns channels whose title, stream name, or EPG tvg-id contains [query]. */
internal fun searchChannels(channels: List<Channel>, query: String): List<Channel> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return channels

    return channels.filter { channel ->
        channel.title.contains(normalizedQuery, ignoreCase = true) ||
            channel.tvgId.contains(normalizedQuery, ignoreCase = true) ||
            streamNamesFromUrl(channel.url).any { streamName ->
                streamName.contains(normalizedQuery, ignoreCase = true)
            }
    }
}

/**
 * Extracts the stream identifier from common IPTV URL shapes.
 *
 * Examples:
 * - /pyatnitsa/index.m3u8 -> pyatnitsa
 * - /live/pyatnitsa.m3u8 -> pyatnitsa
 */
internal fun streamNamesFromUrl(url: String): List<String> {
    val urlWithoutPlaybackOptions = url.substringBefore('|').trim()
    val segments = runCatching {
        URI(urlWithoutPlaybackOptions).path
            ?.split('/')
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }.getOrDefault(emptyList())
    val lastSegment = segments.lastOrNull() ?: return emptyList()
    val fileStem = lastSegment.substringBeforeLast('.', missingDelimiterValue = lastSegment)
    val parentSegment = segments.getOrNull(segments.lastIndex - 1)

    return buildList(2) {
        parentSegment?.takeIf { it.isNotBlank() }?.let(::add)
        fileStem.takeIf { it.isNotBlank() && it != parentSegment }?.let(::add)
    }
}
