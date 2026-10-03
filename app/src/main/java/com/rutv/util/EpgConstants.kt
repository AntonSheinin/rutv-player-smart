package com.rutv.util

/**
 * EPG-related constants
 */
object EpgConstants {
    const val DEFAULT_DESCRIPTION_LANGUAGE = "ru"
    val SUPPORTED_DESCRIPTION_LANGUAGES = setOf("en", "ru", "he")

    // EPG Network timeouts
    const val EPG_CONNECT_TIMEOUT_MS = 180_000
    const val EPG_READ_TIMEOUT_MS = 180_000
}
