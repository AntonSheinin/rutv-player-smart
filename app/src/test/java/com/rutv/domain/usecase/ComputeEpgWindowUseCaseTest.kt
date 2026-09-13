package com.rutv.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ComputeEpgWindowUseCaseTest {
    @Test
    fun dayContainingUsesLocalDayAcrossDaylightSavingChange() {
        val zone = ZoneId.of("Europe/Berlin")
        val instant = Instant.parse("2026-03-29T12:00:00Z").toEpochMilli()

        val window = ComputeEpgWindowUseCase.dayContaining(instant, zone)

        assertEquals(Instant.parse("2026-03-28T23:00:00Z").toEpochMilli(), window.fromUtcMillis)
        assertEquals(Instant.parse("2026-03-29T22:00:00Z").toEpochMilli(), window.toUtcMillis)
    }
}
