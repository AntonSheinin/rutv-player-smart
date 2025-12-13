package com.rutv.domain.usecase

import com.rutv.data.model.Channel
import com.rutv.data.repository.PreferencesRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * Computes EPG time windows in a single, consistent place.
 *
 * This avoids subtle differences between screens and keeps window policy easy to change.
 */
class ComputeEpgWindowUseCase @Inject constructor(
    private val preferencesRepository: PreferencesRepository
) {
    enum class Mode {
        /** Only today's programs in the current device timezone. */
        Today,
        /**
         * Window used for "preload/current program" caching.
         * Includes past days (max of global preference and channel catch-up days) and future days (global preference).
         */
        PreferredForChannel
    }

    data class EpgWindow(
        val fromUtcMillis: Long,
        val toUtcMillis: Long
    )

    suspend operator fun invoke(
        mode: Mode,
        channel: Channel? = null,
        nowUtcMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): EpgWindow {
        val nowZoned = Instant.ofEpochMilli(nowUtcMillis).atZone(zoneId)
        return when (mode) {
            Mode.Today -> {
                val start = nowZoned.toLocalDate()
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
                val end = nowZoned.toLocalDate()
                    .atTime(LocalTime.of(23, 59, 59))
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
                EpgWindow(start, end)
            }

            Mode.PreferredForChannel -> {
                val ch = requireNotNull(channel) { "Channel is required for PreferredForChannel window" }
                val preferredPastDays = preferencesRepository.epgDaysPast.first().coerceAtLeast(0)
                val channelPastDays = ch.catchupDays.coerceAtLeast(0)
                val windowPastDays = maxOf(preferredPastDays, channelPastDays).toLong()
                val preferredAheadDays = preferencesRepository.epgDaysAhead.first().coerceAtLeast(0).toLong()

                val start = nowZoned.toLocalDate()
                    .minusDays(windowPastDays)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
                val end = nowZoned.toLocalDate()
                    .plusDays(preferredAheadDays)
                    .atTime(LocalTime.of(23, 59, 59))
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()

                EpgWindow(start, end)
            }
        }
    }
}


