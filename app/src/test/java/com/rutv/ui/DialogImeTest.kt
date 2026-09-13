package com.rutv.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.rutv.presentation.GoToChannelDialog
import com.rutv.presentation.ParentalPinDialog
import com.rutv.presentation.PinFailure
import com.rutv.presentation.PinOperation
import com.rutv.presentation.PinRequest
import com.rutv.presentation.PinStatus
import com.rutv.presentation.main.ParentalPinPrompt
import com.rutv.presentation.main.ParentalPinPromptReason
import com.rutv.ui.mobile.screens.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
abstract class DialogImeTestBase {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    protected fun show(content: @Composable () -> Unit) {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides null) { content() }
        }
    }

    @After fun closeActivity() {
        if (::activity.isInitialized) activity.pause().stop().destroy()
    }

}

class ChannelDoneDialogImeTest : DialogImeTestBase() {
    @Test fun channelDoneConfirmsOnceWithoutButtonFocus() {
        var calls = 0
        show {
            MaterialTheme {
                GoToChannelDialog(true, "2", 10, {}, { calls++ }, {})
            }
        }
        val field = compose.onNode(hasSetTextAction())
        field.performImeAction()
        assertEquals(1, calls)
    }

}

class SearchDoneDialogImeTest : DialogImeTestBase() {
    @Test fun searchBlankCanBeCorrectedThenDoneConfirms() {
        var calls = 0
        show {
            var query by remember { mutableStateOf("") }
            MaterialTheme { ChannelSearchDialog(query, { query = it }, { calls++ }, {}) }
        }
        val field = compose.onNode(hasSetTextAction())
        field.performImeAction()
        compose.runOnIdle { assertEquals(0, calls) }
        field.assertIsFocused().performTextInput("News")
        field.performImeAction()
        assertEquals(1, calls)
    }

}

class SearchCancelDialogImeTest : DialogImeTestBase() {
    @Test fun searchCancelDoesNotConfirm() {
        var confirms = 0
        var cancels = 0
        show {
            MaterialTheme { ChannelSearchDialog("News", {}, { confirms++ }, { cancels++ }) }
        }
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, confirms)
        assertEquals(1, cancels)
    }

}

class VisibleOkDialogImeTest : DialogImeTestBase() {
    @Test fun visibleOkUsesSameChannelSubmissionGuard() {
        var calls = 0
        show {
            MaterialTheme { GoToChannelDialog(true, "2", 10, {}, { calls++ }, {}) }
        }
        compose.onNodeWithText("OK").performClick()
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(1, calls)
    }

}

class NumericDialogImeTest : DialogImeTestBase() {
    @Test fun numericInvalidDoneRetainsInputThenCommitsValidValue() {
        val saved = mutableListOf<Int>()
        show {
            MaterialTheme { NumberInputDialog("Number", 2, 1, 10, saved::add, {}) }
        }
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("99")
        field.performImeAction()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        field.assertIsFocused().performTextReplacement("5")
        field.performImeAction()
        assertEquals(listOf(5), saved)
    }

}

class UrlDialogImeTest : DialogImeTestBase() {
    @Test fun urlDoneSavesAndBlankCanRetry() {
        val saved = mutableListOf<String>()
        show { MaterialTheme { UrlInputDialog("", {}, saved::add) } }
        val field = compose.onNode(hasSetTextAction())
        field.performImeAction()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        field.performTextInput("https://example.test/list.m3u")
        field.performImeAction()
        assertEquals(listOf("https://example.test/list.m3u"), saved)
    }

}

class SetPinDialogImeTest : DialogImeTestBase() {
    @Test fun setPinNextMovesToConfirmationAndOnlyDoneSaves() {
        var saves = 0
        var dismisses = 0
        show {
            MaterialTheme { SetParentalPinDialog(PinOperation(), { dismisses++ }, { _, _ -> saves++; true }) }
        }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("1234")
        fields[0].performImeAction()
        fields[1].assertIsFocused()
        compose.runOnIdle { assertEquals(0, saves) }
        fields[1].performTextInput("1234")
        fields[1].performImeAction()
        assertEquals(1, saves)
        assertEquals(1, dismisses)
    }

}

class ChangePinDialogImeTest : DialogImeTestBase() {
    @Test fun changePinFinalDoneWaitsForMatchingSuccess() {
        var operation by mutableStateOf(PinOperation())
        var saved: Pair<String, String>? = null
        var request: PinRequest? = null
        var dismisses = 0
        show {
            MaterialTheme {
                ChangeParentalPinDialog(operation, { dismisses++ }, { next, oldPin, newPin ->
                    request = next
                    saved = oldPin to newPin
                    true
                })
            }
        }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("1234")
        fields[0].performImeAction()
        fields[1].assertIsFocused().performTextInput("5678")
        fields[1].performImeAction()
        fields[2].assertIsFocused().performTextInput("5678")
        compose.runOnIdle { assertNull(saved) }
        fields[2].performImeAction()
        compose.runOnUiThread {
            assertEquals("1234" to "5678", saved)
            assertEquals(0, dismisses)
            operation = PinOperation(request, PinStatus.Succeeded)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1, dismisses) }
    }

}

class MismatchPinDialogImeTest : DialogImeTestBase() {
    @Test fun mismatchingSetPinKeepsConfirmationEditable() {
        var saves = 0
        show { MaterialTheme { SetParentalPinDialog(PinOperation(), {}, { _, _ -> saves++; true }) } }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("1234")
        fields[0].performImeAction()
        fields[1].performTextInput("5678")
        fields[1].performImeAction()
        compose.runOnIdle { assertEquals(0, saves) }
        fields[1].assertIsFocused().performTextReplacement("1234")
        fields[1].performImeAction()
        assertEquals(1, saves)
    }
}
