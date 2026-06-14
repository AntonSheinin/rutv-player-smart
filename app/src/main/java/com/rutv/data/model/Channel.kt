package com.rutv.data.model

/**
 * Domain model for a channel.
 * This is the model used throughout the app.
 */
data class Channel(
    val url: String,
    val title: String,
    val logo: String = "",
    val group: String = "General",
    /**
     * Optional multi-category/group support (some IPTV playlists assign a stream to multiple groups).
     *
     * Backward compatible:
     * - [group] remains the "primary" group used by existing UI.
     * - [groups] carries the full set when present.
     */
    val groups: List<String> = emptyList(),
    val tvgId: String = "",
    val catchupDays: Int = 0,
    val catchupSource: String = "",
    val isFavorite: Boolean = false,
    val isLocked: Boolean = false,
    val resizeMode: ResizeMode = ResizeMode.FIT,
    val position: Int = 0
) {
    val hasEpg: Boolean
        get() = tvgId.isNotBlank()

    val allGroups: List<String>
        get() = buildList {
            if (group.isNotBlank()) add(group)
            groups.filter { it.isNotBlank() }.forEach { g ->
                if (!contains(g)) add(g)
            }
        }

    fun supportsCatchup(): Boolean = hasEpg && catchupDays > 0
}
