package com.rutv.util

/**
 * EPG-related constants
 */
object EpgConstants {
    // EPG Network timeouts
    const val EPG_CONNECT_TIMEOUT_MS = 180_000
    const val EPG_READ_TIMEOUT_MS = 180_000
    const val EPG_HEALTH_TIMEOUT_MS = 5_000

    // EPG Fetch settings
    const val EPG_FETCH_BATCH_SIZE = 40

    // EPG Cache settings
    const val EPG_CACHE_COVERAGE_TOLERANCE_MS = 60_000L // 1 minute tolerance for window coverage
}
