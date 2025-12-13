package com.rutv.presentation.main.usecase

import com.rutv.data.model.Channel
import com.rutv.data.repository.PreferencesRepository
import com.rutv.presentation.player.PlayerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Initializes the player deterministically based on persisted user preferences.
 */
class InitializePlayerUseCase @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val playerManager: PlayerManager
) {
    suspend operator fun invoke(channels: List<Channel>): Channel? {
        if (channels.isEmpty()) return null
        try {
            val config = preferencesRepository.playerConfig.first()
            val lastPlayedIndex = preferencesRepository.lastPlayedIndex.first()

            val startIndex = if (lastPlayedIndex in channels.indices) lastPlayedIndex else 0
            playerManager.initialize(channels, config, startIndex)
            return channels.getOrNull(startIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep failure handling at the ViewModel boundary (PlayerManager already pushes PlayerState.Error).
            return channels.firstOrNull()
        }
    }
}


