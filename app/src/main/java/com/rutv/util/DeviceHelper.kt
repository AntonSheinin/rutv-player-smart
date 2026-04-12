package com.rutv.util

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import com.rutv.util.logDebug
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Utility for generic remote control detection and input method tracking
 * Works with any Android STB/TV device with remote control (IR, Bluetooth, USB)
 */
object DeviceHelper {
    private val remoteMode = AtomicBoolean(false)
    private val remoteControlPresentCache = AtomicReference<Boolean?>(null)

    /**
     * Check if any remote control or game controller is connected
     * Supports IR remotes (via USB receiver), Bluetooth remotes, and game controllers
     */
    fun hasRemoteControl(context: Context): Boolean {
        remoteControlPresentCache.get()?.let { return it }

        val start = System.currentTimeMillis()
        val deviceIds = InputDevice.getDeviceIds()
        for (deviceId in deviceIds) {
            val device = InputDevice.getDevice(deviceId) ?: continue

            // Check if device has D-pad (common in remotes)
            val sources = device.sources
            if (sources and InputDevice.SOURCE_DPAD != 0) {
                remoteControlPresentCache.set(true)
                return true
            }

            // Check if device has gamepad buttons (some remotes report as gamepad)
            if (sources and InputDevice.SOURCE_GAMEPAD != 0) {
                remoteControlPresentCache.set(true)
                return true
            }

            // Check if device has keyboard (some IR remotes report as keyboard)
            if (sources and InputDevice.SOURCE_KEYBOARD != 0) {
                // Verify it's not a physical keyboard by checking if it has D-pad keys
                if (device.hasKeys(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN).any { it }) {
                    remoteControlPresentCache.set(true)
                    logDebug { "Remote detection: found remote-like keyboard in ${System.currentTimeMillis() - start}ms" }
                    return true
                }
            }
        }
        remoteControlPresentCache.set(false)
        logDebug { "Remote detection: no remote found in ${System.currentTimeMillis() - start}ms (devices=${deviceIds.size})" }
        return false
    }

    /**
     * Check if remote input is currently active
     * Static flag configured at app start for TV/STB devices.
     */
    fun isRemoteInputActive(): Boolean {
        return remoteMode.get()
    }

    /**
     * Force remote mode regardless of last input method.
     * Useful for TV/STB devices where DPAD is the primary input.
     */
    fun setForceRemoteMode(enabled: Boolean) {
        remoteMode.set(enabled)
    }

}

