package com.rutv.ui.shared.components

import androidx.compose.ui.input.key.Key

/**
 * Small, reusable dialog focus/navigation policy for remote control UX.
 *
 * This is intentionally pure logic (no Compose calls) so behavior stays consistent across dialogs.
 */
internal object DialogFocusPolicy {
    enum class Target { TextField, PrimaryAction, SecondaryAction }

    /**
     * Maps a DPAD key to a focus target transition.
     *
     * - [from] is the currently focused target.
     * - Returns the desired focus target, or null if policy does not handle the key.
     */
    fun nextTarget(from: Target, key: Key, hasSecondaryAction: Boolean): Target? =
        when (key) {
            Key.DirectionUp -> when (from) {
                Target.PrimaryAction, Target.SecondaryAction -> Target.TextField
                Target.TextField -> null
            }

            Key.DirectionDown -> when (from) {
                Target.TextField -> Target.PrimaryAction
                else -> null
            }

            Key.DirectionLeft -> when (from) {
                Target.PrimaryAction -> if (hasSecondaryAction) Target.SecondaryAction else null
                else -> null
            }

            Key.DirectionRight -> when (from) {
                Target.SecondaryAction -> Target.PrimaryAction
                else -> null
            }

            else -> null
        }
}


