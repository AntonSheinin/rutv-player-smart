package com.rutv.ui.shared.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * Shared helpers for "wait until LazyList has been laid out" patterns.
 *
 * This avoids scattered snapshotFlow/filter/first boilerplate and keeps focus logic consistent.
 */
suspend fun LazyListState.awaitFirstLayout() {
    snapshotFlow { layoutInfo.visibleItemsInfo.isNotEmpty() }
        .distinctUntilChanged()
        .filter { it }
        .first()
}


