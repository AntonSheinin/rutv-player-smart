package com.videoplayer.domain.usecase

import com.videoplayer.data.model.Channel
import com.videoplayer.data.model.EpgResponse
import com.videoplayer.data.repository.ChannelRepository
import com.videoplayer.data.repository.EpgRepository
import com.videoplayer.data.repository.PreferencesRepository
import com.videoplayer.util.Result
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * Use case for fetching EPG data
 */
class FetchEpgUseCase @Inject constructor(
    private val epgRepository: EpgRepository,
    private val channelRepository: ChannelRepository,
    private val preferencesRepository: PreferencesRepository
) {

    /**
     * Fetch EPG data for all channels
     */
    suspend operator fun invoke(): Result<EpgResponse> {
        try {
            // Get EPG URL
            val epgUrl = preferencesRepository.epgUrl.first()
            if (epgUrl.isBlank()) {
                Timber.w("EPG URL not configured")
                return Result.Error(Exception("EPG URL not configured"))
            }

            // Check health first
            Timber.d("Checking EPG service health")
            when (val healthResult = epgRepository.checkHealth(epgUrl)) {
                is Result.Success -> {
                    if (!healthResult.data) {
                        Timber.w("EPG service is not healthy")
                        return Result.Error(Exception("EPG service not healthy"))
                    }
                }
                is Result.Error -> {
                    Timber.e("EPG health check failed: ${healthResult.message}")
                    return healthResult
                }
                is Result.Loading -> return Result.Error(Exception("Unexpected loading state"))
            }

            // Get channels
            val channelsResult = channelRepository.getAllChannels()
            val channels = when (channelsResult) {
                is Result.Success -> channelsResult.data
                is Result.Error -> return channelsResult
                is Result.Loading -> return Result.Error(Exception("Unexpected loading state"))
            }

            if (channels.isEmpty()) {
                Timber.w("No channels to fetch EPG for")
                return Result.Error(Exception("No channels loaded"))
            }

            val channelsWithEpg = channels.filter { it.hasEpg }
            if (channelsWithEpg.isEmpty()) {
                Timber.w("No channels with EPG configuration")
                return Result.Error(Exception("No channels with EPG"))
            }

            // Fetch EPG data
            Timber.d("Fetching EPG for ${channelsWithEpg.size} channels")
            return epgRepository.fetchEpgData(epgUrl, channels)

        } catch (e: Exception) {
            Timber.e(e, "Error in FetchEpgUseCase")
            return Result.Error(e)
        }
    }
}
