package com.rutv.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveEndBehaviorTest {
    @Test
    fun missingOrInvalidStoredValueDefaultsToAsk() {
        assertEquals(ArchiveEndBehavior.ASK, ArchiveEndBehavior.fromStoredValue(null))
        assertEquals(ArchiveEndBehavior.ASK, ArchiveEndBehavior.fromStoredValue(""))
        assertEquals(ArchiveEndBehavior.ASK, ArchiveEndBehavior.fromStoredValue("UNKNOWN"))
    }

    @Test
    fun storedValuesRoundTrip() {
        ArchiveEndBehavior.entries.forEach { behavior ->
            assertEquals(behavior, ArchiveEndBehavior.fromStoredValue(behavior.storedValue))
        }
    }
}
