package com.rutv.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlin.math.max
import kotlin.math.min

/**
 * Simple PagingSource that serves data from an already materialized list.
 */
class StaticPagingSource<T : Any>(
    private val data: List<T>
) : PagingSource<Int, T>() {

    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val anchor = state.anchorPosition ?: return null
        val closest = state.closestPageToPosition(anchor)
        return closest?.prevKey?.plus(1) ?: closest?.nextKey?.minus(1)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val start = params.key ?: 0
        if (start >= data.size) {
            return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
        }
        val end = min(start + params.loadSize, data.size)
        val subList = data.subList(start, end)
        val prevKey = if (start == 0) null else max(start - params.loadSize, 0)
        val nextKey = if (end < data.size) end else null
        return LoadResult.Page(
            data = subList,
            prevKey = prevKey,
            nextKey = nextKey
        )
    }
}
