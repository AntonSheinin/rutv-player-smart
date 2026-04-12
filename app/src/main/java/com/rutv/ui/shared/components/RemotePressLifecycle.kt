package com.rutv.ui.shared.components

/**
 * Shared remote button press lifecycle handling:
 * - First KeyDown arms a pending short press.
 * - First repeat (or native long-press flag) triggers long press once.
 * - KeyUp triggers short press only when long press was not fired.
 *
 * This keeps behavior deterministic and avoids timer-based fallbacks.
 */
class RemotePressLifecycle {
    private var isDown: Boolean = false
    private var longHandled: Boolean = false

    fun onDown(
        repeatCount: Int,
        isLongPress: Boolean,
        onLongPress: () -> Boolean
    ): Boolean {
        val repeated = repeatCount > 0 || isLongPress
        if (!repeated) {
            // Always arm a fresh press on initial down; this also heals stale state
            // when KEY_UP was not delivered after a focus transfer.
            isDown = true
            longHandled = false
            return true
        }

        if (!isDown) {
            // First repeat event on a fresh state (focus transferred while key held).
            // Arm the state but skip long-press — the next repeat will fire it.
            isDown = true
            longHandled = false
            return true
        }
        if (!longHandled) {
            longHandled = onLongPress()
        }
        return true
    }

    fun onUp(onShortPress: () -> Unit): Boolean {
        if (!isDown) return false
        if (!longHandled) {
            onShortPress()
        }
        reset()
        return true
    }

    fun reset() {
        isDown = false
        longHandled = false
    }
}

/**
 * Same lifecycle as [RemotePressLifecycle], but keyed for multiple concurrent buttons
 * (e.g., LEFT and RIGHT in the same listener).
 */
class RemotePressRegistry<K> {
    private data class State(
        var isDown: Boolean = false,
        var longHandled: Boolean = false
    )

    private val states = mutableMapOf<K, State>()

    fun onDown(
        key: K,
        repeatCount: Int,
        isLongPress: Boolean,
        onLongPress: () -> Boolean
    ): Boolean {
        val state = states.getOrPut(key) { State() }
        val repeated = repeatCount > 0 || isLongPress
        if (!repeated) {
            // Always arm a fresh press on initial down; this also heals stale state
            // when KEY_UP was not delivered after a focus transfer.
            state.isDown = true
            state.longHandled = false
            return true
        }

        if (!state.isDown) {
            // First repeat event on a fresh state (focus transferred while key held).
            // Arm the state but skip long-press — the next repeat will fire it.
            // This prevents premature long-press when focus moves between controls.
            state.isDown = true
            state.longHandled = false
            return true
        }
        if (!state.longHandled) {
            state.longHandled = onLongPress()
        }
        return true
    }

    fun onUp(key: K, onShortPress: () -> Unit): Boolean {
        val state = states[key] ?: return false
        if (!state.isDown) {
            states.remove(key)
            return false
        }
        if (!state.longHandled) {
            onShortPress()
        }
        states.remove(key)
        return true
    }

    fun onUpWithResult(key: K, onShortPress: () -> Boolean): Boolean {
        val state = states[key] ?: return false
        if (!state.isDown) {
            states.remove(key)
            return false
        }
        val result = if (state.longHandled) {
            true
        } else {
            onShortPress()
        }
        states.remove(key)
        return result
    }

    fun reset(key: K) {
        states.remove(key)
    }

    fun resetAll() {
        states.clear()
    }
}
