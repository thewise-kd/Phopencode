package io.github.thewisekd.phopencode.feature.chat.ui.switcher

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.AgentOption
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ModelOption
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the switcher draws and sends, in English: the texts of both languages are in `SwitcherLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SwitcherBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    /** Scrolled into view first: the sheet's content scrolls. */
    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()

    private fun merged(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).performScrollTo()

    private fun absent(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun click(tag: String) {
        merged(tag).performClick()
        compose.waitForIdle()
    }

    // The compact control

    @Test
    fun `the control shows the current agent, model and variant by their names`() {
        compose.setSwitcher(loaded(open = false))

        assertEquals("Build", node(SWITCHER_AGENT_TAG).text())
        assertEquals("Sonnet ğüşiöç", node(SWITCHER_MODEL_TAG).text())
        assertEquals("high", node(SWITCHER_VARIANT_TAG).text())
        absent(SWITCHER_SHEET_TAG)
    }

    @Test
    fun `a current choice the lists do not name is shown by its ID, the model's provider named only for a screen reader`() {
        compose.setSwitcher(SwitcherState(enabled = true, agent = "gizli-ajan", model = UNLISTED))

        assertEquals("gizli-ajan", node(SWITCHER_AGENT_TAG).text())
        assertEquals("gpt-x", node(SWITCHER_MODEL_TAG).text())
        assertEquals("Model: openai/gpt-x", node(SWITCHER_MODEL_TAG).description())
        absent(SWITCHER_VARIANT_TAG)
    }

    @Test
    fun `very long names leave every part of the control visible`() {
        val long = "çok-uzun-".repeat(20)
        val agent = AgentOption(id = "a", name = "Ajan $long")
        val model = ModelOption(providerId = "p", modelId = "m", name = "Model $long", variants = listOf("v-$long"))
        compose.setSwitcher(
            SwitcherState(enabled = true, agent = "a", model = model.ref("v-$long"), agents = listOf(agent), models = listOf(model)),
        )

        for (tag in listOf(SWITCHER_AGENT_TAG, SWITCHER_MODEL_TAG, SWITCHER_VARIANT_TAG)) {
            val part = node(tag).assertIsDisplayed()
            assertTrue(part.fetchSemanticsNode().size.width > 0, tag)
        }
        val control = merged(SWITCHER_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        for (tag in listOf(SWITCHER_AGENT_TAG, SWITCHER_MODEL_TAG, SWITCHER_VARIANT_TAG)) {
            val bounds = node(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.right <= control.right + 0.5f && bounds.left >= control.left - 0.5f, "$tag $bounds in $control")
        }
    }

    @Test
    fun `the control sits in the slot it is given`() {
        compose.setSwitcher(loaded(open = false))

        compose.onNode(hasTestTag(SWITCHER_CONTROL_TAG) and hasAnyAncestor(hasTestTag(HOST_TAG))).assertExists()
    }

    @Test
    fun `tapping the control opens the sheet`() {
        val harness = compose.setSwitcher(loaded(open = false))

        click(SWITCHER_CONTROL_TAG)

        assertEquals(listOf<SwitcherIntent>(SwitcherIntent.Open), harness.intents)
    }

    @Test
    fun `tapping the agent chip or the model chip opens the sheet`() {
        val harness = compose.setSwitcher(loaded(open = false))

        node(SWITCHER_AGENT_TAG).performClick()
        compose.waitForIdle()
        node(SWITCHER_MODEL_TAG).performClick()
        compose.waitForIdle()

        assertEquals(listOf<SwitcherIntent>(SwitcherIntent.Open, SwitcherIntent.Open), harness.intents)
    }

    @Test
    fun `the chips are compact, side by side, and one target big enough to tap`() {
        compose.setSwitcher(loaded(open = false))

        val agent = node(SWITCHER_AGENT_TAG).fetchSemanticsNode().boundsInRoot
        val model = node(SWITCHER_MODEL_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(model.left >= agent.right, "the model chip at $model is not after the agent chip at $agent")
        val control = merged(SWITCHER_CONTROL_TAG).fetchSemanticsNode()
        val height = with(control.layoutInfo.density) { control.touchBoundsInRoot.height.toDp() }
        assertTrue(height >= 47.5.dp, "the control's touch height is $height")
    }

    @Test
    fun `the control cannot be tapped before the session is ready`() {
        val harness = compose.setSwitcher(SwitcherState())

        merged(SWITCHER_CONTROL_TAG).assertIsNotEnabled()
        merged(SWITCHER_CONTROL_TAG).performClick()
        compose.waitForIdle()

        assertTrue(harness.intents.isEmpty())
    }

    // The sheet

    @Test
    fun `the sheet follows the state`() {
        val harness = compose.setSwitcher(loaded(open = false))
        absent(SWITCHER_SHEET_TAG)

        harness.state.value = loaded(open = true)
        compose.waitForIdle()
        node(SWITCHER_TITLE_TAG)

        harness.state.value = loaded(open = false)
        compose.waitForIdle()
        absent(SWITCHER_SHEET_TAG)
    }

    @Test
    fun `the close button closes the sheet`() {
        val harness = compose.setSwitcher(loaded())

        click(SWITCHER_CLOSE_TAG)

        assertEquals(listOf<SwitcherIntent>(SwitcherIntent.Close), harness.intents)
    }

    @Test
    fun `the current agent is marked and another can be picked`() {
        val harness = compose.setSwitcher(loaded())

        merged(switcherAgentTag(BUILD.id)).assertIsSelected()
        merged(switcherAgentTag(PLAN.id)).assertIsNotSelected()
        assertTrue(merged(switcherAgentTag(PLAN.id)).text().contains("Plan çizici"))
        assertTrue(merged(switcherAgentTag(BUILD.id)).text().contains("Kodu yazar ve çalıştırır"))

        click(switcherAgentTag(PLAN.id))

        assertEquals(listOf<SwitcherIntent>(SwitcherIntent.PickAgent(PLAN.id)), harness.intents)
    }

    @Test
    fun `the current model and variant are marked`() {
        compose.setSwitcher(loaded())

        merged(switcherModelTag(SONNET.providerId, SONNET.modelId)).assertIsSelected()
        merged(switcherModelTag(LOCAL.providerId, LOCAL.modelId)).assertIsNotSelected()
        merged(switcherVariantTag(SONNET.providerId, SONNET.modelId, "high")).assertIsSelected()
        merged(switcherVariantTag(SONNET.providerId, SONNET.modelId, "max")).assertIsNotSelected()
        merged(switcherVariantTag(SONNET.providerId, SONNET.modelId, null)).assertIsNotSelected()
        assertTrue(merged(switcherModelTag(LOCAL.providerId, LOCAL.modelId)).text().contains("Qwen yerel"))
        // A model without variants offers none.
        absent(switcherVariantTag(LOCAL.providerId, LOCAL.modelId, null))
    }

    @Test
    fun `the default variant is marked when the current model has none set`() {
        compose.setSwitcher(loaded().copy(model = SONNET.ref()))

        merged(switcherVariantTag(SONNET.providerId, SONNET.modelId, null)).assertIsSelected()
        merged(switcherVariantTag(SONNET.providerId, SONNET.modelId, "high")).assertIsNotSelected()
    }

    @Test
    fun `picking a model or a variant sends the model with its variant`() {
        val harness = compose.setSwitcher(loaded())

        click(switcherModelTag(LOCAL.providerId, LOCAL.modelId))
        click(switcherVariantTag(SONNET.providerId, SONNET.modelId, "max"))
        click(switcherVariantTag(SONNET.providerId, SONNET.modelId, null))
        // The current model's row keeps its variant, so the logic sees nothing new.
        click(switcherModelTag(SONNET.providerId, SONNET.modelId))

        assertEquals(
            listOf<SwitcherIntent>(
                SwitcherIntent.PickModel(ModelRef("local", "qwen", null)),
                SwitcherIntent.PickModel(ModelRef("anthropic", "sonnet", "max")),
                SwitcherIntent.PickModel(ModelRef("anthropic", "sonnet", null)),
                SwitcherIntent.PickModel(ModelRef("anthropic", "sonnet", "high")),
            ),
            harness.intents,
        )
    }

    @Test
    fun `while a switch is on its way nothing can be picked`() {
        val harness = compose.setSwitcher(loaded().copy(switching = true))

        node(SWITCHER_SWITCHING_TAG)
        merged(switcherAgentTag(PLAN.id)).assertIsNotEnabled()
        merged(switcherModelTag(LOCAL.providerId, LOCAL.modelId)).assertIsNotEnabled()
        merged(switcherVariantTag(SONNET.providerId, SONNET.modelId, "max")).assertIsNotEnabled()
        merged(SWITCHER_CLOSE_TAG).assertIsEnabled()
        // The current choice stays marked.
        merged(switcherAgentTag(BUILD.id)).assertIsSelected()

        merged(switcherAgentTag(PLAN.id)).performClick()
        compose.waitForIdle()
        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `while the lists load a still note shows instead`() {
        compose.setSwitcher(SwitcherState(enabled = true, open = true, loading = true, agent = "build"))

        node(SWITCHER_LOADING_TAG)
        absent(SWITCHER_NO_AGENTS_TAG)
        absent(SWITCHER_NO_MODELS_TAG)
        absent(SWITCHER_SWITCHING_TAG)
    }

    @Test
    fun `empty lists say so`() {
        compose.setSwitcher(SwitcherState(enabled = true, open = true))

        node(SWITCHER_NO_AGENTS_TAG)
        node(SWITCHER_NO_MODELS_TAG)
        absent(SWITCHER_LOADING_TAG)
    }

    @Test
    fun `a failed load can be retried and dismissed`() {
        val harness = compose.setSwitcher(
            SwitcherState(enabled = true, open = true, error = SwitcherError(SwitcherAction.Load, ChatFailure.Unreachable)),
        )
        absent(SWITCHER_NO_AGENTS_TAG)

        val retry = compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(SWITCHER_ERROR_TAG)) and hasRetryText())
        retry.performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(SWITCHER_ERROR_TAG)) and hasDismissLabel())
            .performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf(SwitcherIntent.Retry, SwitcherIntent.DismissError), harness.intents)
    }

    @Test
    fun `a failed switch can be dismissed but not retried`() {
        val harness = compose.setSwitcher(loaded().copy(error = SwitcherError(SwitcherAction.SwitchModel, ChatFailure.Rejected)))

        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(SWITCHER_ERROR_TAG)) and hasRetryText()).assertDoesNotExist()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(SWITCHER_ERROR_TAG)) and hasDismissLabel())
            .performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf<SwitcherIntent>(SwitcherIntent.DismissError), harness.intents)
    }

    private fun hasRetryText() = hasText("Try again")

    private fun hasDismissLabel() = hasContentDescription("Dismiss error")
}
