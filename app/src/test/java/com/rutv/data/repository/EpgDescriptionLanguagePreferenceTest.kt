package com.rutv.data.repository

import android.app.Application
import com.rutv.util.EpgConstants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class EpgDescriptionLanguagePreferenceTest {
    private val preferences by lazy {
        PreferencesRepository(RuntimeEnvironment.getApplication(), PlaylistAccess())
    }

    @Test fun defaultsToRussianAndPersistsSupportedLanguages() = runBlocking {
        assertEquals(EpgConstants.DEFAULT_DESCRIPTION_LANGUAGE, preferences.epgDescriptionLanguage.first())

        for (language in EpgConstants.SUPPORTED_DESCRIPTION_LANGUAGES) {
            preferences.saveEpgDescriptionLanguage(language)
            assertEquals(language, preferences.epgDescriptionLanguage.first())
        }
    }

    @Test fun rejectsUnsupportedLanguage() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { preferences.saveEpgDescriptionLanguage("fr") }
        }
    }
}
