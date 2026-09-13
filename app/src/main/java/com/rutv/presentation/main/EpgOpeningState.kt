package com.rutv.presentation.main

import androidx.compose.runtime.Immutable
import com.rutv.data.model.EpgProgram

@Immutable
data class EpgOpeningState(
    val session: Long,
    val channelTvgId: String,
    val target: EpgProgram?,
    val targetLoadPending: Boolean
)

@Immutable
internal data class EpgProgramIdentity(
    val id: String?,
    val startUtcMillis: Long,
    val stopUtcMillis: Long
)

internal fun EpgProgram.focusIdentity(): EpgProgramIdentity? {
    val normalizedId = id.trim().takeIf(String::isNotEmpty)
    return when {
        normalizedId != null -> EpgProgramIdentity(normalizedId, 0L, 0L)
        startUtcMillis > 0L && stopUtcMillis > startUtcMillis ->
            EpgProgramIdentity(null, startUtcMillis, stopUtcMillis)
        else -> null
    }
}

internal fun List<EpgProgram>.indexOfProgram(identity: EpgProgramIdentity?): Int {
    if (identity == null) return -1
    return withIndex()
        .sortedBy { it.value.startUtcMillis }
        .firstOrNull { it.value.focusIdentity() == identity }
        ?.index
        ?: -1
}

internal fun resolveInitialEpgProgramIndex(
    programs: List<EpgProgram>,
    targetIdentity: EpgProgramIdentity?,
    nowUtcMillis: Long
): Int {
    if (programs.isEmpty()) return -1
    val targetIndex = programs.indexOfProgram(targetIdentity)
    if (targetIndex >= 0) return targetIndex
    val currentIndex = programs.indexOfFirst { it.isCurrent(nowUtcMillis) }
    return currentIndex.takeIf { it >= 0 } ?: 0
}

internal fun List<EpgProgram>.overlapping(fromUtcMillis: Long, toUtcMillis: Long): List<EpgProgram> =
    filter { it.stopUtcMillis > fromUtcMillis && it.startUtcMillis < toUtcMillis }

internal data class EpgTargetWindow(
    val target: EpgProgram,
    val dayAnchorUtcMillis: Long
)

internal fun resolveEpgTargetWindow(
    playbackTarget: EpgProgram?,
    cachedPrograms: List<EpgProgram>
): EpgTargetWindow? {
    val target = playbackTarget ?: return null
    val timestampSource = target.takeIf { it.startUtcMillis > 0L && it.stopUtcMillis > it.startUtcMillis }
        ?: target.focusIdentity()
            ?.let { cachedPrograms.indexOfProgram(it) }
            ?.takeIf { it >= 0 }
            ?.let(cachedPrograms::get)
            ?.takeIf { it.startUtcMillis > 0L && it.stopUtcMillis > it.startUtcMillis }
        ?: return null
    return EpgTargetWindow(target = target, dayAnchorUtcMillis = timestampSource.startUtcMillis)
}

internal fun MainViewState.withEpgPanelClosed(): MainViewState = copy(
    showEpgPanel = false,
    isEpgLoading = false,
    epgOpeningState = null
)
