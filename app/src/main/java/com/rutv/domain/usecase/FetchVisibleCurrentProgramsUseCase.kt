package com.rutv.domain.usecase

import com.rutv.data.model.EpgProgram
import com.rutv.data.repository.PreferencesRepository
import com.rutv.domain.repository.EpgRepository
import com.rutv.util.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import javax.inject.Inject

/**
 * Fetches today's EPG window in a single batched request for the given tvgIds and returns
 * the "now playing" program for each. Used to populate the channel list's per-row subtitle
 * for channels that aren't currently playing.
 */
class FetchVisibleCurrentProgramsUseCase @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val epgRepository: EpgRepository,
    private val computeEpgWindowUseCase: ComputeEpgWindowUseCase
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
            val window = computeEpgWindowUseCase(
                mode = ComputeEpgWindowUseCase.Mode.Today,
                nowUtcMillis = nowUtcMillis,
                zoneId = zoneId
            )
            val programsByTvgId = epgRepository.getWindowedProgramsForChannels(
                epgUrl = epgUrl,
                tvgIds = tvgIds,
                fromUtcMillis = window.fromUtcMillis,
                toUtcMillis = window.toUtcMillis
            )
            val current = programsByTvgId.mapValues { (_, programs) ->
                programs.firstOrNull { it.isCurrent(nowUtcMillis) }
            }
            Result.Success(current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
