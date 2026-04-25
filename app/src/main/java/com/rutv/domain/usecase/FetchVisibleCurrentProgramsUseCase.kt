package com.rutv.domain.usecase

import com.rutv.data.model.EpgProgram
import com.rutv.data.repository.PreferencesRepository
import com.rutv.domain.repository.EpgRepository
import com.rutv.util.Result
import com.rutv.util.logDebug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * Fetches an EPG window covering "now" in a single batched request for the given tvgIds and
 * returns the "now playing" program for each. Used to populate the channel list's per-row
 * subtitle for channels that aren't currently playing.
 *
 * The window starts at local midnight of the previous day so programs that started before
 * today (e.g. late-night shows spanning midnight) are included — the backend filters by
 * start_time, and `Today`-only would miss those and report null for the current program.
 */
class FetchVisibleCurrentProgramsUseCase @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val epgRepository: EpgRepository
) {
    suspend operator fun invoke(
        tvgIds: List<String>,
        nowUtcMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Result<Map<String, EpgProgram?>> {
        if (tvgIds.isEmpty()) return Result.Success(emptyMap())
        return try {
            val epgUrl = preferencesRepository.epgUrl.first().trim()
            if (epgUrl.isBlank()) {
                return Result.Error(IllegalStateException("EPG URL not configured"))
            }
            val today = Instant.ofEpochMilli(nowUtcMillis).atZone(zoneId).toLocalDate()
            val fromUtcMillis = today.minusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
            val toUtcMillis = today
                .atTime(LocalTime.of(23, 59, 59))
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
            val programsByTvgId = epgRepository.getWindowedProgramsForChannels(
                epgUrl = epgUrl,
                tvgIds = tvgIds,
                fromUtcMillis = fromUtcMillis,
                toUtcMillis = toUtcMillis
            )
            val current = programsByTvgId.mapValues { (_, programs) ->
                programs.firstOrNull { it.isCurrent(nowUtcMillis) }
            }
            logDebug {
                val withPrograms = programsByTvgId.count { it.value.isNotEmpty() }
                val withCurrent = current.count { it.value != null }
                "FetchVisibleCurrentPrograms: requested=${tvgIds.size}, " +
                    "withPrograms=$withPrograms, withCurrent=$withCurrent"
            }
            Result.Success(current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
