package io.github.thewisekd.phopencode.feature.preview.ui.ports

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ManualPortProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.ui.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** core/ui's error banner, in English. */
private const val RETRY = "Try again"
private const val DISMISS = "Dismiss error"

/**
 * What the port picker sends and which controls it enables; the texts are in English and checked in
 * [PortsLanguageContract]. Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class PortsScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<PortsIntent>()

    private fun show(state: PortsState) = compose.setPortsScreen(state) { sent += it }

    private val hasError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

    /** Every state the screen draws, for the checks that hold in each one. */
    private val everyState = listOf(
        PortsState(),
        listed,
        listed.copy(refreshing = true),
        onlyOpencode,
        empty,
        unsupported,
        empty.copy(error = PreviewFailure.NotConnected),
        empty.copy(error = PreviewFailure.CommandFailed),
        listed.copy(error = PreviewFailure.ConnectionLost),
        listed.copy(manualText = "70000", manualProblem = ManualPortProblem.OutOfRange),
    )

    @Test
    fun `the root keeps the route's placeholder tag in every state`() {
        val states = PortsStates(compose) { sent += it }
        everyState.forEach { state ->
            states.show(state)
            compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
        }
    }

    @Test
    fun `a listed port opens it`() {
        show(listed)

        compose.scrollPortsTo(hasTestTag(PortsTags.port(DEV_PORT)))
        compose.onNodeWithTag(PortsTags.port(DEV_PORT)).assertIsEnabled().performClick()

        assertEquals(listOf<PortsIntent>(PortsIntent.Open(DEV_PORT)), sent)
    }

    @Test
    fun `the opencode port is listed but disabled and opens nothing`() {
        show(listed)

        compose.scrollPortsTo(hasTestTag(PortsTags.port(OPENCODE_PORT)))
        compose.onNodeWithTag(PortsTags.port(OPENCODE_PORT)).assertIsDisplayed().assertIsNotEnabled().performClick()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `each listed port has a row of its own`() {
        show(listed)

        listed.ports.forEach { port -> compose.onAllNodesWithTag(PortsTags.port(port.port)).assertCountEquals(1) }
    }

    @Test
    fun `refresh in the top bar lists the ports again`() {
        show(listed)

        compose.onNodeWithTag(PortsTags.REFRESH).assertIsEnabled().performClick()

        assertEquals(listOf<PortsIntent>(PortsIntent.Refresh), sent)
    }

    @Test
    fun `refresh waits while a listing runs`() {
        val states = PortsStates(compose) { sent += it }
        listOf(PortsState(), listed.copy(refreshing = true)).forEach { state ->
            states.show(state)
            compose.onNodeWithTag(PortsTags.REFRESH).assertIsNotEnabled()
        }
    }

    @Test
    fun `loading and refreshing show a still progress line`() {
        val states = PortsStates(compose) { sent += it }
        states.show(PortsState())
        compose.onNodeWithTag(PortsTags.LOADING).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        compose.onAllNodesWithTag(PortsTags.EMPTY).assertCountEquals(0)

        states.show(listed.copy(refreshing = true))
        compose.onNodeWithTag(PortsTags.REFRESHING).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        compose.onNodeWithTag(PortsTags.port(DEV_PORT)).assertExists()
    }

    @Test
    fun `an empty list explains itself and offers a refresh`() {
        show(empty)

        compose.onNodeWithTag(PortsTags.EMPTY).assertIsDisplayed()
        compose.onNode(hasText("Refresh") and hasClickAction()).performClick()

        assertEquals(listOf<PortsIntent>(PortsIntent.Refresh), sent)
    }

    @Test
    fun `a list with only the opencode port also explains that no dev server listens`() {
        show(onlyOpencode)

        compose.onNodeWithTag(PortsTags.EMPTY).assertExists()
        compose.onNodeWithTag(PortsTags.port(OPENCODE_PORT)).assertExists().assertIsNotEnabled()
    }

    @Test
    fun `an unsupported server says so and leaves the manual field`() {
        show(unsupported)

        compose.onNodeWithTag(PortsTags.UNSUPPORTED).assertIsDisplayed()
        compose.onAllNodesWithTag(PortsTags.EMPTY).assertCountEquals(0)
        compose.onNodeWithTag(PortsTags.MANUAL_FIELD).assertIsDisplayed()
    }

    @Test
    fun `not connected offers no retry and lists nothing`() {
        show(listed.copy(error = PreviewFailure.NotConnected))

        compose.onNodeWithTag(PortsTags.NOT_CONNECTED).assertIsDisplayed()
        compose.onAllNodesWithTag(PortsTags.ERROR).assertCountEquals(0)
        compose.onAllNodesWithTag(PortsTags.port(DEV_PORT)).assertCountEquals(0)
        compose.onAllNodesWithTag(PortsTags.EMPTY).assertCountEquals(0)
        compose.onAllNodes(hasText(RETRY) and hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun `a failed listing can be retried and dismissed`() {
        show(empty.copy(error = PreviewFailure.CommandFailed))

        compose.onNodeWithTag(PortsTags.ERROR).assertIsDisplayed()
        compose.onAllNodesWithTag(PortsTags.EMPTY).assertCountEquals(0)
        compose.onNode(hasText(RETRY) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(DISMISS).performClick()

        assertEquals(listOf(PortsIntent.Refresh, PortsIntent.DismissError), sent)
    }

    @Test
    fun `a failed refresh keeps the ports it had`() {
        show(listed.copy(error = PreviewFailure.TimedOut))

        compose.onNodeWithTag(PortsTags.ERROR).assertIsDisplayed()
        compose.onNodeWithTag(PortsTags.port(DEV_PORT)).assertExists()
    }

    @Test
    fun `typing in the manual field sends the text`() {
        show(listed)

        compose.onNodeWithTag(PortsTags.MANUAL_FIELD).performTextInput("3000")

        assertEquals(listOf<PortsIntent>(PortsIntent.EditManualPort("3000")), sent)
    }

    @Test
    fun `the manual field shows what the state holds`() {
        show(listed.copy(manualText = "3000"))

        compose.onNodeWithTag(PortsTags.MANUAL_FIELD)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("3000")))
    }

    @Test
    fun `the open button opens the manual port`() {
        show(listed.copy(manualText = "3000"))

        compose.onNodeWithTag(PortsTags.MANUAL_OPEN).assertIsEnabled().performClick()

        assertEquals(listOf<PortsIntent>(PortsIntent.OpenManualPort), sent)
    }

    @Test
    fun `the keyboard's go key opens the manual port`() {
        show(listed.copy(manualText = "3000"))

        compose.onNodeWithTag(PortsTags.MANUAL_FIELD)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Go))
            .performImeAction()

        assertEquals(listOf<PortsIntent>(PortsIntent.OpenManualPort), sent)
    }

    @Test
    fun `the manual field asks for a numeric keyboard`() {
        val view = compose.setPortsScreenInView(listed) { sent += it }

        compose.onNodeWithTag(PortsTags.MANUAL_FIELD).performClick()
        compose.waitForIdle()
        val info = EditorInfo()
        compose.runOnIdle { view().onCreateInputConnection(info) }

        assertEquals(InputType.TYPE_CLASS_NUMBER, info.inputType and InputType.TYPE_MASK_CLASS)
    }

    @Test
    fun `a manual problem marks the field in error and every problem has its own text`() {
        val states = PortsStates(compose) { sent += it }
        states.show(listed.copy(manualText = "3000"))
        compose.onAllNodes(hasTestTag(PortsTags.MANUAL_FIELD) and hasError).assertCountEquals(0)
        compose.onAllNodesWithTag(PortsTags.MANUAL_PROBLEM, useUnmergedTree = true).assertCountEquals(0)

        ManualPortProblem.entries.forEach { problem ->
            states.show(listed.copy(manualText = "x", manualProblem = problem))
            compose.onNode(hasTestTag(PortsTags.MANUAL_FIELD) and hasError).assertExists()
            compose.onNodeWithTag(PortsTags.MANUAL_PROBLEM, useUnmergedTree = true).assertExists()
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `back closes the screen`() {
        show(listed)

        compose.onNodeWithContentDescription("Navigate back").performClick()

        assertEquals(listOf<PortsIntent>(PortsIntent.Close), sent)
    }

    @Test
    fun `rows, refresh and open are at least 48 dp tall`() {
        show(listed)

        listed.ports.forEach { compose.onNodeWithTag(PortsTags.port(it.port)).assertHeightIsAtLeast(48.dp) }
        compose.onNodeWithTag(PortsTags.MANUAL_OPEN).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(PortsTags.MANUAL_FIELD).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(PortsTags.REFRESH).assertHasClickAction()
    }

    @Test
    fun `every state passes the accessibility audit at the largest text`() {
        val states = PortsStates(compose, fontScale = LARGE_FONT_SCALE) { sent += it }
        everyState.forEach { state ->
            states.show(state)
            compose.assertAccessible()
        }
    }

    @Test
    fun `listing errors are announced`() {
        val states = PortsStates(compose) { sent += it }
        mapOf(
            PreviewFailure.NotConnected to R.string.preview_ports_error_not_connected,
            PreviewFailure.CommandFailed to R.string.preview_ports_error_command_failed,
        ).forEach { (failure, message) ->
            states.show(empty.copy(error = failure))
            compose.assertErrorAnnounced(message)
        }
    }
}
