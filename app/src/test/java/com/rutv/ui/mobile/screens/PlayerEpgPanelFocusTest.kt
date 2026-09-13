package com.rutv.ui.mobile.screens

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.rutv.data.model.EpgProgram
import com.rutv.presentation.main.EpgOpeningState
import com.rutv.ui.theme.RuTvTheme
import com.rutv.util.DeviceHelper
import kotlinx.collections.immutable.toImmutableList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalTestApi::class)
class PlayerEpgPanelFocusTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var activity: ActivityController<ComponentActivity>

    @After
    fun tearDown() {
        DeviceHelper.setForceRemoteMode(false)
        if (::activity.isInitialized) activity.pause().stop().destroy()
    }

    @Test
    fun userNavigationPreventsLateTargetFromReplacingSelection() {
        DeviceHelper.setForceRemoteMode(true)
        val now = System.currentTimeMillis()
        val target = program("target", now - 7_200_000L, now - 3_600_000L)
        val userChoice = program("user", now + 3_600_000L, now + 7_200_000L)
        val programs = mutableStateOf(listOf(userChoice).toImmutableList())
        val opening = mutableStateOf(EpgOpeningState(1L, "channel", target, true))
        var activated: EpgProgram? = null
        val focusManager = PlayerFocusManager(PlayerFocusDestination.EPG_PANEL)

        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            RuTvTheme {
                EpgPanel(
                    programs = programs.value,
                    channel = null,
                    isLoading = true,
                    openingState = opening.value,
                    onProgramClick = { activated = it },
                    onPlayArchive = { activated = it },
                    isPlaylistOpen = false,
                    epgDaysPast = 7,
                    epgDaysAhead = 2,
                    epgLoadedFromUtc = 0L,
                    epgLoadedToUtc = 0L,
                    onLoadMorePast = {},
                    onLoadMoreFuture = {},
                    onClose = {},
                    focusManager = focusManager,
                    onEnsureDateRange = { _, _ -> }
                )
            }
        }

        val list = compose.onNode(hasScrollToIndexAction())
        list.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        compose.runOnUiThread {
            programs.value = listOf(target, userChoice).toImmutableList()
            opening.value = opening.value.copy(targetLoadPending = false)
        }
        compose.waitForIdle()
        list.performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }

        compose.runOnIdle { assertEquals(userChoice, activated) }
    }

    private fun program(id: String, start: Long, stop: Long) = EpgProgram(
        id = id,
        startTime = "",
        stopTime = "",
        title = id,
        startTimeMillis = start,
        stopTimeMillis = stop
    )
}
