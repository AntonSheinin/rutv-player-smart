package com.rutv.data.model

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class EpgProgramTest {
    @Test fun equivalentTimestampFormatsAndExclusiveStop() {
        val iso = EpgProgram("1", "2026-09-06T10:00:00Z", "2026-09-06T11:00:00Z", "test")
        val xml = EpgProgram("1", "20260906130000 +0300", "20260906140000 +0300", "test")
        assertEquals(iso.startUtcMillis, xml.startUtcMillis)
        assertTrue(iso.isCurrent(iso.startUtcMillis))
        assertFalse(iso.isCurrent(iso.stopUtcMillis))
        assertEquals(0L, EpgProgram("", "bad", "bad", "").startUtcMillis)
    }

    @Test fun localTimestampUsesCurrentTimezoneOnSameThread() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+01:00"))
            val first = EpgProgram("", "2026-09-06T10:00:00", "2026-09-06T11:00:00", "")
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+03:00"))
            val second = EpgProgram("", "2026-09-06T10:00:00", "2026-09-06T11:00:00", "")
            assertEquals(7_200_000L, first.startUtcMillis - second.startUtcMillis)
        } finally { TimeZone.setDefault(previous) }
    }
}
