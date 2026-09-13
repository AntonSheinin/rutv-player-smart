package com.rutv.ui.mobile.screens

import com.rutv.data.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerEpgPanelKeyTest {
    @Test
    fun duplicateProviderRowsReceiveUniqueStableKeys() {
        val duplicate = EpgProgram(
            id = "same",
            startTime = "",
            stopTime = "",
            title = "Program",
            startTimeMillis = 100L,
            stopTimeMillis = 200L
        )

        val keys = programItemKeys(listOf(duplicate, duplicate, duplicate.copy(id = "other")))

        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys, programItemKeys(listOf(duplicate, duplicate, duplicate.copy(id = "other"))))
    }
}
