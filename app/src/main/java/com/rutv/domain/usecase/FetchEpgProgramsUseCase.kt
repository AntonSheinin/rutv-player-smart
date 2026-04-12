package com.rutv.domain.usecase

import com.rutv.data.model.Channel
import com.rutv.data.model.EpgProgram
import com.rutv.domain.repository.EpgRepository
import com.rutv.data.repository.PreferencesRepository
import com.rutv.util.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import javax.inject.Inject

/**
 * Loads EPG programs for a given channel/tvgId using the shared window policy.
 *
 * This keeps all callers consistent and relies on [EpgRepository] caching for performance.
 */
class FetchEpgProgramsUseCase @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val epgRepository: EpgRepository,
    private val computeEpgWindowUseCase: ComputeEpgWindowUseCase
) {
    data class EpgProgramsWindow(
        val programs: List<EpgProgram>,
        val fromUtcMillis: Long,
        val toUtcMillis: Long
    )

    suspend operator fun invoke(
        tvgId: String,
        mode: ComputeEpgWindowUseCase.Mode,
        channel: Channel? = null,
        nowUtcMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Result<EpgProgramsWindow> {
        try {
            val epgUrl = preferencesRepository.epgUrl.first().trim()
            if (epgUrl.isBlank()) {
                return Result.Error(IllegalStateException("EPG URL not configured"))
            }
            if (tvgId.isBlank()) {
                return Result.Success(EpgProgramsWindow(emptyList(), 0L, 0L))
            }

            val window = computeEpgWindowUseCase(
                mode = mode,
                channel = channel,
                nowUtcMillis = nowUtcMillis,
                zoneId = zoneId
            )
            val programs = epgRepository.getWindowedProgramsForChannel(
                epgUrl = epgUrl,
                tvgId = tvgId,
                fromUtcMillis = window.fromUtcMillis,
                toUtcMillis = window.toUtcMillis
            )
            return Result.Success(
                EpgProgramsWindow(
                    programs = programs,
                    fromUtcMillis = window.fromUtcMillis,
                    toUtcMillis = window.toUtcMillis
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Error(e)
        }
    }
}


