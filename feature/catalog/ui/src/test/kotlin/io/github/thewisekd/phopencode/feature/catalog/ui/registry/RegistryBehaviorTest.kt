package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** core/ui's back button label. */
private const val BACK = "Go back"

/** Which intents the registry screens' controls send, and that they are large enough to tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class RegistryBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = ENGLISH_REGISTRY_STRINGS

    @Test
    fun `the roots keep the placeholder tag the navigation tests look for`() {
        val stage = Stage(compose)
        stage.show { RegistryScreen(CatalogRoute.Registry, registry, onIntent = {}) }
        compose.onNodeWithTag("placeholder:${CatalogRoute.Registry}").assertIsDisplayed()
        stage.show { CommandDetailScreen(CatalogRoute.CommandDetail("review"), commandShown, onIntent = {}) }
        compose.onNodeWithTag("placeholder:${CatalogRoute.CommandDetail("review")}").assertIsDisplayed()
        stage.show { AgentDetailScreen(CatalogRoute.AgentDetail("build"), agentShown, onIntent = {}) }
        compose.onNodeWithTag("placeholder:${CatalogRoute.AgentDetail("build")}").assertIsDisplayed()
        stage.show { ModelDetailScreen(CatalogRoute.ModelDetail("acme", "large"), modelShown, onIntent = {}) }
        compose.onNodeWithTag("placeholder:${CatalogRoute.ModelDetail("acme", "large")}").assertIsDisplayed()
    }

    @Test
    fun `the selected tab is marked and the others select theirs`() {
        val intents = mutableListOf<RegistryIntent>()
        compose.setRegistry(registry) { intents += it }

        compose.onNodeWithText(strings.tabs.getValue(RegistryTab.Commands)).assertIsSelected()
        compose.onNodeWithText(strings.tabs.getValue(RegistryTab.Agents)).assertIsNotSelected().assertTouchSizeIsAtLeast()
        compose.onNodeWithText(strings.tabs.getValue(RegistryTab.Agents)).performClick()
        compose.onNodeWithText(strings.tabs.getValue(RegistryTab.Models)).performClick()

        assertEquals(listOf<RegistryIntent>(RegistryIntent.SelectTab(RegistryTab.Agents), RegistryIntent.SelectTab(RegistryTab.Models)), intents)
    }

    @Test
    fun `back closes and reload reads the shown tab again`() {
        val intents = mutableListOf<RegistryIntent>()
        compose.setRegistry(registry.copy(tab = RegistryTab.Agents)) { intents += it }

        compose.onNodeWithContentDescription(BACK).assertTouchSizeIsAtLeast().performClick()
        compose.onNodeWithContentDescription(strings.reload).assertTouchSizeIsAtLeast().performClick()

        assertEquals(listOf<RegistryIntent>(RegistryIntent.Close, RegistryIntent.Reload(RegistryTab.Agents)), intents)
    }

    @Test
    fun `reload waits while the shown tab loads`() {
        compose.setRegistry(registry.copy(commands = CatalogList()))

        compose.onNodeWithContentDescription(strings.reload).assertIsNotEnabled()
    }

    @Test
    fun `a row opens its details`() {
        val intents = mutableListOf<RegistryIntent>()
        val stage = Stage(compose)
        stage.show { RegistryScreen(CatalogRoute.Registry, registry, onIntent = { intents += it }) }
        compose.onNode(hasText("/review") and hasClickAction(), useUnmergedTree = false)
            .assertTouchSizeIsAtLeast().performClick()
        stage.show { RegistryScreen(CatalogRoute.Registry, registry.copy(tab = RegistryTab.Agents), onIntent = { intents += it }) }
        compose.onNode(hasText(build.name, substring = true) and hasClickAction()).assertTouchSizeIsAtLeast().performClick()
        stage.show { RegistryScreen(CatalogRoute.Registry, registry.copy(tab = RegistryTab.Models), onIntent = { intents += it }) }
        compose.onNode(hasText(small.name, substring = true) and hasClickAction()).assertTouchSizeIsAtLeast().performClick()

        assertEquals(
            listOf(
                RegistryIntent.OpenCommand("review"),
                RegistryIntent.OpenAgent("build"),
                RegistryIntent.OpenModel("acme", "small"),
            ),
            intents,
        )
    }

    @Test
    fun `a row says what a tap does`() {
        compose.setRegistry(registry)

        compose.onNode(hasText("/review") and hasClickAction()).assert(
            SemanticsMatcher("has an open label") { it.config[SemanticsActions.OnClick].label == "Open details" },
        )
    }

    @Test
    fun `a failed list retries its own tab`() {
        val intents = mutableListOf<RegistryIntent>()
        compose.setRegistry(registry.copy(tab = RegistryTab.Models, models = CatalogList(loading = false, error = CatalogFailure.Unreachable))) {
            intents += it
        }

        compose.onNodeWithText(strings.retry, useUnmergedTree = true).performClick()

        assertEquals(listOf<RegistryIntent>(RegistryIntent.Reload(RegistryTab.Models)), intents)
    }

    @Test
    fun `retrying cannot fix a lost connection or refused credentials`() {
        val stage = Stage(compose)
        listOf(CatalogFailure.NotConnected, CatalogFailure.Unauthorized).forEach { failure ->
            val state = registry.copy(commands = CatalogList(loading = false, error = failure))
            stage.show { RegistryScreen(CatalogRoute.Registry, state, onIntent = {}) }
            assertEquals(0, compose.onAllNodesWithText(strings.retry, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `progress is announced as indeterminate`() {
        compose.setRegistry(registry.copy(commands = CatalogList()))

        compose.onNodeWithContentDescription(strings.loading.getValue(RegistryTab.Commands))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    }

    @Test
    fun `a command detail closes and retries`() {
        val intents = mutableListOf<CommandDetailIntent>()
        compose.setCommand(CommandDetailState(name = "review", loading = false, error = CatalogFailure.ServerFailed)) { intents += it }

        compose.onNodeWithText(strings.retry, useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription(BACK).performClick()

        assertEquals(listOf<CommandDetailIntent>(CommandDetailIntent.Retry, CommandDetailIntent.Close), intents)
    }

    @Test
    fun `an agent opens its model, closes and retries`() {
        val intents = mutableListOf<AgentDetailIntent>()
        val stage = Stage(compose)
        stage.show { AgentDetailScreen(CatalogRoute.AgentDetail("build"), agentShown, onIntent = { intents += it }) }
        compose.onNode(hasText("acme/large", substring = true) and hasClickAction())
            .performScrollTo().assertTouchSizeIsAtLeast().performClick()
        compose.onNodeWithContentDescription(BACK).performClick()
        val failed = AgentDetailState(agentId = "build", loading = false, error = CatalogFailure.Unreachable)
        stage.show { AgentDetailScreen(CatalogRoute.AgentDetail("build"), failed, onIntent = { intents += it }) }
        compose.onNodeWithText(strings.retry, useUnmergedTree = true).performClick()

        assertEquals(listOf<AgentDetailIntent>(AgentDetailIntent.OpenModel, AgentDetailIntent.Close, AgentDetailIntent.Retry), intents)
    }

    @Test
    fun `an agent without a model has nothing to open`() {
        compose.setAgent(AgentDetailState(agentId = "explore", loading = false, agent = explore))

        compose.onNode(hasText(strings.sessionModel) and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun `a model closes and retries`() {
        val intents = mutableListOf<ModelDetailIntent>()
        compose.setModel(ModelDetailState(providerId = "acme", modelId = "large", loading = false, error = CatalogFailure.NotFound)) {
            intents += it
        }

        compose.onNodeWithText(strings.retry, useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription(BACK).assertIsEnabled().performClick()

        assertEquals(listOf<ModelDetailIntent>(ModelDetailIntent.Retry, ModelDetailIntent.Close), intents)
    }

    @Test
    fun `the details are titled before they load`() {
        val stage = Stage(compose)
        stage.show { CommandDetailScreen(CatalogRoute.CommandDetail("review"), CommandDetailState(name = "review"), onIntent = {}) }
        compose.onNodeWithText("/review").assertIsDisplayed()
        stage.show { AgentDetailScreen(CatalogRoute.AgentDetail("build"), AgentDetailState(agentId = "build"), onIntent = {}) }
        compose.onNodeWithText("build").assertIsDisplayed()
        val model = ModelDetailState(providerId = "acme", modelId = "large")
        stage.show { ModelDetailScreen(CatalogRoute.ModelDetail("acme", "large"), model, onIntent = {}) }
        compose.onNodeWithText("large").assertIsDisplayed()
    }
}
