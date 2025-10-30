package com.videoplayer.data.epg

import com.videoplayer.data.model.EpgProgram

/**
 * Provider for session-cached, windowed, per-channel EPG fetching.
 * (fromMillis, toMillis are UTC epoch ms)
 */
interface EpgProvider {
    suspend fun getEpgForChannel(tvgId: String, fromMillis: Long, toMillis: Long): List<EpgProgram>
    fun clearSessionCache()
}
