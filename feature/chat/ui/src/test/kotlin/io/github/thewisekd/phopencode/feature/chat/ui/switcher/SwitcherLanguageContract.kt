package io.github.thewisekd.phopencode.feature.chat.ui.switcher

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The texts of the switcher in one language. */
internal data class ExpectedSwitcherStrings(
    val agentLabel: (String) -> String,
    val modelLabel: (String) -> String,
    val variantLabel: (String) -> String,
    val defaultAgent: String,
    val defaultModel: String,
    val change: String,
    val title: String,
    val close: String,
    val agents: String,
    val models: String,
    val defaultVariant: String,
    val loading: String,
    val switching: String,
    val noAgents: String,
    val noModels: String,
    val error: (SwitcherAction, reason: String) -> String,
    val reasons: Map<ChatFailure, String>,
)

/** What the switcher says, the same in each language; a subclass sets the language and its texts. */
abstract class SwitcherLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedSwitcherStrings

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()

    /** The merged node, as a screen reader reads a button. */
    private fun merged(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).performScrollTo()

    /** The switcher a test draws once and then changes; content can be set only once per test. */
    private var shown: SwitcherHarness? = null

    private fun show(state: SwitcherState) {
        val current = shown
        if (current == null) shown = compose.setSwitcher(state) else current.state.value = state
        compose.waitForIdle()
    }

    @Test
    fun `the control names what is current to a screen reader`() {
        show(loaded(open = false))
        assertEquals(strings.agentLabel("Build"), node(SWITCHER_AGENT_TAG).description())
        assertEquals(strings.modelLabel("Sonnet ğüşiöç"), node(SWITCHER_MODEL_TAG).description())
        assertEquals(strings.variantLabel("high"), node(SWITCHER_VARIANT_TAG).description())
        assertEquals(strings.change, merged(SWITCHER_CONTROL_TAG).clickLabel())
    }

    @Test
    fun `the control says when nothing is set`() {
        show(SwitcherState(enabled = true))
        assertEquals(strings.defaultAgent, node(SWITCHER_AGENT_TAG).text())
        assertEquals(strings.defaultModel, node(SWITCHER_MODEL_TAG).text())
        assertEquals(strings.agentLabel(strings.defaultAgent), node(SWITCHER_AGENT_TAG).description())
        assertEquals(strings.modelLabel(strings.defaultModel), node(SWITCHER_MODEL_TAG).description())
    }

    @Test
    fun `the sheet names its parts`() {
        show(loaded())
        assertEquals(strings.title, node(SWITCHER_TITLE_TAG).text())
        assertEquals(strings.close, merged(SWITCHER_CLOSE_TAG).text())
        assertEquals(strings.agents, node(SWITCHER_AGENTS_TAG).text())
        assertEquals(strings.models, node(SWITCHER_MODELS_TAG).text())
        assertEquals(strings.defaultVariant, merged(switcherVariantTag(SONNET.providerId, SONNET.modelId, null)).text())
    }

    @Test
    fun `the still notes are named`() {
        show(SwitcherState(enabled = true, open = true, loading = true))
        assertEquals(strings.loading, node(SWITCHER_LOADING_TAG).text())

        show(loaded().copy(switching = true))
        assertEquals(strings.switching, node(SWITCHER_SWITCHING_TAG).text())

        show(SwitcherState(enabled = true, open = true))
        assertEquals(strings.noAgents, node(SWITCHER_NO_AGENTS_TAG).text())
        assertEquals(strings.noModels, node(SWITCHER_NO_MODELS_TAG).text())
    }

    @Test
    fun `a failure says what failed and why`() {
        assertTrue(strings.reasons.keys == ChatFailure.entries.toSet())
        for (action in SwitcherAction.entries) {
            for ((reason, text) in strings.reasons) {
                show(loaded().copy(error = SwitcherError(action, reason)))
                assertEquals(strings.error(action, text), merged(SWITCHER_ERROR_TAG).text(), "$action $reason")
            }
        }
    }
}
