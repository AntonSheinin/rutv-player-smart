package com.videoplayer.domain.usecase

import com.videoplayer.data.model.Channel
import com.videoplayer.data.repository.EpgRepository
import com.videoplayer.data.repository.EpgWindow
import java.time.Instant
import javax.inject.Inject

/**
 * Use case for calculating EPG window based on channels and days ahead
 */
class CalculateEpgWindowUseCase @Inject constructor(
    private val epgRepository: EpgRepository
) {
    /**
     * Calculate EPG window for given channels and days ahead
     */
    suspend operator fun invoke(
        channels: List<Channel>,
        epgDaysAhead: Int,
        now: Instant = Instant.now()
    ): EpgWindow {
        return epgRepository.calculateWindow(channels, epgDaysAhead, now)
    }
}
