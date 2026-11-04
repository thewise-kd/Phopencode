package io.github.thewisekd.phopencode.feature.connect.ui.progress

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressState
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

private const val CANCEL = "Cancel"
private const val SUBMIT = "Connect"
private const val DONE = "Done"
private const val CURRENT = "In progress"
private const val FAILED = "Failed"
private const val PENDING = "Waiting"
private const val REJECTED = "The opencode server refused the password. Enter it again."

private val ACTION_LABELS = mapOf(
    ProgressAction.Retry to "Try again",
    ProgressAction.EditServer to "Edit server",
    ProgressAction.ReviewHostKey to "Review host key",
    ProgressAction.EnterServerPassword to "Enter password",
)

private val ACTION_INTENTS = mapOf(
    ProgressAction.Retry to ProgressIntent.Retry,
    ProgressAction.EditServer to ProgressIntent.EditServer,
    ProgressAction.ReviewHostKey to ProgressIntent.ReviewHostKey,
    ProgressAction.EnterServerPassword to ProgressIntent.EnterServerPassword,
)

/** Built at runtime, so it reads as what it is: a made-up value. */
private val TYPED = listOf("not", "a", "real", "one").joinToString("-")

/** What the connection screen draws and sends, in English: the texts of both languages are in the contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ProgressScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val connecting = ProgressState(serverName = SERVER_NAME)
    private val asking = ProgressState(
        serverName = SERVER_NAME,
        step = ProgressStep.Server,
        error = ProgressError.ServerPasswordUnknown,
        actions = setOf(ProgressAction.EnterServerPassword),
        askingServerPassword = true,
    )

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun assertSteps(vararg expected: String) {
        for ((step, description) in SHOWN_STEPS.zip(expected.toList())) {
            compose.onNodeWithTag(stepTag(step)).assert(hasStateDescription(description))
        }
    }

    // Step indicator

    @Test
    fun `each running step is current, the ones before it done and the ones after it waiting`() {
        val harness = compose.setProgressScreen(connecting)

        for ((index, step) in SHOWN_STEPS.withIndex()) {
            harness.state.value = connecting.copy(step = step)

            val expected = SHOWN_STEPS.indices.map { i ->
                when {
                    i < index -> DONE
                    i == index -> CURRENT
                    else -> PENDING
                }
            }
            assertSteps(*expected.toTypedArray())
        }
    }

    @Test
    fun `connected shows every step done`() {
        compose.setProgressScreen(connecting.copy(step = ProgressStep.Connected))

        assertSteps(DONE, DONE, DONE, DONE)
    }

    @Test
    fun `an error marks its step failed`() {
        val harness = compose.setProgressScreen(connecting)

        for ((index, step) in SHOWN_STEPS.withIndex()) {
            harness.state.value = connecting.copy(step = step, error = ProgressError.Disconnected)

            val expected = SHOWN_STEPS.indices.map { i ->
                when {
                    i < index -> DONE
                    i == index -> FAILED
                    else -> PENDING
                }
            }
            assertSteps(*expected.toTypedArray())
        }
    }

    @Test
    fun `the server name is shown`() {
        compose.setProgressScreen(connecting)

        compose.onNodeWithText(SERVER_NAME).assertIsDisplayed()
    }

    @Test
    fun `no error panel while connecting`() {
        compose.setProgressScreen(connecting.copy(step = ProgressStep.Api))

        compose.onNodeWithTag(PROGRESS_ERROR_TAG).assertDoesNotExist()
    }

    // Actions

    @Test
    fun `only the state's actions are offered`() {
        compose.setProgressScreen(
            connecting.copy(
                error = ProgressError.Unreachable,
                actions = setOf(ProgressAction.Retry, ProgressAction.EditServer),
            ),
        )

        compose.onNodeWithText(ACTION_LABELS.getValue(ProgressAction.Retry)).assertIsDisplayed()
        compose.onNodeWithText(ACTION_LABELS.getValue(ProgressAction.EditServer)).assertIsDisplayed()
        compose.onNodeWithText(ACTION_LABELS.getValue(ProgressAction.ReviewHostKey)).assertDoesNotExist()
        compose.onNodeWithText(ACTION_LABELS.getValue(ProgressAction.EnterServerPassword)).assertDoesNotExist()
    }

    @Test
    fun `no actions while connecting`() {
        compose.setProgressScreen(connecting)

        for (label in ACTION_LABELS.values) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test
    fun `each action sends its intent`() {
        assertEquals(ProgressAction.entries.toSet(), ACTION_INTENTS.keys)
        val harness = compose.setProgressScreen(
            connecting.copy(error = ProgressError.Unexpected, actions = ProgressAction.entries.toSet()),
        )

        for ((action, intent) in ACTION_INTENTS) {
            harness.intents.clear()
            compose.onNodeWithText(ACTION_LABELS.getValue(action)).performClick()

            compose.runOnIdle { assertEquals(listOf<ProgressIntent>(intent), harness.intents) }
        }
    }

    @Test
    fun `cancel is offered at every step and with an error, and sends Cancel`() {
        val harness = compose.setProgressScreen(connecting)
        val states = SHOWN_STEPS.map { connecting.copy(step = it) } +
            connecting.copy(
                step = ProgressStep.Server,
                error = ProgressError.ServerLost,
                actions = setOf(ProgressAction.Retry),
            )

        for (state in states) {
            harness.state.value = state
            harness.intents.clear()

            compose.onNodeWithText(CANCEL).assertIsDisplayed().assertTouchSizeIsAtLeast().performClick()

            compose.runOnIdle { assertEquals(listOf<ProgressIntent>(ProgressIntent.Cancel), harness.intents) }
        }
    }

    @Test
    fun `action buttons are at least 48dp`() {
        compose.setProgressScreen(
            connecting.copy(error = ProgressError.Unexpected, actions = ProgressAction.entries.toSet()),
        )

        for (label in ACTION_LABELS.values) {
            compose.onNodeWithText(label).assertTouchSizeIsAtLeast()
        }
    }

    // Server password prompt

    @Test
    fun `no password prompt unless asked`() {
        compose.setProgressScreen(asking.copy(askingServerPassword = false))

        compose.onNodeWithTag(SERVER_SECRET_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun `the password is masked and sent only through SubmitServerPassword`() {
        val harness = compose.setProgressScreen(asking)

        compose.onNodeWithTag(SERVER_SECRET_FIELD_TAG).assert(isPassword).performTextInput(TYPED)

        compose.onNode(showsText(TYPED)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList(), harness.intents) }
        inDialog(SUBMIT).assertTouchSizeIsAtLeast().performClick()
        compose.runOnIdle {
            assertEquals(listOf<ProgressIntent>(ProgressIntent.SubmitServerPassword(TYPED)), harness.intents)
        }
    }

    @Test
    fun `the field is cleared after submit`() {
        compose.setProgressScreen(asking)

        compose.onNodeWithTag(SERVER_SECRET_FIELD_TAG).performTextInput(TYPED)
        inDialog(SUBMIT).performClick()

        // The state still asks, as it does when the presenter refuses the password.
        compose.onNodeWithTag(SERVER_SECRET_FIELD_TAG).assert(isEmptyField)
        inDialog(SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun `submit is disabled until a password is typed`() {
        compose.setProgressScreen(asking)

        inDialog(SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(SERVER_SECRET_FIELD_TAG).performTextInput("x")
        inDialog(SUBMIT).assertIsEnabled()
    }

    @Test
    fun `dismissing sends DismissServerPassword and clears the field`() {
        val harness = compose.setProgressScreen(asking)

        compose.onNodeWithTag(SERVER_SECRET_FIELD_TAG).performTextInput(TYPED)
        inDialog(CANCEL).assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle {
            assertEquals(listOf<ProgressIntent>(ProgressIntent.DismissServerPassword), harness.intents)
        }
        compose.onNodeWithTag(SERVER_SECRET_FIELD_TAG).assert(isEmptyField)
    }

    @Test
    fun `a prompt asked again starts empty`() {
        val harness = compose.setProgressScreen(asking)

        compose.onNodeWithTag(SERVER_SECRET_FIELD_TAG).performTextInput(TYPED)
        harness.state.value = asking.copy(askingServerPassword = false)
        compose.onNodeWithTag(SERVER_SECRET_FIELD_TAG).assertDoesNotExist()
        harness.state.value = asking

        compose.onNodeWithTag(SERVER_SECRET_FIELD_TAG).assert(isEmptyField)
        compose.runOnIdle { assertEquals(emptyList(), harness.intents) }
    }

    @Test
    fun `a refused password shows in the prompt`() {
        compose.setProgressScreen(asking.copy(error = ProgressError.ServerPasswordRejected))

        inDialog(REJECTED).assertIsDisplayed()
    }
}
