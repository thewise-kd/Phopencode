package io.github.thewisekd.phopencode.feature.chat.ui.switcher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.AgentOption
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ModelOption
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState

/** The tag the harness puts on the switcher's modifier, as the chat puts its slot tag there. */
internal const val HOST_TAG = "test:switcher"

/** Made-up agents, with Turkish letters so a lost character would show. */
internal val BUILD = AgentOption(id = "build", name = "Build", description = "Kodu yazar ve çalıştırır")
internal val PLAN = AgentOption(id = "plan", name = "Plan çizici")

/** A made-up model with variants, and one without. */
internal val SONNET = ModelOption(providerId = "anthropic", modelId = "sonnet", name = "Sonnet ğüşiöç", variants = listOf("high", "max"))
internal val LOCAL = ModelOption(providerId = "local", modelId = "qwen", name = "Qwen yerel")

/** A loaded, open sheet with `build` and Sonnet at `high` current. */
internal fun loaded(open: Boolean = true) = SwitcherState(
    enabled = true,
    open = open,
    agent = BUILD.id,
    model = SONNET.ref("high"),
    agents = listOf(BUILD, PLAN),
    models = listOf(SONNET, LOCAL),
)

/** A current model the server does not list. */
internal val UNLISTED: ModelRef = ModelRef("openai", "gpt-x", null)

/** The switcher in the app theme, with [state] changeable by the test and every intent recorded. */
internal class SwitcherHarness(initial: SwitcherState) {
    val state: MutableState<SwitcherState> = mutableStateOf(initial)
    val intents = mutableListOf<SwitcherIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                // Scrollable so tests can scroll the control into view, as in the other slots' harnesses.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AgentModelSwitcher(
                        state = state.value,
                        onIntent = { intents += it },
                        modifier = Modifier.fillMaxWidth().testTag(HOST_TAG),
                    )
                }
            }
        }
    }
}

internal fun ComposeContentTestRule.setSwitcher(state: SwitcherState): SwitcherHarness {
    val harness = SwitcherHarness(state)
    setContent { harness.Content() }
    return harness
}

/** The text a node shows, its parts joined. */
internal fun SemanticsNodeInteraction.text(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

/** What a screen reader says for a node instead of its text. */
internal fun SemanticsNodeInteraction.description(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")

/** The label a screen reader gives a node's click. */
internal fun SemanticsNodeInteraction.clickLabel(): String? =
    fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
