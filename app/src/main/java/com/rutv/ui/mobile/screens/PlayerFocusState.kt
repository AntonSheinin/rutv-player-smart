package com.rutv.ui.mobile.screens

import androidx.compose.ui.focus.FocusRequester

/**
 * Grouped focus state for PlayerScreen
 * Separates concerns: Compose focus vs Android View focus vs visual hints
 */

/**
 * Focus state for Compose-based UI elements (custom controls, playlist, EPG)
 */
data class ComposeFocusState(
    val leftColumnRequesters: List<FocusRequester>? = null,
    val rightColumnRequesters: List<FocusRequester>? = null,
    val playlistRequester: FocusRequester? = null,
    val playlistReady: Boolean = false,
    val lastFocusedPlaylistIndex: Int = -1
)

/**
 * Focus state for EPG panel
 * Uses token-based system for timing coordination
 */
data class EpgFocusState(
    val requestToken: Int = 0,
    val targetIndex: Int? = null,
    val suppressFallback: Boolean = false
)

/**
 * Visual hint state for UI feedback (separate from actual focus)
 */
data class VisualHintState(
    val favoritesHint: Boolean = false,
    val rotateHint: Boolean = false
)

/**
 * Complete focus state for PlayerScreen
 * Groups all focus-related state in one place
 */
data class PlayerFocusState(
    val compose: ComposeFocusState = ComposeFocusState(),
    val epg: EpgFocusState = EpgFocusState(),
    val visualHints: VisualHintState = VisualHintState()
)

