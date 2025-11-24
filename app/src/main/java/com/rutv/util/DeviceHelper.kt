package com.rutv.util

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Utility for generic remote control detection and input method tracking
 * Works with any Android STB/TV device with remote control (IR, Bluetooth, USB)
 */
object DeviceHelper {
    private val lastRemoteInputTime = AtomicLong(0L)
    private val lastTouchInputTime = AtomicLong(0L)
    private val isRemoteInputActive = AtomicBoolean(false)
    private val forceRemoteMode = AtomicBoolean(false)

    private const val INPUT_METHOD_TIMEOUT_MS = 5000L // 5 seconds timeout

    /**
     * Check if any remote control or game controller is connected
     * Supports IR remotes (via USB receiver), Bluetooth remotes, and game controllers
     */
    fun hasRemoteControl(context: Context): Boolean {
        val deviceIds = InputDevice.getDeviceIds()
        for (deviceId in deviceIds) {
            val device = InputDevice.getDevice(deviceId) ?: continue

            // Check if device has D-pad (common in remotes)
            val sources = device.sources
            if (sources and InputDevice.SOURCE_DPAD != 0) {
                return true
            }

            // Check if device has gamepad buttons (some remotes report as gamepad)
            if (sources and InputDevice.SOURCE_GAMEPAD != 0) {
                return true
            }

            // Check if device has keyboard (some IR remotes report as keyboard)
            if (sources and InputDevice.SOURCE_KEYBOARD != 0) {
                // Verify it's not a physical keyboard by checking if it has D-pad keys
                if (device.hasKeys(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN).any { it }) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Detect if input event is from remote control or touch
     * Based on event source and device type
     */
    fun detectInputMethod(event: KeyEvent?): Boolean {
        if (event == null) return false

        val device = event.device
        val source = event.source

        // Remote control indicators:
        // - D-pad source
        // - Gamepad source (some remotes)
        // - Keyboard source with D-pad keys
        val isRemote = when {
            source and InputDevice.SOURCE_DPAD != 0 -> true
            source and InputDevice.SOURCE_GAMEPAD != 0 -> true
            source and InputDevice.SOURCE_KEYBOARD != 0 -> {
                // Check if it's a remote by looking at key codes or device name
                val keyCode = event.keyCode
                keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
                keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
                device?.name?.contains("remote", ignoreCase = true) == true ||
                device?.name?.contains("ir", ignoreCase = true) == true
            }
            else -> false
        }

        return isRemote
    }

    /**
     * Update tracking of last input method used
     * Call this when processing key events
     */
    fun updateLastInputMethod(event: KeyEvent?) {
        if (event == null) return

        val now = System.currentTimeMillis()
        val isRemote = detectInputMethod(event)

        if (isRemote) {
            lastRemoteInputTime.set(now)
            isRemoteInputActive.set(true)
        } else {
            lastTouchInputTime.set(now)
            // Check if touch was recent - if not, keep remote as active
            val timeSinceTouch = now - lastTouchInputTime.get()
            if (timeSinceTouch > INPUT_METHOD_TIMEOUT_MS) {
                // Touch hasn't been used recently, assume remote is still active
                // Only switch if touch was very recent
            } else {
                isRemoteInputActive.set(false)
            }
        }
    }

    /**
     * Check if remote input is currently active
     * Returns true if remote was last used input method
     */
    fun isRemoteInputActive(): Boolean {
        if (forceRemoteMode.get()) return true
        val now = System.currentTimeMillis()
        val timeSinceRemote = now - lastRemoteInputTime.get()
        val timeSinceTouch = now - lastTouchInputTime.get()

        // Remote is active if:
        // - Remote was used more recently than touch AND within timeout
        // - OR touch hasn't been used recently (timeout) and remote was used
        return when {
            timeSinceRemote < INPUT_METHOD_TIMEOUT_MS &&
            lastRemoteInputTime.get() > lastTouchInputTime.get() -> true
            timeSinceTouch > INPUT_METHOD_TIMEOUT_MS &&
            timeSinceRemote < INPUT_METHOD_TIMEOUT_MS -> true
            else -> isRemoteInputActive.get()
        }
    }

    /**
     * Explicitly mark a remote interaction when we know DPAD navigation occurred but
     * don't have a reliable KeyEvent source to inspect (e.g., focus jumps triggered
     * from legacy views).
     */
    fun markRemoteInteraction() {
        val now = System.currentTimeMillis()
        lastRemoteInputTime.set(now)
        isRemoteInputActive.set(true)
    }

    /**
     * Force remote mode regardless of last input method.
     * Useful for TV/STB devices where DPAD is the primary input.
     */
    fun setForceRemoteMode(enabled: Boolean) {
        forceRemoteMode.set(enabled)
        if (enabled) {
            val now = System.currentTimeMillis()
            lastRemoteInputTime.set(now)
            isRemoteInputActive.set(true)
        }
    }

    /**
     * Get list of connected remote-capable devices
     * For debugging/logging purposes
     */
    fun getConnectedRemoteDevices(): List<String> {
        val devices = mutableListOf<String>()
        val deviceIds = InputDevice.getDeviceIds()

        for (deviceId in deviceIds) {
            val device = InputDevice.getDevice(deviceId) ?: continue
            val sources = device.sources

            if (sources and InputDevice.SOURCE_DPAD != 0 ||
                sources and InputDevice.SOURCE_GAMEPAD != 0) {
                devices.add("${device.name} (ID: $deviceId)")
            }
        }

        return devices
    }
}

