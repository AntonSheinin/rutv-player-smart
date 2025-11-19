package com.rutv.ui.mobile.screens

import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.max

internal fun calculateScrollProgress(listState: LazyListState): Float {
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return 0f

    val averageItemSize = layoutInfo.visibleItemsInfo
        .takeIf { it.isNotEmpty() }
        ?.map { it.size }
        ?.average()
        ?.coerceAtLeast(1.0)
        ?: return 0f

    val viewportSize = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
    val totalContentHeight = (averageItemSize * totalItems).toInt()
    val maxScroll = (totalContentHeight - viewportSize).coerceAtLeast(1)
    val scrolled = (listState.firstVisibleItemIndex * averageItemSize + listState.firstVisibleItemScrollOffset).toInt()
    return (scrolled.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)
}

internal fun LazyListState.isItemFullyVisible(index: Int): Boolean {
    val layout = layoutInfo
    if (layout.visibleItemsInfo.isEmpty()) return false
    val viewportStart = layout.viewportStartOffset
    val viewportEnd = layout.viewportEndOffset
    val itemInfo = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    val itemStart = itemInfo.offset
    val itemEnd = itemStart + itemInfo.size
    return itemStart >= viewportStart && itemEnd <= viewportEnd
}

internal suspend fun LazyListState.scrollByIfPossible(delta: Float): Boolean {
    if (delta == 0f) return false
    val layoutInfo = layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems <= 0) return false

    val direction = if (delta > 0) 1 else -1
    val targetIndex = (firstVisibleItemIndex + direction).coerceIn(0, totalItems - 1)

    if (targetIndex == firstVisibleItemIndex) {
        if (direction < 0 && firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) {
            return false
        }
    }

    scrollToItem(targetIndex)
    return true
}

internal suspend fun LazyListState.centerOn(index: Int) {
    if (index < 0) return
    scrollToItem(index)
    val viewportSize = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
    val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val desiredOffset = (viewportSize / 2) - (targetInfo.size / 2)
    scrollToItem(index, -desiredOffset)
}
