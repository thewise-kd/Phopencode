package io.github.thewisekd.phopencode.feature.chat.ui.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerAction
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerError
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SEND = "Send"
private const val STOP = "Stop"
private const val STEER = "Steer now"
private const val QUEUE = "Queue"
private const val DISMISS = "Dismiss error"
private const val CONTROLS = "test:controls"

/** What the composer sends, in English: the texts of both languages are in `ComposerLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ComposerBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    /** Scrolled into view first: at API 35+ the insets push the content down. */
    private fun button(text: String): SemanticsNodeInteraction = compose.onNodeWithText(text).performScrollTo()

    /** The round icon button, found by the name a screen reader gives it. */
    private fun named(name: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(name).performScrollTo()

    private fun field(): SemanticsNodeInteraction = compose.onNodeWithTag(COMPOSER_FIELD_TAG).performScrollTo()

    private fun action(): SemanticsNodeInteraction = compose.onNodeWithTag(COMPOSER_ACTION_TAG).performScrollTo()

    // Typing

    @Test
    fun `the field shows the draft as typed`() {
        compose.setComposer(ready(DRAFT))

        field().assertTextContains(DRAFT)
    }

    @Test
    fun `typing sends DraftChanged and nothing else`() {
        val harness = compose.setComposer(ready())

        field().performTextReplacement(DRAFT)

        assertEquals(listOf<ComposerIntent>(ComposerIntent.DraftChanged(DRAFT)), harness.intents)
    }

    @Test
    fun `the field is disabled while the session is not usable or a prompt is on its way`() {
        val harness = compose.setComposer(ComposerState(draft = DRAFT))
        field().assertIsNotEnabled()

        harness.state.value = ready(DRAFT).copy(sending = true)
        field().assertIsNotEnabled()

        harness.state.value = ready(DRAFT)
        field().assertIsEnabled()
    }

    // Send

    @Test
    fun `Send sends Send when there is something to send`() {
        val harness = compose.setComposer(ready(DRAFT))

        named(SEND).assertIsEnabled().performClick()

        assertEquals(listOf<ComposerIntent>(ComposerIntent.Send), harness.intents)
    }

    @Test
    fun `Send follows canSend`() {
        val cases = listOf(
            ready(),
            ready("   \n "),
            ComposerState(draft = DRAFT, enabled = false),
            ready(DRAFT).copy(sending = true),
        )
        val harness = compose.setComposer(cases.first())

        for (state in cases) {
            harness.state.value = state
            assertTrue(!state.canSend)
            named(SEND).assertIsNotEnabled().performClick()
        }

        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `a skill attached with nothing typed can be sent, other attachments alone cannot`() {
        val skill = PromptAttachment.Skill("granny-workflow")
        val harness = compose.setComposer(ready("  ").copy(attachments = listOf(PromptAttachment.Agent("explore"))))
        named(SEND).assertIsNotEnabled()

        harness.state.value = ready("  ").copy(attachments = listOf(PromptAttachment.Agent("explore"), skill))
        named(SEND).assertIsEnabled().performClick()
        assertEquals(listOf<ComposerIntent>(ComposerIntent.Send), harness.intents)

        harness.state.value = ready().copy(attachments = listOf(skill), enabled = false)
        named(SEND).assertIsNotEnabled()
    }

    @Test
    fun `while running, an attached skill is sent instead of stopping`() {
        val harness = compose.setComposer(running().copy(attachments = listOf(PromptAttachment.Skill("granny-workflow"))))

        compose.onNodeWithContentDescription(STOP).assertDoesNotExist()
        named(SEND).assertIsEnabled().performClick()

        assertEquals(listOf<ComposerIntent>(ComposerIntent.Send), harness.intents)
    }

    @Test
    fun `the button keeps one place whether it sends or stops`() {
        val harness = compose.setComposer(ready(DRAFT))
        action().assert(hasContentDescription(SEND))

        harness.state.value = running()
        action().assert(hasContentDescription(STOP))

        harness.state.value = running(DRAFT)
        action().assert(hasContentDescription(SEND))
    }

    // Stop

    @Test
    fun `with nothing typed, running work offers Stop, which sends Stop`() {
        val harness = compose.setComposer(running())

        compose.onNodeWithContentDescription(SEND).assertDoesNotExist()
        named(STOP).assertIsEnabled().performClick()

        assertEquals(listOf<ComposerIntent>(ComposerIntent.Stop), harness.intents)
    }

    @Test
    fun `Stop follows canStop`() {
        val harness = compose.setComposer(running().copy(stopping = true))
        named(STOP).assertIsNotEnabled().performClick()

        harness.state.value = running().copy(enabled = false)
        named(STOP).assertIsNotEnabled().performClick()

        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `while running, a typed prompt is sent instead of stopping`() {
        val harness = compose.setComposer(running(DRAFT))

        compose.onNodeWithContentDescription(STOP).assertDoesNotExist()
        named(SEND).assertIsEnabled().performClick()

        assertEquals(listOf<ComposerIntent>(ComposerIntent.Send), harness.intents)
    }

    @Test
    fun `nothing is offered to stop when nothing runs`() {
        compose.setComposer(ready())

        compose.onNodeWithContentDescription(STOP).assertDoesNotExist()
    }

    // Delivery

    @Test
    fun `the delivery choice shows only while running`() {
        val harness = compose.setComposer(ready(DRAFT))
        compose.onNodeWithText(STEER).assertDoesNotExist()
        compose.onNodeWithText(QUEUE).assertDoesNotExist()

        harness.state.value = running(DRAFT)
        button(STEER).assertExists()
        button(QUEUE).assertExists()
    }

    @Test
    fun `the delivery choice shows the state's choice`() {
        val harness = compose.setComposer(running(DRAFT))
        button(STEER).assertIsSelected()
        button(QUEUE).assertIsNotSelected()

        harness.state.value = running(DRAFT).copy(delivery = PromptDelivery.Queue)
        button(STEER).assertIsNotSelected()
        button(QUEUE).assertIsSelected()
    }

    @Test
    fun `choosing a delivery sends DeliveryChanged`() {
        val harness = compose.setComposer(running(DRAFT))

        button(QUEUE).performClick()

        assertEquals(listOf<ComposerIntent>(ComposerIntent.DeliveryChanged(PromptDelivery.Queue)), harness.intents)
    }

    @Test
    fun `the delivery cannot change while a prompt is on its way`() {
        val harness = compose.setComposer(running(DRAFT).copy(sending = true))

        button(QUEUE).assertIsNotEnabled().performClick()

        assertTrue(harness.intents.isEmpty())
    }

    // Errors

    @Test
    fun `an error can be dismissed`() {
        val harness = compose.setComposer(ready(DRAFT).copy(error = ComposerError(ComposerAction.Send, ChatFailure.Unreachable)))

        compose.onNodeWithContentDescription(DISMISS).performScrollTo().performClick()

        assertEquals(listOf<ComposerIntent>(ComposerIntent.DismissError), harness.intents)
    }

    @Test
    fun `a failed send keeps the draft in the field`() {
        compose.setComposer(ready(DRAFT).copy(error = ComposerError(ComposerAction.Send, ChatFailure.ServerFailed)))

        field().assertTextContains(DRAFT)
        named(SEND).assertIsEnabled()
    }

    @Test
    fun `no error shows without one`() {
        compose.setComposer(ready(DRAFT))

        compose.onNodeWithContentDescription(DISMISS).assertDoesNotExist()
    }

    // The design

    @Test
    fun `the button is a round icon named Send or Stop, with no text`() {
        val harness = compose.setComposer(ready(DRAFT))
        assertNull(action().fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text))

        harness.state.value = running()
        action().assert(hasContentDescription(STOP))
        assertNull(action().fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text))
    }

    @Test
    fun `the delivery choice is a single-choice group of two chips with no heading`() {
        compose.setComposer(running(DRAFT))

        for (choice in listOf(STEER, QUEUE)) {
            button(choice).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            compose.onNodeWithText(choice).onParent().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        }
        compose.onAllNodes(isHeading()).assertCountEquals(0)
    }

    @Test
    fun `the controls sit in the card under the field, and an error above the card`() {
        compose.setComposer(
            ready(DRAFT).copy(error = ComposerError(ComposerAction.Send, ChatFailure.Unreachable)),
            controls = { Box(Modifier.size(24.dp).testTag(CONTROLS)) },
        )

        compose.onNode(hasTestTag(CONTROLS) and hasAnyAncestor(hasTestTag(COMPOSER_CARD_TAG))).assertExists()
        val card = compose.onNodeWithTag(COMPOSER_CARD_TAG).getBoundsInRoot()
        val field = field().getBoundsInRoot()
        val controls = compose.onNodeWithTag(CONTROLS).getBoundsInRoot()
        val error = compose.onNodeWithContentDescription(DISMISS).getBoundsInRoot()
        assertTrue(controls.top >= field.bottom, "controls at $controls are not under the field at $field")
        assertTrue(error.bottom <= card.top, "the error at $error is not above the card at $card")
    }

    // Touch targets

    @Test
    fun `the buttons are large enough to touch`() {
        val harness = compose.setComposer(ready(DRAFT))
        action().assertTouchSizeIsAtLeast(48.dp)

        harness.state.value = running()
        action().assertTouchSizeIsAtLeast(48.dp)
        button(STEER).assertTouchSizeIsAtLeast(48.dp)
        button(QUEUE).assertTouchSizeIsAtLeast(48.dp)
    }
}
