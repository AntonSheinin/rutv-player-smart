package com.rutv.ui.mobile.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester

/**
 * Centralized coordinator for playlist/EPG focus transitions.
 * Keeps imperative focus management out of composables.
 */
class RemoteFocusCoordinator(
    private val log: ((String) -> Unit)?
) {
    private var playlistController: ((Int, Boolean) -> Boolean)? = null
    private var playlistFocusRequester: FocusRequester? = null

    fun registerPlaylistController(controller: ((Int, Boolean) -> Boolean)?) {
        playlistController = controller
    }

    fun registerPlaylistRequester(focusRequester: FocusRequester?) {
        playlistFocusRequester = focusRequester
    }

    fun clearPlaylist() {
        playlistController = null
        playlistFocusRequester = null
    }

    fun focusPlaylist(index: Int, play: Boolean): Boolean {
        val handled = playlistController?.invoke(index, play) ?: false
        return handled
    }

    fun requestPlaylistFocus() {
        playlistFocusRequester?.requestFocus()
    }
}

@Composable
fun rememberRemoteFocusCoordinator(
    log: ((String) -> Unit)?
): RemoteFocusCoordinator = remember(log) {
    RemoteFocusCoordinator(log)
}
