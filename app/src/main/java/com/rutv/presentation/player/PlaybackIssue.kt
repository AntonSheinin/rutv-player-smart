package com.rutv.presentation.player

/**
 * Structured classification for playback failures.
 * This is primarily used to surface Flussonic/provider states to the user in a consistent way.
 */
sealed class PlaybackIssue(open val message: String?) {
    data class Suspended(override val message: String? = null) : PlaybackIssue(message)
    data class TokenNotFound(override val message: String? = null) : PlaybackIssue(message)
    data class NotFound(override val message: String? = null) : PlaybackIssue(message)
    data class Forbidden(override val message: String? = null) : PlaybackIssue(message)
    data class HttpError(val code: Int, override val message: String? = null) : PlaybackIssue(message)
    data class Network(override val message: String? = null) : PlaybackIssue(message)
    data class Timeout(override val message: String? = null) : PlaybackIssue(message)
    data class Unknown(override val message: String? = null) : PlaybackIssue(message)

    val userMessage: String
        get() = when (this) {
            is Suspended -> "Stream is suspended by provider"
            is TokenNotFound -> "Token not found (access denied)"
            is NotFound -> "Stream not found"
            is Forbidden -> "Access denied"
            is HttpError -> "Stream error (HTTP $code)"
            is Network -> "Network error while loading stream"
            is Timeout -> "Timeout while loading stream"
            is Unknown -> message ?: "Playback error"
        }.let { base ->
            val extra = message?.trim().takeIf { !it.isNullOrBlank() }
            if (extra == null) base else "$base: $extra"
        }
}


