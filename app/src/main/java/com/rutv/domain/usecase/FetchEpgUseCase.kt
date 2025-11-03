package com.rutv.domain.usecase

import androidx.media3.common.util.UnstableApi
import com.rutv.data.model.EpgResponse
import com.rutv.data.repository.ChannelRepository
import com.rutv.data.repository.EpgRepository
import com.rutv.data.repository.PreferencesRepository
import com.rutv.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Use case for fetching EPG data
 */
@UnstableApi
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
            Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Timber.d("       EPG FETCH STARTED")
            Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            // Get EPG URL
            val epgUrl = preferencesRepository.epgUrl.first()
            if (epgUrl.isBlank()) {
                Timber.w("✗ EPG URL not configured")
                return Result.Error(Exception("EPG URL not configured"))
            }
            Timber.d("EPG Service URL: $epgUrl")

            // Check health first
            Timber.d("━━━ HEALTH CHECK ━━━")
            val healthStart = System.currentTimeMillis()
            when (val healthResult = epgRepository.checkHealth(epgUrl)) {
                is Result.Success -> {
                    val healthDuration = System.currentTimeMillis() - healthStart
                    if (!healthResult.data) {
                        Timber.w("✗ EPG service is not healthy (took ${healthDuration}ms)")
                        return Result.Error(Exception("EPG service not healthy"))
                    }
                    Timber.d("✓ EPG service healthy (took ${healthDuration}ms)")
                }
                is Result.Error -> {
                    val healthDuration = System.currentTimeMillis() - healthStart
                    Timber.e("✗ EPG health check failed: ${healthResult.message} (took ${healthDuration}ms)")
                    return healthResult
                }
                is Result.Loading -> return Result.Error(Exception("Unexpected loading state"))
            }

            // Get channels
            Timber.d("━━━ CHANNEL VALIDATION ━━━")
            val channelsResult = channelRepository.getAllChannels()
            val channels = when (channelsResult) {
                is Result.Success -> channelsResult.data
                is Result.Error -> {
                    Timber.e("✗ Failed to get channels: ${channelsResult.message}")
                    return channelsResult
                }
                is Result.Loading -> return Result.Error(Exception("Unexpected loading state"))
            }

            if (channels.isEmpty()) {
                Timber.w("✗ No channels to fetch EPG for")
                return Result.Error(Exception("No channels loaded"))
            }
            Timber.d("Total channels loaded: ${channels.size}")

            val channelsWithEpg = channels.filter { it.hasEpg }
            if (channelsWithEpg.isEmpty()) {
                Timber.w("✗ No channels provide EPG identifiers (tvg-id)")
                return Result.Error(Exception("No channels with EPG identifiers"))
            }
            Timber.d("Channels with EPG: ${channelsWithEpg.size}/${channels.size}")

            val epgDaysAhead = preferencesRepository.epgDaysAhead.first()
            val window = epgRepository.calculateWindow(channelsWithEpg, epgDaysAhead)
            Timber.d("Desired EPG window: ${window.fromInstant} -> ${window.toInstant}")

            // If cache already covers desired window, skip network fetch
            val cacheCoversWindow = withContext(Dispatchers.IO) {
                epgRepository.coversWindow(window)
            }
            if (cacheCoversWindow) {
                val cached = withContext(Dispatchers.IO) { epgRepository.loadEpgData() }
                if (cached != null) {
                    Timber.d("EPG cache already covers desired window, skipping fetch")
                    return Result.Success(cached)
                }
            }

            // Fetch EPG data
            Timber.d("━━━ FETCHING EPG DATA ━━━")
            val fetchStart = System.currentTimeMillis()
            val result = epgRepository.fetchEpgData(epgUrl, channelsWithEpg, window)
            val totalDuration = System.currentTimeMillis() - fetchStart

            when (result) {
                is Result.Success -> {
                    Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Timber.d("  ✓ EPG FETCH COMPLETED")
                    Timber.d("  Total time: ${totalDuration}ms")
                    Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                }
                is Result.Error -> {
                    Timber.e("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Timber.e("  ✗ EPG FETCH FAILED")
                    Timber.e("  Error: ${result.message}")
                    Timber.e("  Time: ${totalDuration}ms")
                    Timber.e("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                }
                is Result.Loading -> {}
            }

            return result

        } catch (e: Exception) {
            Timber.e(e, "✗ Exception in FetchEpgUseCase")
            return Result.Error(e)
        }
    }
}

