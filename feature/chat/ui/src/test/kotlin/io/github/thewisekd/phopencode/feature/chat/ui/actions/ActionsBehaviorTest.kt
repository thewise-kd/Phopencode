package io.github.thewisekd.phopencode.feature.chat.ui.actions

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SessionAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * What the session menu and a prompt's actions draw and send, in English, from hand-made states:
 * the texts of both languages are in `ActionsLanguageContract`.
 *
 * Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ActionsBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()

    private fun merged(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).performScrollTo()

    private fun absent(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun click(tag: String) {
        merged(tag).performClick()
        compose.waitForIdle()
    }

    // The menu button

    @Test
    fun `the menu button is named and opens the menu`() {
        val harness = compose.setActions(READY)

        merged(ActionsTags.MENU_BUTTON).assertIsEnabled().assert(hasContentDescription("Session actions"))
        absent(ActionsTags.MENU)
        absent(ActionsTags.STATUS)

        click(ActionsTags.MENU_BUTTON)

        assertEquals(listOf<ActionsIntent>(ActionsIntent.OpenMenu), harness.intents)
    }

    @Test
    fun `the menu button cannot be tapped before the session is ready, but keeps its place`() {
        val harness = compose.setActions(ActionsState())

        compose.onNodeWithTag(MENU_HOST_TAG).assertExists()
        merged(ActionsTags.MENU_BUTTON).assertIsNotEnabled()
        merged(ActionsTags.MENU_BUTTON).performClick()
        compose.waitForIdle()

        assertEquals(emptyList(), harness.intents)
    }

    @Test
    fun `the menu button is a full touch target`() {
        compose.setActions(READY)

        merged(ActionsTags.MENU_BUTTON).assertTouchSizeIsAtLeast(48.dp)
    }

    // The menu

    @Test
    fun `the open menu offers compact and fork, each asking first`() {
        val harness = compose.setActions(READY.copy(menuOpen = true))

        merged(ActionsTags.COMPACT).assert(hasText("Compact session")).assertIsEnabled()
        merged(ActionsTags.FORK).assert(hasText("Fork session")).assertIsEnabled()
        absent(ActionsTags.BUSY)

        click(ActionsTags.COMPACT)
        click(ActionsTags.FORK)

        assertEquals(
            listOf<ActionsIntent>(ActionsIntent.Request(SessionAction.Compact), ActionsIntent.Request(SessionAction.Fork(null))),
            harness.intents,
        )
    }

    @Test
    fun `the menu closes by its button`() {
        val harness = compose.setActions(READY.copy(menuOpen = true))

        click(ActionsTags.MENU_CLOSE)

        assertEquals(listOf<ActionsIntent>(ActionsIntent.CloseMenu), harness.intents)
    }

    @Test
    fun `while an action runs the menu offers nothing to start and says why`() {
        val harness = compose.setActions(READY.copy(menuOpen = true, running = SessionAction.Fork(null)))

        merged(ActionsTags.COMPACT).assertIsNotEnabled()
        merged(ActionsTags.FORK).assertIsNotEnabled()
        node(ActionsTags.BUSY)
        compose.onNodeWithTag(ActionsTags.COMPACT).performClick()
        compose.waitForIdle()

        assertEquals(emptyList(), harness.intents)
    }

    @Test
    fun `the menu's actions are full touch targets`() {
        compose.setActions(READY.copy(menuOpen = true))

        listOf(ActionsTags.COMPACT, ActionsTags.FORK, ActionsTags.MENU_CLOSE).forEach {
            merged(it).assertTouchSizeIsAtLeast(48.dp)
        }
    }

    // The confirmation

    @Test
    fun `a compaction waits for its confirmation`() {
        val harness = compose.setActions(READY.copy(confirming = SessionAction.Compact))

        assertEquals("Compact this session?", node(ActionsTags.CONFIRM_TITLE).text())
        merged(ActionsTags.CONFIRM).assert(hasText("Compact"))
        absent(ActionsTags.MENU)

        click(ActionsTags.CONFIRM)
        click(ActionsTags.CANCEL)

        assertEquals(listOf(ActionsIntent.Confirm, ActionsIntent.Cancel), harness.intents)
    }

    @Test
    fun `a fork of the whole session and a fork from a prompt say what they copy`() {
        val harness = compose.setActions(READY.copy(confirming = SessionAction.Fork(null)))
        assertEquals("Fork this session?", node(ActionsTags.CONFIRM_TITLE).text())
        node(ActionsTags.CONFIRM_MESSAGE).assert(hasText("whole conversation", substring = true))
        merged(ActionsTags.CONFIRM).assert(hasText("Fork"))

        harness.state.value = READY.copy(confirming = SessionAction.Fork(PROMPT))
        compose.waitForIdle()
        assertEquals("Fork from here?", node(ActionsTags.CONFIRM_TITLE).text())
        node(ActionsTags.CONFIRM_MESSAGE).assert(hasText("before this message", substring = true))

        click(ActionsTags.CONFIRM)
        assertEquals(listOf<ActionsIntent>(ActionsIntent.Confirm), harness.intents)
    }

    @Test
    fun `the confirmation's buttons are full touch targets`() {
        compose.setActions(READY.copy(confirming = SessionAction.Compact))

        merged(ActionsTags.CONFIRM).assertTouchSizeIsAtLeast(48.dp)
        merged(ActionsTags.CANCEL).assertTouchSizeIsAtLeast(48.dp)
    }

    @Test
    fun `the confirmation takes the place of the menu`() {
        compose.setActions(READY.copy(menuOpen = true, confirming = SessionAction.Compact))

        node(ActionsTags.CONFIRM_TITLE)
        absent(ActionsTags.MENU)
    }

    // The status

    @Test
    fun `a compaction on its way or on the server is shown as still text`() {
        val harness = compose.setActions(READY.copy(running = SessionAction.Compact))
        assertEquals("Compacting…", node(ActionsTags.STATUS).text())

        harness.state.value = READY.copy(compacting = true)
        compose.waitForIdle()
        assertEquals("Compacting…", node(ActionsTags.STATUS).text())

        harness.state.value = READY.copy(running = SessionAction.Fork(PROMPT))
        compose.waitForIdle()
        assertEquals("Forking…", node(ActionsTags.STATUS).text())

        harness.state.value = READY
        compose.waitForIdle()
        absent(ActionsTags.STATUS)
    }

    // Errors

    @Test
    fun `a failed action says what failed and can be dismissed`() {
        val harness = compose.setActions(READY.copy(error = ActionError(SessionAction.Compact, ChatFailure.Conflict)))

        assertEquals(
            "The session could not be compacted. The session is busy or changed meanwhile.",
            merged(ActionsTags.ERROR).text(),
        )
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(ActionsTags.ERROR)) and hasContentDescription("Dismiss error"))
            .performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf<ActionsIntent>(ActionsIntent.DismissError), harness.intents)
    }

    @Test
    fun `the open menu shows the last failure instead of a separate notice`() {
        compose.setActions(READY.copy(menuOpen = true, error = ActionError(SessionAction.Fork(null), ChatFailure.Unreachable)))

        compose.onNodeWithTag(ActionsTags.MENU).assertExists()
        assertEquals(1, compose.onAllNodesWithTag(ActionsTags.ERROR).fetchSemanticsNodes().size)
        assertEquals("The session could not be forked. The server could not be reached.", merged(ActionsTags.ERROR).text())
    }

    // A prompt's actions

    @Test
    fun `a prompt's actions open its turn's changes and close`() {
        val harness = compose.setActions(READY, withMessage = true)

        click(ActionsTags.TURN_CHANGES)

        assertEquals(listOf<ActionsIntent>(ActionsIntent.ShowTurnChanges(PROMPT)), harness.intents)
        assertEquals(1, harness.dismissed)
    }

    @Test
    fun `fork from here asks first and closes the prompt's actions`() {
        val harness = compose.setActions(READY, withMessage = true)

        click(ActionsTags.FORK_HERE)

        assertEquals(listOf<ActionsIntent>(ActionsIntent.Request(SessionAction.Fork(PROMPT))), harness.intents)
        assertEquals(1, harness.dismissed)
    }

    @Test
    fun `the prompt's actions close without sending anything`() {
        val harness = compose.setActions(READY, withMessage = true)

        merged(ActionsTags.MESSAGE_CLOSE).assert(hasContentDescription("Close actions"))
        click(ActionsTags.MESSAGE_CLOSE)

        assertEquals(emptyList(), harness.intents)
        assertEquals(1, harness.dismissed)
    }

    @Test
    fun `fork from here waits while an action runs or the session is not ready, the changes do not`() {
        val harness = compose.setActions(READY.copy(running = SessionAction.Compact), withMessage = true)
        merged(ActionsTags.FORK_HERE).assertIsNotEnabled()
        merged(ActionsTags.TURN_CHANGES).assertIsEnabled()

        harness.state.value = ActionsState()
        compose.waitForIdle()
        merged(ActionsTags.FORK_HERE).assertIsNotEnabled()
        merged(ActionsTags.TURN_CHANGES).assertIsEnabled()
    }

    @Test
    fun `the prompt's actions are full touch targets`() {
        compose.setActions(READY, withMessage = true)

        listOf(ActionsTags.TURN_CHANGES, ActionsTags.FORK_HERE, ActionsTags.MESSAGE_CLOSE).forEach {
            merged(it).assertTouchSizeIsAtLeast(48.dp)
        }
    }

    @Test
    fun `the confirmation of a fork from a prompt is drawn once, by the session menu`() {
        compose.setActions(READY.copy(confirming = SessionAction.Fork(PROMPT)), withMessage = true)

        assertEquals(1, compose.onAllNodesWithTag(ActionsTags.CONFIRM_TITLE, useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
