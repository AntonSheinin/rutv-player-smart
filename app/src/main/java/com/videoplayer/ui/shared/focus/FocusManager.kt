package com.videoplayer.ui.shared.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester

/**
 * Focus state management for remote control navigation
 * Handles focus restoration and transitions between panels
 */
object FocusManager {
    // Focus state storage for restoration
    private var savedFocusState: FocusState? = null

    data class FocusState(
        val panel: PanelType,
        val itemIndex: Int? = null,
        val itemId: String? = null
    )

    enum class PanelType {
        NONE,
        PLAYLIST,
        EPG,
        SETTINGS,
        DIALOG,
        PROGRAM_DETAILS
    }

    /**
     * Save focus state when panel closes
     */
    fun saveFocusState(panel: PanelType, itemIndex: Int? = null, itemId: String? = null) {
        savedFocusState = FocusState(panel, itemIndex, itemId)
    }

    /**
     * Get saved focus state for restoration
     */
    fun getSavedFocusState(): FocusState? = savedFocusState

    /**
     * Clear saved focus state
     */
    fun clearSavedFocusState() {
        savedFocusState = null
    }

    /**
     * Get initial focus index for panel
     * Returns saved index if available, otherwise returns current playing/selected index
     */
    @Composable
    fun getInitialFocusIndex(
        panel: PanelType,
        currentIndex: Int,
        savedIndex: Int?
    ): Int {
        return remember(panel, currentIndex, savedIndex) {
            savedIndex ?: currentIndex
        }
    }
}

