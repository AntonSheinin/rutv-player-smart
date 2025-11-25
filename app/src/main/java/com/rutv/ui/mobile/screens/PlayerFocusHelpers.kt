package com.rutv.ui.mobile.screens

import com.rutv.data.model.Channel

/**
 * Helper functions for focus requests
 * Handles timing and coordination between different focus systems
 */

/**
 * Request EPG focus with token increment (preserves existing token system)
 */
fun PlayerFocusState.requestEpgFocus(targetIndex: Int?): PlayerFocusState {
    return copy(epg = epg.copy(
        targetIndex = targetIndex,
        requestToken = epg.requestToken + 1
    ))
}

/**
 * Request playlist focus safely (handles ready state)
 */
fun PlayerFocusState.requestPlaylistFocus(
    coordinator: RemoteFocusCoordinator,
    debugLogger: ((String) -> Unit)?
): PlayerFocusState {
    return if (compose.playlistReady) {
        coordinator.requestPlaylistFocus()
        this
    } else {
        debugLogger?.invoke("requestPlaylistFocus() deferred - requester missing")
        this
    }
}

/**
 * Transition focus from EPG panel to playlist panel
 */
fun PlayerFocusState.transitionFromEpgToPlaylist(
    coordinator: RemoteFocusCoordinator,
    allChannels: List<Channel>,
    currentChannelIndex: Int,
    debugLogger: ((String) -> Unit)?
): PlayerFocusState {
    debugLogger?.invoke("EPG->Playlist: Transferring focus")

    val targetIndex = when {
        compose.lastFocusedPlaylistIndex >= 0 -> compose.lastFocusedPlaylistIndex
        currentChannelIndex >= 0 -> currentChannelIndex
        else -> -1
    }
    val resolvedIndex = when {
        targetIndex >= 0 && targetIndex < allChannels.size -> targetIndex
        allChannels.isNotEmpty() -> 0
        else -> -1
    }
    if (resolvedIndex >= 0) {
        debugLogger?.invoke("EPG->Playlist: Focusing channel $resolvedIndex")
        coordinator.focusPlaylist(resolvedIndex, false)
    }

    return requestPlaylistFocus(coordinator, debugLogger)
}

/**
 * Update visual hint state (separate from focus)
 */
fun PlayerFocusState.updateVisualHint(
    favoritesHint: Boolean? = null,
    rotateHint: Boolean? = null
): PlayerFocusState {
    return copy(visualHints = visualHints.copy(
        favoritesHint = favoritesHint ?: visualHints.favoritesHint,
        rotateHint = rotateHint ?: visualHints.rotateHint
    ))
}

