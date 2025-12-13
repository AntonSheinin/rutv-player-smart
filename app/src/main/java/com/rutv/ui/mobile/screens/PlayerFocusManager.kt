package com.rutv.ui.mobile.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Represents distinct focus areas in the player screen.
 */
enum class PlayerFocusDestination {
    /** ExoPlayer controls + Custom Control Buttons */
    PLAYER_CONTROLS,
    /** Channel playlist panel */
    PLAYLIST_PANEL,
    /** EPG program list panel */
    EPG_PANEL,
    /** Program details dialog */
    PROGRAM_DETAILS,
    /** Video only, no UI overlays */
    NONE
}

/**
 * Centralized state-driven focus manager for PlayerScreen.
 * Replaces the older imperative focus coordinator with a declarative state machine.
 */
class PlayerFocusManager(
    private val log: ((String) -> Unit)?,
    initial: PlayerFocusDestination = PlayerFocusDestination.NONE
) {
    private val destinationState = mutableStateOf<PlayerFocusDestination>(initial)
    val currentDestination: PlayerFocusDestination
        get() = destinationState.value

    private val focusRegistry = mutableMapOf<PlayerFocusDestination, FocusRequester?>()
    private val playlistFocusCallbacks = mutableMapOf<PlayerFocusDestination, ((Int, Boolean) -> Boolean)?>()

    /**
     * Register a focus entry point for a destination.
     * Components should call this when they mount.
     */
    fun registerEntry(destination: PlayerFocusDestination, requester: FocusRequester?) {
        focusRegistry[destination] = requester
        // If this destination is currently active and we just registered, request focus
        if (destinationState.value == destination && requester != null) {
            requester.requestFocus()
        }
    }

    /**
     * Unregister a focus entry point.
     * Components should call this when they unmount.
     */
    fun unregisterEntry(destination: PlayerFocusDestination) {
        focusRegistry.remove(destination)
    }

    /**
     * Request focus to move to a destination.
     * This updates state and triggers the registered FocusRequester if available.
     */
    fun requestEnter(destination: PlayerFocusDestination) {
        if (destinationState.value == destination) {
            // Already at this destination, just ensure focus is requested
            focusRegistry[destination]?.requestFocus()
            return
        }

        destinationState.value = destination

        val requester = focusRegistry[destination]
        if (requester != null) {
            requester.requestFocus()
        }
    }

    /**
     * Get the registered requester for a destination (for special cases like playlist index focusing).
     */
    fun getRequester(destination: PlayerFocusDestination): FocusRequester? {
        return focusRegistry[destination]
    }

    /**
     * Register a focus callback for a destination (e.g., to focus a specific playlist index).
     */
    fun registerFocusCallback(destination: PlayerFocusDestination, callback: ((Int, Boolean) -> Boolean)?) {
        playlistFocusCallbacks[destination] = callback
    }

    /**
     * Request focus on a specific item within a destination (e.g., playlist channel index).
     */
    fun focusItem(destination: PlayerFocusDestination, index: Int, play: Boolean = false): Boolean {
        val callback = playlistFocusCallbacks[destination]
        return callback?.invoke(index, play) ?: false
    }
}

@Composable
fun rememberPlayerFocusManager(
    initial: PlayerFocusDestination = PlayerFocusDestination.NONE,
    log: ((String) -> Unit)? = null
): PlayerFocusManager = remember(initial, log) {
    PlayerFocusManager(log, initial)
}

/**
 * Composable effect that watches for focus requests and triggers when requester becomes available.
 * This handles the race condition where a destination is requested before its requester is registered.
 */
@Composable
fun PlayerFocusManager.WatchForPendingRequests() {
    val currentDest = currentDestination
    val requester = remember(currentDest) { getRequester(currentDest) }

    LaunchedEffect(currentDest, requester) {
        if (requester != null) {
            // Wait for at least one frame so layout/focus nodes are ready (no fixed delay).
            withFrameNanos { }
            requester.requestFocus()
        }
    }
}

