package com.videoplayer.ui.shared.presentation

import java.text.SimpleDateFormat
import java.util.*

/**
 * Centralized time and date formatting utilities
 * Uses locale-aware formatting for proper internationalization
 */
object TimeFormatter {
    private val locale: Locale
        get() = Locale.getDefault()

    /**
     * Format a date for EPG display (e.g., "Monday, January 15")
     */
    fun formatEpgDate(date: Date): String {
        val format = SimpleDateFormat("EEEE, MMMM d", locale)
        return format.format(date)
    }

    /**
     * Format a date and time for program details (e.g., "14:30, Monday, January 15")
     */
    fun formatProgramDateTime(date: Date): String {
        val format = SimpleDateFormat("HH:mm, EEEE, MMMM d", locale)
        return format.format(date)
    }

    /**
     * Format a time only (e.g., "14:30")
     */
    fun formatTime(date: Date): String {
        val format = SimpleDateFormat("HH:mm", locale)
        return format.format(date)
    }

    /**
     * Format a date only (e.g., "January 15, 2024")
     */
    fun formatDate(date: Date): String {
        val format = SimpleDateFormat("MMMM d, yyyy", locale)
        return format.format(date)
    }

    /**
     * Format duration in minutes to a human-readable string
     */
    fun formatDuration(minutes: Int): String {
        return when {
            minutes < 60 -> "${minutes}m"
            minutes % 60 == 0 -> "${minutes / 60}h"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }
}
