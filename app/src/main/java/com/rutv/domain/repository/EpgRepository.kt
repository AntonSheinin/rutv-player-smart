package com.rutv.domain.repository

import com.rutv.data.model.EpgProgram

interface EpgRepository {

    enum class TimeChangeTrigger {
        TIMEZONE, TIME_SET, DATE, UNKNOWN
    }

    enum class TimeChangeResult {
        NONE, CLOCK_CHANGED, TIMEZONE_CHANGED
    }

    suspend fun handleSystemTimeOrTimezoneChange(
        trigger: TimeChangeTrigger,
        now: Long = System.currentTimeMillis()
    ): TimeChangeResult

    suspend fun getWindowedProgramsForChannel(
        epgUrl: String,
        tvgId: String,
        fromUtcMillis: Long,
        toUtcMillis: Long
    ): List<EpgProgram>

    suspend fun getCurrentProgram(tvgId: String): EpgProgram?

    suspend fun getProgramsForChannel(tvgId: String): List<EpgProgram>

    suspend fun clearCache()
}
