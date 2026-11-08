package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryTab
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The texts of the registry and its detail screens in one language. */
internal data class ExpectedRegistryStrings(
    val title: String,
    val tabs: Map<RegistryTab, String>,
    val reload: String,
    val loading: Map<RegistryTab, String>,
    val reloading: String,
    val emptyTitles: Map<RegistryTab, String>,
    val emptyMessages: Map<RegistryTab, String>,
    val noDescription: String,
    val modes: List<String>,
    /** The unknown `sideways` mode. */
    val unknownMode: String,
    val hidden: String,
    val default: String,
    /** A model row's 200 000 token context. */
    val contextLine: String,
    val loadingCommand: String,
    val loadingAgent: String,
    val loadingModel: String,
    val description: String,
    val mode: String,
    val model: String,
    val permissions: String,
    val noPermissions: String,
    val sessionModel: String,
    /** Allow, deny, ask and the unknown `maybe`. */
    val effects: List<String>,
    val provider: String,
    val modelId: String,
    val limits: String,
    val limitNames: List<String>,
    /** 200 000 and 64 000 tokens. */
    val limitValues: List<String>,
    val notGiven: String,
    val pricing: String,
    val pricingUnit: String,
    val baseTier: String,
    /** The tier above 200 000 context tokens. */
    val contextTier: String,
    val priceNames: List<String>,
    /** 3, 15, 0.075, 6 and 22.5. */
    val prices: List<String>,
    val unknownPrice: String,
    val noPricing: String,
    val variants: String,
    val errors: Map<CatalogFailure, String>,
    val retry: String,
)

/** What the registry screens show, the same in each language; a subclass sets the language and its texts. */
abstract class RegistryLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedRegistryStrings

    @Test
    fun `the registry has its title, tabs and reload button`() {
        compose.setRegistry(registry)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        strings.tabs.values.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription(strings.reload).assertIsDisplayed()
    }

    @Test
    fun `the command tab lists names and descriptions`() {
        compose.setRegistry(registry)

        compose.onNodeWithText("/review", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(review.description!!, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("/release", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(strings.noDescription, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the agent tab names each mode and marks a hidden agent`() {
        compose.setRegistry(registry.copy(tab = RegistryTab.Agents))

        compose.onNodeWithText(build.name, useUnmergedTree = true).assertIsDisplayed()
        (strings.modes + strings.unknownMode + strings.hidden).forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `the model tab shows the provider, context and default`() {
        compose.setRegistry(registry.copy(tab = RegistryTab.Models))

        compose.onNodeWithText(large.name, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(strings.contextLine, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(strings.default, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText(acme.name, substring = true, useUnmergedTree = true).onFirst().assertIsDisplayed()
        // A provider the server does not list is shown by its ID.
        compose.onNodeWithText(stray.providerId, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `each tab says when it loads`() {
        val stage = Stage(compose)
        RegistryTab.entries.forEach { tab ->
            val state = RegistryState(tab = tab)
            stage.show { RegistryScreen(CatalogRoute.Registry, state, onIntent = {}) }
            compose.onNodeWithContentDescription(strings.loading.getValue(tab)).assertIsDisplayed()
        }
    }

    @Test
    fun `a reloading list keeps its rows and says so`() {
        compose.setRegistry(registry.copy(commands = registry.commands.copy(loading = true)))

        compose.onNodeWithContentDescription(strings.reloading).assertIsDisplayed()
        compose.onNodeWithText("/review", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `each tab says when it is empty`() {
        val stage = Stage(compose)
        RegistryTab.entries.forEach { tab ->
            val state = RegistryState(
                tab = tab,
                commands = CatalogList(loading = false),
                agents = CatalogList(loading = false),
                models = CatalogList(loading = false),
            )
            stage.show { RegistryScreen(CatalogRoute.Registry, state, onIntent = {}) }
            compose.onNodeWithText(strings.emptyTitles.getValue(tab)).assertIsDisplayed()
            compose.onNodeWithText(strings.emptyMessages.getValue(tab)).assertIsDisplayed()
        }
    }

    @Test
    fun `every failure has its own message`() {
        assertEquals(CatalogFailure.entries.toSet(), strings.errors.keys)
        assertEquals(CatalogFailure.entries.size, strings.errors.values.toSet().size, "messages must differ")
    }

    @Test
    fun `a list's failure is explained on its tab`() {
        val stage = Stage(compose)
        strings.errors.forEach { (failure, message) ->
            val state = registry.copy(tab = RegistryTab.Models, models = CatalogList(loading = false, error = failure))
            stage.show { RegistryScreen(CatalogRoute.Registry, state, onIntent = {}) }
            compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun `a command shows its name and description`() {
        compose.setCommand(commandShown)

        compose.onNodeWithText("/review").assertIsDisplayed()
        compose.onNodeWithText(strings.description).assertIsDisplayed()
        compose.onNodeWithText(review.description!!).assertIsDisplayed()
    }

    @Test
    fun `a command without a description says so`() {
        compose.setCommand(CommandDetailState(name = "release", loading = false, command = release))

        compose.onNodeWithText(strings.noDescription).assertIsDisplayed()
    }

    @Test
    fun `the details say when they load`() {
        val stage = Stage(compose)
        stage.show { CommandDetailScreen(CatalogRoute.CommandDetail("review"), CommandDetailState(name = "review"), onIntent = {}) }
        compose.onNodeWithContentDescription(strings.loadingCommand).assertIsDisplayed()
        stage.show { AgentDetailScreen(CatalogRoute.AgentDetail("build"), AgentDetailState(agentId = "build"), onIntent = {}) }
        compose.onNodeWithContentDescription(strings.loadingAgent).assertIsDisplayed()
        stage.show {
            ModelDetailScreen(
                CatalogRoute.ModelDetail("acme", "large"),
                ModelDetailState(providerId = "acme", modelId = "large"),
                onIntent = {},
            )
        }
        compose.onNodeWithContentDescription(strings.loadingModel).assertIsDisplayed()
    }

    @Test
    fun `the details explain a failure`() {
        val stage = Stage(compose)
        strings.errors.forEach { (failure, message) ->
            val state = AgentDetailState(agentId = "build", loading = false, error = failure)
            stage.show { AgentDetailScreen(CatalogRoute.AgentDetail("build"), state, onIntent = {}) }
            compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun `an agent shows its description, mode, model and permissions`() {
        compose.setAgent(agentShown)

        compose.onNodeWithText(build.name).assertIsDisplayed()
        listOf(strings.description, strings.mode, strings.model, strings.permissions).forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(build.description!!).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.modes[0]).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("acme/large", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        buildRules.forEach { rule ->
            compose.onNodeWithText(rule.action, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(rule.resource, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        strings.effects.forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `an agent without a model or rules says so`() {
        compose.setAgent(AgentDetailState(agentId = "explore", loading = false, agent = explore))

        compose.onNodeWithText(strings.noDescription).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.sessionModel).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.noPermissions).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a model shows its provider, limits, prices and variants`() {
        compose.setModel(modelShown)

        compose.onNodeWithText(large.name).assertIsDisplayed()
        compose.onNodeWithText(strings.default, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        strings.limitNames.forEach { assertTrue(compose.countText(it) > 0, "no $it") }
        listOf(strings.provider, strings.modelId, strings.limits, strings.pricing, strings.variants)
            .forEach { compose.onNodeWithText(it, useUnmergedTree = true).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText(acme.name, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(large.id, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        strings.limitValues.forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        // No input limit was given, and no cache write price on the base tier.
        compose.onNodeWithText(strings.notGiven, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        listOf(strings.pricingUnit, strings.baseTier, strings.contextTier).forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        strings.priceNames.forEach {
            assertTrue(compose.countText(it) > 0, "no $it")
        }
        strings.prices.forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        assertTrue(compose.countText(strings.unknownPrice) > 0, "no unknown price")
        large.variants.forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `a model without prices says so`() {
        compose.setModel(modelShown.copy(modelId = "small", model = small, defaultModel = null))

        compose.onNodeWithText(strings.noPricing, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.countText(strings.default))
    }

    @Test
    fun `retry is offered for a failure retrying can fix`() {
        compose.setModel(ModelDetailState(providerId = "acme", modelId = "large", loading = false, error = CatalogFailure.Unreachable))

        compose.onNode(hasText(strings.retry), useUnmergedTree = true).assertIsDisplayed()
    }
}
