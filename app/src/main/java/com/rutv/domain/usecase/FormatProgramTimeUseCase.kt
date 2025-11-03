package com.rutv.domain.usecase

import com.rutv.ui.shared.presentation.TimeFormatter
import java.util.Date
import javax.inject.Inject

/**
 * Use case for formatting program time
 */
class FormatProgramTimeUseCase @Inject constructor() {
    /**
     * Format program date and time for display
     */
    operator fun invoke(timestampMillis: Long): String {
        return if (timestampMillis > 0L) {
            TimeFormatter.formatProgramDateTime(Date(timestampMillis))
        } else {
            "--"
        }
    }

    /**
     * Format EPG date for display
     */
    fun formatEpgDate(timestampMillis: Long): String {
        return TimeFormatter.formatEpgDate(Date(timestampMillis))
    }

    /**
     * Format time only for display
     */
    fun formatTime(timestampMillis: Long): String {
        return if (timestampMillis > 0L) {
            TimeFormatter.formatTime(Date(timestampMillis))
        } else {
            "--"
        }
    }
}
