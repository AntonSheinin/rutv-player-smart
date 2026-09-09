package com.rutv.presentation.main

import com.rutv.data.model.EpgProgram
import kotlinx.collections.immutable.toImmutableList

internal fun MainViewState.withEpgPage(
    tvgId: String, programs: List<EpgProgram>, from: Long, to: Long
): MainViewState {
    if (!showEpgPanel || epgChannelTvgId != tvgId) return this
    val preserved = epgPrograms.filterNot { it.stopUtcMillis > from && it.startUtcMillis < to }
    val combined = (preserved + programs).associateBy { it.id.ifBlank { "${it.startUtcMillis}:${it.title}" } }
        .values.sortedBy { it.startUtcMillis }.toImmutableList()
    return copy(
        epgPrograms = combined,
        epgLoadedFromUtc = if (epgLoadedFromUtc == 0L) from else minOf(epgLoadedFromUtc, from),
        epgLoadedToUtc = maxOf(epgLoadedToUtc, to)
    )
}
