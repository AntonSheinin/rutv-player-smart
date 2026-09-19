package com.rutv.presentation.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AudioLanguageStateTest {

    @Test
    fun preservesSupportedTrackLabelsAndTracksSelection() {
        val russianGroup = audioGroup(
            labels = listOf("RUS", "RUS"),
            supported = booleanArrayOf(true, true),
            selected = booleanArrayOf(false, true)
        )
        val englishGroup = audioGroup(
            labels = listOf("ENG"),
            supported = booleanArrayOf(true),
            selected = booleanArrayOf(false)
        )
        val unsupportedGermanGroup = audioGroup(
            labels = listOf("DEU"),
            supported = booleanArrayOf(false),
            selected = booleanArrayOf(false)
        )
        val unlabeledGroup = audioGroup(
            labels = listOf(null),
            supported = booleanArrayOf(true),
            selected = booleanArrayOf(false)
        )

        val state = Tracks(
            listOf(russianGroup, englishGroup, unsupportedGermanGroup, unlabeledGroup)
        ).toAudioLanguageState()

        assertEquals(listOf("RUS", "ENG"), state.availableTrackLabels)
        assertEquals("RUS", state.selectedTrackLabel)
    }

    @Test
    fun missingLanguageMetadataProducesNoChoices() {
        val state = Tracks(
            listOf(
                audioGroup(
                    labels = listOf(null, "  "),
                    supported = booleanArrayOf(true, true),
                    selected = booleanArrayOf(true, false)
                )
            )
        ).toAudioLanguageState()

        assertEquals(emptyList<String>(), state.availableTrackLabels)
        assertNull(state.selectedTrackLabel)
    }

    private fun audioGroup(
        labels: List<String?>,
        supported: BooleanArray,
        selected: BooleanArray
    ): Tracks.Group {
        val formats = labels.map(::audioFormat)
        val support = IntArray(formats.size) { index ->
            if (supported[index]) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE
        }
        return Tracks.Group(
            TrackGroup(*formats.toTypedArray()),
            false,
            support,
            selected
        )
    }

    private fun audioFormat(label: String?): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setLabel(label)
        .setLanguage("ru")
        .build()
}
