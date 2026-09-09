package com.rutv.data.repository

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EpgRemoteDataSourceTest {
    private val remote = EpgRemoteDataSource(Gson())
    @Test fun explicitEmptyAndNullableBackendVersionAreValid() {
        val response = remote.parseEpgResponseStreaming("{\"last_epg_update_at\":null,\"epg\":{\"one\":[]}}".reader())
        assertTrue(response.epg.containsKey("one"))
        assertTrue(response.epg.getValue("one").isEmpty())
    }
    @Test fun missingOrMalformedMapIsFailureInsteadOfEmptySuccess() {
        for (json in listOf("{}", "{\"epg\":", "{\"epg\":null}")) {
            try { remote.parseEpgResponseStreaming(json.reader()); fail("Expected parse failure") } catch (_: IOException) { }
        }
    }
}
